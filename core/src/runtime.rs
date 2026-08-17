//! The tunnel event loop.
//!
//! One thread owns everything on the packet path. It drives a single `poll()` over the TUN
//! descriptor plus every upstream socket, so the thread sleeps whenever nothing is happening
//! rather than spinning — which is what keeps the battery cost of a userspace stack sane.
//!
//! That sleep is only real if nothing forces a wakeup. The loop used to cap its timeout at
//! 200 ms and re-run the entire pipeline on each expiry, so an idle tunnel cost five wakeups a
//! second forever. It now trusts smoltcp's `poll_delay` and sleeps until there is genuinely
//! something to do, which makes every off-thread event responsible for announcing itself —
//! see [`crate::wake`] for the three that must, and why a missed one would look like a hang
//! rather than like slowness.
//!
//! The hard problem a transparent tunnel has to solve is that smoltcp sockets bind to a
//! *specific* endpoint, while we must accept connections to arbitrary destinations. Two
//! mechanisms combine to handle it:
//!
//!   1. `Interface::set_any_ip(true)` lets the interface accept packets that are not
//!      addressed to it, provided some socket matches.
//!   2. We peek every packet before smoltcp sees it. On a SYN to a new 4-tuple we create a
//!      socket already listening on that exact destination, *then* hand the frame over, so
//!      the handshake finds a socket waiting for it.

use std::collections::{HashMap, HashSet};
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::os::unix::io::RawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::thread::JoinHandle;
use std::time::{Duration, Instant as StdInstant};

use smoltcp::iface::{Config as IfaceConfig, Interface, SocketHandle, SocketSet};
use smoltcp::socket::tcp;
use smoltcp::time::Instant;
use smoltcp::wire::{HardwareAddress, IpCidr, IpListenEndpoint};

use crate::ca::CertAuthority;
use crate::config::Config;
use crate::content::ContentFilter;
use crate::dns;
use crate::dns_cache::DnsCache;
use crate::events::{Event, EventLog, Stats};
use crate::filter::{DomainFilter, Verdict};
use crate::jvm::JavaBridge;
use crate::mitm::{self, MitmSession};
use crate::net;
use crate::packet::{self, PROTO_TCP, PROTO_UDP};
use crate::tun::TunDevice;
use crate::wake::Waker;

/// Addresses assigned to the smoltcp side of the link. The Android side of the TUN takes
/// 10.0.0.2/32, so a /24 here makes the peer on-link and no ARP/NDP is needed on a TUN.
const IFACE_V4: Ipv4Addr = Ipv4Addr::new(10, 0, 0, 1);
const IFACE_V6: Ipv6Addr = Ipv6Addr::new(0xfd00, 0, 0, 1, 0, 0, 0, 1);

/// Ring size for each smoltcp socket, in each direction.
///
/// This buffer only spans the app↔tunnel hop, where both ends are in this process and the RTT
/// is microseconds — the bandwidth-delay product it has to cover is tiny. Real buffering for
/// the path that actually has latency lives in the kernel's upstream socket. It was 64 KiB,
/// which cost 128 KiB of zeroed heap per connection and a 64 MiB ceiling at `MAX_CONNECTIONS`.
const TCP_BUFFER: usize = 16 * 1024;
const MAX_CONNECTIONS: usize = 512;
const UDP_SESSION_TTL: Duration = Duration::from_secs(30);
const HANDSHAKE_TTL: Duration = Duration::from_secs(10);

/// How long the loop is willing to sleep when smoltcp reports nothing pending.
///
/// Every real event has an explicit wakeup (a packet on the TUN fd, a readable upstream socket,
/// or the [`Waker`] pipe), so this is a backstop rather than a schedule: if some future producer
/// forgets to wake us, the tunnel recovers within this window instead of hanging. Keep it long
/// — this value *is* the idle battery cost.
const IDLE_CEILING_MS: i32 = 30_000;

/// The ceiling used when the wake pipe could not be created. Without a waker, off-thread
/// changes are only noticed by timing out, so this has to stay short.
const NO_WAKER_CEILING_MS: i32 = 200;

/// Stop asking an upstream socket for more data once this much is queued for the app.
///
/// `POLLIN` used to be requested regardless of the backlog, so a slow app-side reader left the
/// fd permanently readable: `poll` returned immediately every time and the loop became a busy
/// wait while `from_remote` grew without bound.
const READ_HIGH_WATER: usize = 256 * 1024;

/// Cap on the UID→package-name map, which had no bound at all.
const MAX_APP_NAMES: usize = 256;

/// How often the expiry sweeps run. They enforce 10 s and 30 s TTLs, so running them per
/// iteration (which is what happened before) was between 50× and 150× more often than needed.
const SWEEP_INTERVAL: Duration = Duration::from_secs(1);

/// State shared between the tunnel thread and the JNI callers.
pub struct Shared {
    pub config: RwLock<Config>,
    pub filter: RwLock<DomainFilter>,
    pub content: RwLock<ContentFilter>,
    pub stats: Stats,
    pub events: EventLog,
    /// Upstream answers, keyed by name and qtype. Flushed from JNI whenever a rule, list or
    /// config change could alter a verdict — see [`crate::dns_cache`].
    pub dns_cache: Mutex<DnsCache>,
    /// UID → package name, resolved once per UID rather than per connection. Shared handles,
    /// so handing one to an event is a refcount bump rather than a copy.
    pub app_names: Mutex<HashMap<i32, Arc<str>>>,
    /// Set when DoH was requested but the core had to fall back to plaintext UDP. Surfaced in
    /// the UI rather than swallowed — a silent downgrade would misrepresent the guarantee.
    pub doh_degraded: Arc<AtomicBool>,
    pub ca: Option<Arc<CertAuthority>>,
    pub tls_server: Option<Arc<rustls::ServerConfig>>,
    pub tls_client: Option<Arc<rustls::ClientConfig>>,
    /// UIDs observed rejecting our certificate. Populated at runtime so a pinned app is
    /// bypassed automatically instead of being left with no connectivity.
    pub pinned: Mutex<HashSet<i32>>,
}

impl Shared {
    fn new(config: Config) -> Self {
        // The CA is only needed for Layer 2. If it cannot be built the tunnel still runs with
        // DNS filtering, which is the layer that does most of the work anyway.
        let ca = if config.data_dir.is_empty() {
            None
        } else {
            match CertAuthority::load_or_create(std::path::Path::new(&config.data_dir)) {
                Ok(ca) => Some(Arc::new(ca)),
                Err(e) => {
                    log::error!("cannot initialise root CA: {e}; HTTPS filtering disabled");
                    None
                }
            }
        };
        let tls_server = ca.as_ref().map(|ca| mitm::server_config(Arc::clone(ca)));
        let tls_client = ca.as_ref().map(|_| mitm::client_config());

        Self {
            config: RwLock::new(config),
            filter: RwLock::new(DomainFilter::new()),
            content: RwLock::new(ContentFilter::new()),
            stats: Stats::default(),
            events: EventLog::new(2000),
            dns_cache: Mutex::new(DnsCache::new()),
            app_names: Mutex::new(HashMap::new()),
            doh_degraded: Arc::new(AtomicBool::new(false)),
            ca,
            tls_server,
            tls_client,
            pinned: Mutex::new(HashSet::new()),
        }
    }

    pub fn ca_pem(&self) -> String {
        self.ca
            .as_ref()
            .map(|c| c.ca_pem().to_string())
            .unwrap_or_default()
    }
}

pub struct Runtime {
    pub shared: Arc<Shared>,
    /// ABP rule text handed over by JNI but not yet compiled.
    ///
    /// Held here rather than inside `ContentFilter` so the expensive engine build happens once,
    /// at commit, instead of once per list — and so the old engine is not kept alive beside a
    /// half-built new one while lists are still arriving.
    pub staged_content: Mutex<Vec<String>>,
    stop: Arc<AtomicBool>,
    /// `None` only if the pipe could not be created; the loop then falls back to a short poll
    /// ceiling so it still notices `stop` and config changes, just less promptly.
    waker: Option<Arc<Waker>>,
    join: Option<JoinHandle<()>>,
}

impl Runtime {
    pub fn start(tun_fd: RawFd, config: Config, jvm: Arc<JavaBridge>) -> Self {
        let shared = Arc::new(Shared::new(config));
        let stop = Arc::new(AtomicBool::new(false));
        let waker = match Waker::new() {
            Ok(w) => Some(Arc::new(w)),
            Err(e) => {
                log::error!("cannot create wake pipe ({e}); falling back to timed polling");
                None
            }
        };

        let join = {
            let shared = Arc::clone(&shared);
            let stop = Arc::clone(&stop);
            let waker = waker.clone();
            std::thread::Builder::new()
                .name("omnishield-tunnel".into())
                .spawn(move || {
                    if let Err(e) = run_loop(tun_fd, shared, jvm, stop, waker) {
                        log::error!("tunnel loop exited: {e}");
                    }
                    // The descriptor was detached on the Kotlin side, so closing it is ours.
                    unsafe { libc::close(tun_fd) };
                    log::info!("tunnel thread finished");
                })
                .ok()
        };

        Self {
            shared,
            staged_content: Mutex::new(Vec::new()),
            stop,
            waker,
            join,
        }
    }

    /// Interrupts the loop's `poll()` so a change made from JNI takes effect now.
    ///
    /// Without this the loop could sleep for [`IDLE_CEILING_MS`] before noticing, which for a
    /// setting the user just toggled would look like the app ignoring them.
    pub fn wake(&self) {
        if let Some(w) = &self.waker {
            w.wake();
        }
    }

    pub fn stop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        // Order matters: the flag is set first, so the loop sees it the moment the wake lands.
        // Waking without setting the flag would just spin the loop once.
        self.wake();
        if let Some(j) = self.join.take() {
            let _ = j.join();
        }
    }
}

// ---------------------------------------------------------------------------
// Connection state
// ---------------------------------------------------------------------------

// `uid`/`app` are read by the Phase 4 MITM opt-in check and the Phase 5 content filter.
#[allow(dead_code)]
struct TcpConn {
    handle: SocketHandle,
    fd: RawFd,
    remote: SocketAddr,
    connecting: bool,
    /// app → upstream
    to_remote: Vec<u8>,
    /// upstream → app
    from_remote: Vec<u8>,
    uid: i32,
    app: Arc<str>,
    remote_eof: bool,
    created: StdInstant,
    /// Present only for connections opted in to TLS interception.
    mitm: Option<Box<MitmSession>>,
    /// Set once `finish_response` has released the (possibly rewritten) body to the app.
    mitm_finished: bool,
}

/// A DNS query handed to the DoH worker, kept so the reply can be routed back to the app that
/// asked and so the query can be retried over plaintext UDP if DoH fails.
struct PendingDns {
    app_addr: IpAddr,
    app_port: u16,
    orig_dst: IpAddr,
    orig_port: u16,
    query: Vec<u8>,
    tuple: FourTuple,
    endpoints: packet::Endpoints,
    created: StdInstant,
    /// Carried so a plaintext retry can create its session already attributed, rather than
    /// paying for a fresh JNI lookup on a path that has already done one.
    attribution: (i32, Arc<str>),
}

struct UdpSession {
    fd: RawFd,
    /// Where to send the reply — the app's own endpoint.
    app_addr: IpAddr,
    app_port: u16,
    /// The address the app addressed, which must be the source of our reply.
    orig_dst: IpAddr,
    orig_port: u16,
    created: StdInstant,
    /// Whether this session carries DNS. Replies on a DNS session are eligible for the answer
    /// cache; everything else is relayed untouched.
    is_dns: bool,
    /// Attribution for the socket that opened this session, resolved once. `-1` means it was
    /// never resolved, in which case callers fall back to a fresh lookup.
    uid: i32,
    app: Arc<str>,
}

/// Renders `addr:port` for display, bracketing IPv6 per RFC 3986 — otherwise an address like
/// `2001:db8::1` on port 443 renders as `2001:db8::1:443`, which is ambiguous nonsense.
fn format_endpoint(addr: IpAddr, port: u16) -> String {
    match addr {
        IpAddr::V4(v4) => format!("{v4}:{port}"),
        IpAddr::V6(v6) => format!("[{v6}]:{port}"),
    }
}

/// Everything the DNS path needs to reach an upstream resolver.
///
/// Bundled rather than threaded through as loose parameters: `triage` already carries enough
/// arguments, and grouping these keeps the DoH concern in one place.
struct DnsUpstream {
    resolver: Option<crate::doh::DohResolver>,
    pending: HashMap<u64, PendingDns>,
    next_token: u64,
}

impl DnsUpstream {
    fn take_token(&mut self) -> u64 {
        self.next_token = self.next_token.wrapping_add(1);
        self.next_token
    }
}

#[derive(Hash, PartialEq, Eq, Clone, Copy, Debug)]
struct FourTuple {
    src: IpAddr,
    src_port: u16,
    dst: IpAddr,
    dst_port: u16,
}

// ---------------------------------------------------------------------------
// Main loop
// ---------------------------------------------------------------------------

fn run_loop(
    tun_fd: RawFd,
    shared: Arc<Shared>,
    jvm: Arc<JavaBridge>,
    stop: Arc<AtomicBool>,
    waker: Option<Arc<Waker>>,
) -> io::Result<()> {
    let mtu = shared.config.read().unwrap().mtu;
    net::set_nonblocking(tun_fd)?;

    let mut device = TunDevice::new(tun_fd, mtu);
    let mut iface = build_interface(&mut device);
    let mut sockets = SocketSet::new(Vec::new());

    let mut conns: HashMap<SocketHandle, TcpConn> = HashMap::new();
    let mut pending: HashMap<FourTuple, (SocketHandle, StdInstant)> = HashMap::new();
    let mut udp: HashMap<FourTuple, UdpSession> = HashMap::new();

    let mut read_buf = vec![0u8; mtu.max(2048)];
    let started = StdInstant::now();

    // Encrypted upstream DNS, if configured and the endpoint parses. A malformed or
    // hostname-based endpoint degrades to plaintext rather than failing the tunnel.
    let mut dns_up = {
        let cfg = shared.config.read().unwrap();
        let resolver = if cfg.uses_doh() {
            match crate::doh::parse(&cfg.doh_url) {
                Some(endpoint) => {
                    log::info!("upstream DNS over HTTPS: {}", cfg.doh_url);
                    Some(crate::doh::DohResolver::start(
                        endpoint,
                        Arc::clone(&jvm),
                        Arc::clone(&shared.doh_degraded),
                        waker.clone(),
                    ))
                }
                None => {
                    log::warn!("unusable DoH endpoint {}; using plaintext UDP", cfg.doh_url);
                    shared.doh_degraded.store(true, Ordering::Relaxed);
                    None
                }
            }
        } else {
            None
        };
        DnsUpstream {
            resolver,
            pending: HashMap::new(),
            next_token: 0,
        }
    };

    // Reused across iterations rather than rebuilt. These tables change far less often than
    // the loop runs, so allocating three fresh Vecs per pass was pure overhead — and at idle
    // it was the only allocation happening at all.
    let mut fds: Vec<libc::pollfd> = Vec::with_capacity(16);
    let mut conn_keys: Vec<SocketHandle> = Vec::with_capacity(16);
    let mut udp_keys: Vec<FourTuple> = Vec::with_capacity(16);

    let wake_fd = waker.as_ref().map(|w| w.read_fd()).unwrap_or(-1);
    let idle_ceiling = if waker.is_some() {
        IDLE_CEILING_MS
    } else {
        NO_WAKER_CEILING_MS
    };
    let mut last_sweep = StdInstant::now();

    log::info!("tunnel loop running (mtu={mtu}, idle ceiling={idle_ceiling}ms)");

    while !stop.load(Ordering::Relaxed) {
        // --- 1. Wait for something to do -----------------------------------
        fds.clear();
        conn_keys.clear();
        udp_keys.clear();

        fds.push(libc::pollfd {
            fd: tun_fd,
            events: libc::POLLIN,
            revents: 0,
        });
        // A negative fd is ignored by poll(), which is what makes the no-waker fallback work
        // without a second code path.
        fds.push(libc::pollfd {
            fd: wake_fd,
            events: libc::POLLIN,
            revents: 0,
        });

        // Built in one pass over the map: the previous version collected the keys and then
        // indexed the map again for each one, hashing every key twice.
        for (handle, c) in conns.iter() {
            let mut ev = 0i16;
            // Backpressure. Asking for more while the app side is already behind kept the fd
            // permanently readable, which turned the loop into a busy wait and let
            // `from_remote` grow unbounded.
            if !c.remote_eof && c.from_remote.len() < READ_HIGH_WATER {
                ev |= libc::POLLIN;
            }
            if c.connecting || !c.to_remote.is_empty() {
                ev |= libc::POLLOUT;
            }
            conn_keys.push(*handle);
            fds.push(libc::pollfd {
                fd: c.fd,
                events: ev,
                revents: 0,
            });
        }
        for (key, s) in udp.iter() {
            udp_keys.push(*key);
            fds.push(libc::pollfd {
                fd: s.fd,
                events: libc::POLLIN,
                revents: 0,
            });
        }

        // Trust smoltcp. `None` means no socket wants servicing, so there is genuinely nothing
        // to do until an fd becomes readable or someone wakes us — the old code slept 200 ms
        // and re-ran the whole pipeline to rediscover that five times a second.
        let timeout_ms = match iface.poll_delay(smol_now(started), &sockets) {
            Some(d) => (d.total_millis() as i64).clamp(0, idle_ceiling as i64) as i32,
            None => idle_ceiling,
        };
        unsafe { libc::poll(fds.as_mut_ptr(), fds.len() as libc::nfds_t, timeout_ms) };

        // --- 2. Absorb the wake, if that is what woke us --------------------
        if fds[1].revents & libc::POLLIN != 0 {
            if let Some(w) = &waker {
                w.drain();
            }
        }

        // --- 3. Drain the TUN ----------------------------------------------
        if fds[0].revents & libc::POLLIN != 0 {
            for _ in 0..256 {
                let n = unsafe {
                    libc::read(
                        tun_fd,
                        read_buf.as_mut_ptr() as *mut libc::c_void,
                        read_buf.len(),
                    )
                };
                if n <= 0 {
                    break;
                }
                // Borrowed, not copied. Only the TCP branch needs to own the bytes, and it
                // takes its own copy; UDP, DNS and ICMP used to pay for an allocation they
                // then only ever read through a reference.
                triage(
                    &read_buf[..n as usize],
                    &shared,
                    &jvm,
                    &mut device,
                    &mut sockets,
                    &mut conns,
                    &mut pending,
                    &mut udp,
                    &mut dns_up,
                );
            }
        }

        // --- 4. Let smoltcp process what we queued --------------------------
        iface.poll(smol_now(started), &mut device, &mut sockets);

        // --- 5. Promote completed handshakes into real connections ----------
        promote_pending(&shared, &jvm, &mut sockets, &mut conns, &mut pending);

        // --- 6. Shuttle bytes between smoltcp sockets and upstream sockets ---
        for i in 0..conn_keys.len() {
            let revents = fds[2 + i].revents;
            let handle = conn_keys[i];
            pump_connection(handle, revents, &shared, &mut sockets, &mut conns);
        }

        // --- 6b. Encrypted DNS answers --------------------------------------
        drain_doh(&mut dns_up, &shared, &jvm, &mut device, &mut udp);

        // --- 7. Upstream UDP replies ----------------------------------------
        for (i, key) in udp_keys.iter().enumerate() {
            let revents = fds[2 + conn_keys.len() + i].revents;
            if revents & libc::POLLIN != 0 {
                pump_udp(key, &mut udp, &mut device, &shared);
            }
        }

        // --- 8. Turn what step 6 queued into actual packets ------------------
        //
        // `pump_connection` calls `send_slice`, which only fills the socket's ring — the
        // frames are produced by `iface.poll`. Without this second poll the data waited for
        // the *next* iteration, so every upstream burst cost a full extra pass, and
        // `poll_delay` above would have reported "work pending" and refused to sleep.
        iface.poll(smol_now(started), &mut device, &mut sockets);

        // --- 9. Flush anything smoltcp produced -----------------------------
        while let Some(pkt) = device.pop_tx() {
            let mut off = 0usize;
            while off < pkt.len() {
                let n = unsafe {
                    libc::write(
                        tun_fd,
                        pkt[off..].as_ptr() as *const libc::c_void,
                        pkt.len() - off,
                    )
                };
                if n <= 0 {
                    break;
                }
                off += n as usize;
            }
            device.recycle(pkt);
        }

        // --- 10. Reap, on a clock rather than every pass ---------------------
        if last_sweep.elapsed() >= SWEEP_INTERVAL {
            last_sweep = StdInstant::now();
            reap(&mut sockets, &mut conns, &mut pending, &mut udp);
            expire_pending_dns(&mut dns_up);
        }
    }

    for (_, c) in conns.drain() {
        unsafe { libc::close(c.fd) };
    }
    for (_, s) in udp.drain() {
        unsafe { libc::close(s.fd) };
    }
    Ok(())
}

fn smol_now(started: StdInstant) -> Instant {
    Instant::from_millis(started.elapsed().as_millis() as i64)
}

fn build_interface(device: &mut TunDevice) -> Interface {
    let config = IfaceConfig::new(HardwareAddress::Ip);
    let mut iface = Interface::new(config, device, Instant::from_millis(0));

    // Accept packets addressed to hosts that are not us — the whole point of a transparent
    // tunnel. Without this smoltcp drops everything whose destination is not a local address.
    iface.set_any_ip(true);

    iface.update_ip_addrs(|addrs| {
        let _ = addrs.push(IpCidr::new(IFACE_V4.into(), 24));
        let _ = addrs.push(IpCidr::new(IFACE_V6.into(), 64));
    });
    let _ = iface.routes_mut().add_default_ipv4_route(IFACE_V4);
    let _ = iface.routes_mut().add_default_ipv6_route(IFACE_V6);
    iface
}

// ---------------------------------------------------------------------------
// Packet triage
// ---------------------------------------------------------------------------

#[allow(clippy::too_many_arguments)]
fn triage(
    pkt: &[u8],
    shared: &Arc<Shared>,
    jvm: &Arc<JavaBridge>,
    device: &mut TunDevice,
    sockets: &mut SocketSet<'static>,
    conns: &mut HashMap<SocketHandle, TcpConn>,
    pending: &mut HashMap<FourTuple, (SocketHandle, StdInstant)>,
    udp: &mut HashMap<FourTuple, UdpSession>,
    dns_up: &mut DnsUpstream,
) {
    let info = match packet::parse(pkt) {
        Some(i) => i,
        // Unparseable is dropped, never forwarded — passing a packet we do not understand
        // would be a filtering bypass.
        None => return,
    };

    let cfg = shared.config.read().unwrap();
    let e = info.endpoints;
    let tuple = FourTuple {
        src: e.src,
        src_port: e.src_port,
        dst: e.dst,
        dst_port: e.dst_port,
    };

    match info.protocol {
        PROTO_UDP => {
            // DNS to our sentinel address. Pre-parsed when the config was installed; this
            // used to re-parse the address from a string on every UDP datagram.
            if e.dst_port == 53 && Some(e.dst) == cfg.sentinel_addr() {
                drop(cfg);
                handle_dns(&pkt, &info, shared, jvm, device, udp, dns_up);
                return;
            }
            // QUIC cannot be intercepted; dropping it forces browsers back to TLS 1.3 over
            // TCP, which we can filter.
            if e.dst_port == 443 && cfg.block_quic {
                return;
            }
            // Plain DNS aimed at a hard-coded resolver would bypass the sentinel entirely.
            if e.dst_port == 53 {
                drop(cfg);
                handle_dns(&pkt, &info, shared, jvm, device, udp, dns_up);
                return;
            }
            drop(cfg);
            forward_udp(&pkt, &info, tuple, jvm, udp);
        }

        PROTO_TCP => {
            if cfg.block_dot && e.dst_port == 853 {
                return; // DNS-over-TLS would bypass Phase 3
            }
            drop(cfg);

            if info.is_syn() && !pending.contains_key(&tuple) {
                if conns.len() + pending.len() >= MAX_CONNECTIONS {
                    log::warn!("connection table full; dropping SYN to {}", e.dst);
                    return;
                }
                // Created *before* smoltcp sees the SYN, and deliberately before the firewall
                // check in `promote_pending`. Refusing earlier would mean dropping the SYN,
                // which the app experiences as a connect timeout rather than the immediate
                // reset it gets today — the buffers are sized (see TCP_BUFFER) so that paying
                // for them briefly is cheaper than that regression.
                let mut sock = tcp::Socket::new(
                    tcp::SocketBuffer::new(vec![0u8; TCP_BUFFER]),
                    tcp::SocketBuffer::new(vec![0u8; TCP_BUFFER]),
                );
                sock.set_nagle_enabled(false);
                let listen = IpListenEndpoint {
                    addr: Some(e.dst.into()),
                    port: e.dst_port,
                };
                if sock.listen(listen).is_err() {
                    return;
                }
                let handle = sockets.add(sock);
                pending.insert(tuple, (handle, StdInstant::now()));
            }
            // The one path that genuinely needs to own the bytes: smoltcp consumes the frame
            // asynchronously, after this function has returned. The copy comes from the
            // device's buffer pool rather than a fresh allocation.
            device.push_rx_from(pkt);
        }

        packet::PROTO_ICMP | packet::PROTO_ICMPV6 => {
            // Dropped, deliberately.
            //
            // Handing ICMP to smoltcp while `any_ip` is on makes it answer echo requests for
            // *every* destination, so `ping 192.0.2.1` would succeed against a host that does
            // not exist. That is worse than not working: it turns ping into a tool that
            // silently reports false success. Genuinely proxying ICMP needs a raw socket,
            // which is root-only, so the honest behaviour is to drop it.
            drop(cfg);
        }

        _ => {
            drop(cfg);
        }
    }
}

/// Resolves the owning app of a connection, caching package names per UID.
///
/// Both JNI calls in here are expensive — each attaches the calling thread to the JVM and makes
/// a reflective call — so the callers are responsible for not invoking this per packet. See
/// [`attribute_udp`] for the DNS path, which used to do exactly that.
fn attribute(
    shared: &Arc<Shared>,
    jvm: &Arc<JavaBridge>,
    protocol: i32,
    e: &packet::Endpoints,
) -> (i32, Arc<str>) {
    let uid = jvm.owner_uid(
        protocol,
        &e.src.to_string(),
        e.src_port as i32,
        &e.dst.to_string(),
        e.dst_port as i32,
    );
    if uid < 0 {
        return (-1, Arc::from(""));
    }
    let mut cache = shared.app_names.lock().unwrap_or_else(|p| p.into_inner());
    // Bounded: a long-lived tunnel on a busy device would otherwise accumulate an entry per
    // UID ever seen and never release one. Wholesale clearing is fine — the map is a cache,
    // and repopulating it costs one JNI call per app still active.
    if cache.len() >= MAX_APP_NAMES {
        cache.clear();
    }
    let name = cache
        .entry(uid)
        .or_insert_with(|| Arc::from(jvm.package_for_uid(uid).as_str()))
        .clone();
    (uid, name)
}

/// Attribution for the DNS path, reusing a session's answer instead of asking again.
///
/// `jvm.rs` documents the JVM calls as being "per connection, never per packet", which was not
/// true of DNS: every query ran a full `owner_uid` round trip — two `IpAddr::to_string()`
/// allocations, a JVM thread attach and a reflective call — for a 4-tuple whose owning UID
/// cannot change for the life of the session.
///
/// The saving is real but bounded: a resolver that opens a fresh socket per lookup produces a
/// new 4-tuple every time and gets no reuse. It helps where a socket is reused, and costs
/// nothing where it is not.
fn attribute_udp(
    shared: &Arc<Shared>,
    jvm: &Arc<JavaBridge>,
    e: &packet::Endpoints,
    tuple: &FourTuple,
    udp: &HashMap<FourTuple, UdpSession>,
) -> (i32, Arc<str>) {
    if let Some(s) = udp.get(tuple) {
        if s.uid >= 0 {
            return (s.uid, Arc::clone(&s.app));
        }
    }
    attribute(shared, jvm, PROTO_UDP as i32, e)
}

// ---------------------------------------------------------------------------
// DNS
// ---------------------------------------------------------------------------

fn handle_dns(
    pkt: &[u8],
    info: &packet::PacketInfo,
    shared: &Arc<Shared>,
    jvm: &Arc<JavaBridge>,
    device: &mut TunDevice,
    udp: &mut HashMap<FourTuple, UdpSession>,
    dns_up: &mut DnsUpstream,
) {
    let payload = &pkt[info.payload_offset..];
    let e = info.endpoints;
    shared.stats.dns_total.fetch_add(1, Ordering::Relaxed);

    let query = match dns::parse_query(payload) {
        Some(q) => q,
        None => {
            // Not a query we understand — relay it rather than break resolution.
            let tuple = FourTuple {
                src: e.src,
                src_port: e.src_port,
                dst: e.dst,
                dst_port: e.dst_port,
            };
            forward_dns_upstream(
                pkt,
                info,
                shared,
                jvm,
                udp,
                tuple,
                dns_up,
                (-1, Arc::from("")),
            );
            return;
        }
    };

    let filtering = shared.config.read().unwrap().filtering_enabled;
    let verdict = if filtering {
        shared.filter.read().unwrap().lookup(&query.name)
    } else {
        Verdict::Allow
    };

    let tuple = FourTuple {
        src: e.src,
        src_port: e.src_port,
        dst: e.dst,
        dst_port: e.dst_port,
    };

    if let Verdict::Block(rule) = verdict {
        let (uid, app) = attribute_udp(shared, jvm, &e, &tuple, udp);
        shared.stats.dns_blocked.fetch_add(1, Ordering::Relaxed);
        shared
            .events
            .push(Event::new("dns", query.name.clone(), uid, app, true, rule));

        let reply = dns::nxdomain_response(payload, &query);
        // The reply must appear to come from the address the app queried.
        if let Some(frame) = net::build_udp_packet(e.dst, e.dst_port, e.src, e.src_port, &reply) {
            device.inject_tx(frame);
        }
        return;
    }

    let (uid, app) = attribute_udp(shared, jvm, &e, &tuple, udp);
    let attribution = (uid, Arc::clone(&app));

    // A hit still emits its event below, so the live log, the daily rollups and the DNS
    // counters are identical whether the answer came from here or from upstream.
    let cached = {
        let mut cache = shared.dns_cache.lock().unwrap_or_else(|p| p.into_inner());
        let hit = cache.get(payload, &query.name, query.qtype, StdInstant::now());
        if hit.is_none() {
            cache.record_miss();
        }
        hit
    };

    shared
        .events
        .push(Event::new("dns", query.name, uid, app, false, ""));

    if let Some(reply) = cached {
        if let Some(frame) = net::build_udp_packet(e.dst, e.dst_port, e.src, e.src_port, &reply) {
            device.inject_tx(frame);
            return;
        }
        // Falling through means the frame could not be built; ask upstream rather than
        // silently dropping the query.
    }

    forward_dns_upstream(pkt, info, shared, jvm, udp, tuple, dns_up, attribution);
}

fn forward_dns_upstream(
    pkt: &[u8],
    info: &packet::PacketInfo,
    shared: &Arc<Shared>,
    jvm: &Arc<JavaBridge>,
    udp: &mut HashMap<FourTuple, UdpSession>,
    tuple: FourTuple,
    dns_up: &mut DnsUpstream,
    attribution: (i32, Arc<str>),
) {
    let payload = &pkt[info.payload_offset..];
    let e = info.endpoints;

    // Encrypted first when it is configured. The query is retained so it can be replayed over
    // plaintext if the worker reports failure — the user still gets resolution, and the
    // degraded flag tells them it is no longer private.
    if dns_up.resolver.is_some() {
        // Token taken before borrowing the resolver: `take_token` needs `&mut self`, and
        // holding a reference to the resolver across it would overlap the borrows.
        let token = dns_up.take_token();
        let submitted = dns_up
            .resolver
            .as_ref()
            .is_some_and(|r| r.submit(token, payload.to_vec()));
        if submitted {
            dns_up.pending.insert(
                token,
                PendingDns {
                    app_addr: e.src,
                    app_port: e.src_port,
                    orig_dst: e.dst,
                    orig_port: e.dst_port,
                    query: payload.to_vec(),
                    tuple,
                    endpoints: e,
                    created: StdInstant::now(),
                    attribution: attribution.clone(),
                },
            );
            return;
        }
        log::warn!("DoH worker unavailable; using plaintext for this query");
        shared.doh_degraded.store(true, Ordering::Relaxed);
    }

    send_dns_plaintext(payload, tuple, e, shared, jvm, udp, attribution);
}

/// Sends a DNS query as plaintext UDP to the configured resolver.
fn send_dns_plaintext(
    payload: &[u8],
    tuple: FourTuple,
    e: packet::Endpoints,
    shared: &Arc<Shared>,
    jvm: &Arc<JavaBridge>,
    udp: &mut HashMap<FourTuple, UdpSession>,
    attribution: (i32, Arc<str>),
) {
    // Pre-parsed at config install time rather than re-parsed per query.
    let upstream: IpAddr = shared.config.read().unwrap().upstream_addr();

    let fd = match ensure_udp_session(&tuple, udp, jvm, upstream, e, true, attribution) {
        Some(fd) => fd,
        None => return,
    };
    let (sa, len) = net::sockaddr(&upstream, 53);
    unsafe {
        libc::sendto(
            fd,
            payload.as_ptr() as *const libc::c_void,
            payload.len(),
            0,
            &sa as *const _ as *const libc::sockaddr,
            len,
        )
    };
}

/// Collects finished DoH exchanges and returns the answers to the apps that asked.
fn drain_doh(
    dns_up: &mut DnsUpstream,
    shared: &Arc<Shared>,
    jvm: &Arc<JavaBridge>,
    device: &mut TunDevice,
    udp: &mut HashMap<FourTuple, UdpSession>,
) {
    let answers = match dns_up.resolver.as_ref() {
        Some(r) => r.poll(),
        None => return,
    };

    for answer in answers {
        let Some(p) = dns_up.pending.remove(&answer.token) else {
            continue;
        };
        match answer.answer {
            Some(bytes) => {
                shared
                    .dns_cache
                    .lock()
                    .unwrap_or_else(|p| p.into_inner())
                    .put(&bytes, StdInstant::now());
                // The reply must appear to come from the address the app queried.
                if let Some(frame) =
                    net::build_udp_packet(p.orig_dst, p.orig_port, p.app_addr, p.app_port, &bytes)
                {
                    device.inject_tx(frame);
                }
            }
            None => send_dns_plaintext(
                &p.query,
                p.tuple,
                p.endpoints,
                shared,
                jvm,
                udp,
                p.attribution.clone(),
            ),
        }
    }
}

/// Drops DoH queries whose answer never came back.
///
/// A worker that dies mid-flight would otherwise leak these forever. Called from the sweep
/// rather than per iteration — it enforces a 10 s timeout, so running it on every pass meant a
/// clock read per outstanding query, hundreds of times more often than the deadline needed.
fn expire_pending_dns(dns_up: &mut DnsUpstream) {
    if dns_up.pending.is_empty() {
        return;
    }
    dns_up
        .pending
        .retain(|_, p| p.created.elapsed() < Duration::from_secs(10));
}

fn ensure_udp_session(
    tuple: &FourTuple,
    udp: &mut HashMap<FourTuple, UdpSession>,
    jvm: &Arc<JavaBridge>,
    upstream: IpAddr,
    e: packet::Endpoints,
    is_dns: bool,
    attribution: (i32, Arc<str>),
) -> Option<RawFd> {
    if let Some(s) = udp.get(tuple) {
        return Some(s.fd);
    }
    let fd = net::new_socket(&upstream, libc::SOCK_DGRAM).ok()?;
    if !jvm.protect(fd) {
        log::warn!("protect() failed for udp fd {fd}; closing to avoid a routing loop");
        unsafe { libc::close(fd) };
        return None;
    }
    udp.insert(
        *tuple,
        UdpSession {
            fd,
            app_addr: e.src,
            app_port: e.src_port,
            orig_dst: e.dst,
            orig_port: e.dst_port,
            created: StdInstant::now(),
            is_dns,
            uid: attribution.0,
            app: attribution.1,
        },
    );
    Some(fd)
}

fn forward_udp(
    pkt: &[u8],
    info: &packet::PacketInfo,
    tuple: FourTuple,
    jvm: &Arc<JavaBridge>,
    udp: &mut HashMap<FourTuple, UdpSession>,
) {
    let e = info.endpoints;
    let fd = match ensure_udp_session(&tuple, udp, jvm, e.dst, e, false, (-1, Arc::from(""))) {
        Some(fd) => fd,
        None => return,
    };
    let payload = &pkt[info.payload_offset..];
    let (sa, len) = net::sockaddr(&e.dst, e.dst_port);
    unsafe {
        libc::sendto(
            fd,
            payload.as_ptr() as *const libc::c_void,
            payload.len(),
            0,
            &sa as *const _ as *const libc::sockaddr,
            len,
        )
    };
}

fn pump_udp(
    key: &FourTuple,
    udp: &mut HashMap<FourTuple, UdpSession>,
    device: &mut TunDevice,
    shared: &Arc<Shared>,
) {
    let session = match udp.get(key) {
        Some(s) => s,
        None => return,
    };
    let mut buf = [0u8; 4096];
    loop {
        let n = unsafe {
            libc::recv(
                session.fd,
                buf.as_mut_ptr() as *mut libc::c_void,
                buf.len(),
                0,
            )
        };
        if n <= 0 {
            break;
        }
        let reply = &buf[..n as usize];
        // Cache before relaying, so the very next A/AAAA/HTTPS lookup for the same name is
        // answered locally instead of costing another round trip and another radio wakeup.
        if session.is_dns {
            shared
                .dns_cache
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .put(reply, StdInstant::now());
        }
        if let Some(frame) = net::build_udp_packet(
            session.orig_dst,
            session.orig_port,
            session.app_addr,
            session.app_port,
            reply,
        ) {
            device.inject_tx(frame);
        }
    }
}

// ---------------------------------------------------------------------------
// TCP
// ---------------------------------------------------------------------------

fn promote_pending(
    shared: &Arc<Shared>,
    jvm: &Arc<JavaBridge>,
    sockets: &mut SocketSet<'static>,
    conns: &mut HashMap<SocketHandle, TcpConn>,
    pending: &mut HashMap<FourTuple, (SocketHandle, StdInstant)>,
) {
    let ready: Vec<(FourTuple, SocketHandle)> = pending
        .iter()
        .filter(|(_, (h, _))| {
            let s = sockets.get::<tcp::Socket>(*h);
            s.is_active() && s.may_send()
        })
        .map(|(t, (h, _))| (*t, *h))
        .collect();

    for (tuple, handle) in ready {
        pending.remove(&tuple);

        let e = packet::Endpoints {
            src: tuple.src,
            dst: tuple.dst,
            src_port: tuple.src_port,
            dst_port: tuple.dst_port,
        };
        let (uid, app) = attribute(shared, jvm, PROTO_TCP as i32, &e);

        shared.stats.conns_total.fetch_add(1, Ordering::Relaxed);

        // Phase 6 firewall: refuse the connection outright for a blocked app.
        let blocked = {
            let cfg = shared.config.read().unwrap();
            uid >= 0 && cfg.blocked_uids.contains(&uid)
        };
        if blocked {
            shared.stats.conns_blocked.fetch_add(1, Ordering::Relaxed);
            shared.events.push(Event::new(
                "tcp",
                format_endpoint(tuple.dst, tuple.dst_port),
                uid,
                app,
                true,
                "firewall",
            ));
            sockets.get_mut::<tcp::Socket>(handle).abort();
            continue;
        }

        // Layer 2 opt-in. Bypass is the default: a UID must be explicitly listed, MITM must
        // be enabled, and the app must not have already been seen rejecting our certificate.
        let mitm = {
            let cfg = shared.config.read().unwrap();
            let pinned = shared.pinned.lock().unwrap_or_else(|p| p.into_inner());
            let eligible = cfg.mitm_enabled
                && tuple.dst_port == 443
                && uid >= 0
                && cfg.mitm_uids.contains(&uid)
                && !pinned.contains(&uid);
            if eligible {
                match (&shared.tls_server, &shared.tls_client) {
                    (Some(s), Some(c)) => {
                        MitmSession::new(Arc::clone(s), Arc::clone(c)).map(Box::new)
                    }
                    _ => None,
                }
            } else {
                None
            }
        };

        match dial(&tuple, jvm) {
            Some(fd) => {
                shared.events.push(Event::new(
                    if mitm.is_some() { "tls" } else { "tcp" },
                    format_endpoint(tuple.dst, tuple.dst_port),
                    uid,
                    app.clone(),
                    false,
                    "",
                ));
                conns.insert(
                    handle,
                    TcpConn {
                        handle,
                        fd,
                        remote: SocketAddr::new(tuple.dst, tuple.dst_port),
                        connecting: true,
                        to_remote: Vec::new(),
                        from_remote: Vec::new(),
                        uid,
                        app,
                        remote_eof: false,
                        created: StdInstant::now(),
                        mitm,
                        mitm_finished: false,
                    },
                );
            }
            None => {
                sockets.get_mut::<tcp::Socket>(handle).abort();
            }
        }
    }
}

/// Opens a protected, non-blocking upstream socket and starts connecting.
fn dial(tuple: &FourTuple, jvm: &Arc<JavaBridge>) -> Option<RawFd> {
    let fd = net::new_socket(&tuple.dst, libc::SOCK_STREAM).ok()?;
    if !jvm.protect(fd) {
        log::warn!("protect() failed for tcp fd {fd}; closing to avoid a routing loop");
        unsafe { libc::close(fd) };
        return None;
    }
    let (sa, len) = net::sockaddr(&tuple.dst, tuple.dst_port);
    let rc = unsafe { libc::connect(fd, &sa as *const _ as *const libc::sockaddr, len) };
    if rc < 0 {
        let err = io::Error::last_os_error();
        if err.raw_os_error() != Some(libc::EINPROGRESS) {
            log::debug!("connect to {}:{} failed: {err}", tuple.dst, tuple.dst_port);
            unsafe { libc::close(fd) };
            return None;
        }
    }
    Some(fd)
}

fn pump_connection(
    handle: SocketHandle,
    revents: i16,
    shared: &Arc<Shared>,
    sockets: &mut SocketSet<'static>,
    conns: &mut HashMap<SocketHandle, TcpConn>,
) {
    let conn = match conns.get_mut(&handle) {
        Some(c) => c,
        None => return,
    };

    if conn.connecting && revents & (libc::POLLOUT | libc::POLLERR | libc::POLLHUP) != 0 {
        let err = net::socket_error(conn.fd);
        if err != 0 {
            log::debug!("upstream connect to {} failed: errno {err}", conn.remote);
            sockets.get_mut::<tcp::Socket>(handle).abort();
            conn.remote_eof = true;
            return;
        }
        conn.connecting = false;
    }

    // Read whatever the origin sent, regardless of mode.
    let mut incoming = Vec::new();
    if !conn.connecting && revents & libc::POLLIN != 0 {
        let mut buf = [0u8; 32 * 1024];
        loop {
            let n =
                unsafe { libc::read(conn.fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
            if n > 0 {
                incoming.extend_from_slice(&buf[..n as usize]);
                if incoming.len() > 512 * 1024 {
                    break;
                }
            } else if n == 0 {
                conn.remote_eof = true;
                break;
            } else {
                break; // EWOULDBLOCK
            }
        }
    }

    if conn.mitm.is_some() {
        pump_mitm(handle, conn, incoming, shared, sockets);
    } else {
        pump_relay(handle, conn, incoming, sockets);
    }

    // Flush whatever either path queued toward the origin.
    if !conn.connecting && !conn.to_remote.is_empty() {
        let n = unsafe {
            libc::write(
                conn.fd,
                conn.to_remote.as_ptr() as *const libc::c_void,
                conn.to_remote.len(),
            )
        };
        if n > 0 {
            conn.to_remote.drain(..n as usize);
        }
    }

    let socket = sockets.get_mut::<tcp::Socket>(handle);
    if !conn.from_remote.is_empty() && socket.can_send() {
        if let Ok(sent) = socket.send_slice(&conn.from_remote) {
            conn.from_remote.drain(..sent);
        }
    }
    // Half-close once everything has been relayed.
    if conn.remote_eof && conn.from_remote.is_empty() && socket.may_send() {
        socket.close();
    }
}

/// Plain byte relay — the default for every connection not opted in to interception.
fn pump_relay(
    handle: SocketHandle,
    conn: &mut TcpConn,
    incoming: Vec<u8>,
    sockets: &mut SocketSet<'static>,
) {
    let socket = sockets.get_mut::<tcp::Socket>(handle);
    if socket.can_recv() {
        let _ = socket.recv(|data| {
            conn.to_remote.extend_from_slice(data);
            (data.len(), ())
        });
    }
    conn.from_remote.extend_from_slice(&incoming);
}

/// Drives both TLS state machines and the content filter between them.
fn pump_mitm(
    handle: SocketHandle,
    conn: &mut TcpConn,
    incoming: Vec<u8>,
    shared: &Arc<Shared>,
    sockets: &mut SocketSet<'static>,
) {
    let socket = sockets.get_mut::<tcp::Socket>(handle);
    let content = match shared.content.read() {
        Ok(c) => c,
        Err(p) => p.into_inner(),
    };
    let session = match conn.mitm.as_mut() {
        Some(m) => m,
        None => return,
    };

    // app ciphertext → server side
    if socket.can_recv() {
        let _ = socket.recv(|data| {
            session.app_in(data);
            (data.len(), ())
        });
    }

    // The pinning escape hatch. An app that refuses our certificate is remembered so every
    // later connection bypasses interception; leaving it broken would be far worse than
    // losing Layer 2 coverage for that app.
    if session.handshake_rejected {
        let uid = conn.uid;
        if uid >= 0 {
            shared
                .pinned
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .insert(uid);
        }
        shared.events.push(Event::new(
            "tls",
            session
                .sni
                .clone()
                .unwrap_or_else(|| conn.remote.to_string()),
            uid,
            conn.app.clone(),
            false,
            "pinned — bypassing",
        ));
        socket.abort();
        conn.remote_eof = true;
        return;
    }

    if !incoming.is_empty() {
        session.origin_in(&incoming);
    }
    if session.needs_upstream() {
        session.start_upstream();
    }
    session.pump(&content);

    if let Some(url) = session.blocked_url.take() {
        shared.stats.conns_blocked.fetch_add(1, Ordering::Relaxed);
        shared.events.push(Event::new(
            "http",
            url,
            conn.uid,
            conn.app.clone(),
            true,
            "content rule",
        ));
    }

    // `Connection: close` is forced on the request, so origin EOF delimits the body exactly —
    // this is where a rewritten response gets released.
    if conn.remote_eof && !conn.mitm_finished {
        conn.mitm_finished = true;
        if session.finish_response(&content) {
            shared.stats.bytes_saved.fetch_add(1, Ordering::Relaxed);
            shared.events.push(Event::new(
                "http",
                session.sni.clone().unwrap_or_default(),
                conn.uid,
                conn.app.clone(),
                false,
                "cosmetic filter applied",
            ));
        }
    }

    let to_origin = session.origin_out();
    if !to_origin.is_empty() {
        conn.to_remote.extend_from_slice(&to_origin);
    }
    let to_app = session.app_out();
    if !to_app.is_empty() {
        conn.from_remote.extend_from_slice(&to_app);
    }
}

fn reap(
    sockets: &mut SocketSet<'static>,
    conns: &mut HashMap<SocketHandle, TcpConn>,
    pending: &mut HashMap<FourTuple, (SocketHandle, StdInstant)>,
    udp: &mut HashMap<FourTuple, UdpSession>,
) {
    let dead: Vec<SocketHandle> = conns
        .iter()
        .filter(|(h, c)| {
            let s = sockets.get::<tcp::Socket>(**h);
            // Never reap while bytes are still queued toward the app — an intercepted
            // connection writes its whole rewritten response *after* the origin EOF.
            c.from_remote.is_empty() && (!s.is_open() || (c.remote_eof && !s.is_active()))
        })
        .map(|(h, _)| *h)
        .collect();
    for h in dead {
        if let Some(c) = conns.remove(&h) {
            unsafe { libc::close(c.fd) };
        }
        sockets.remove(h);
    }

    // Handshakes that never completed would otherwise leak a socket each.
    let stale: Vec<FourTuple> = pending
        .iter()
        .filter(|(_, (h, t))| {
            t.elapsed() > HANDSHAKE_TTL && !sockets.get::<tcp::Socket>(*h).is_active()
        })
        .map(|(k, _)| *k)
        .collect();
    for k in stale {
        if let Some((h, _)) = pending.remove(&k) {
            sockets.remove(h);
        }
    }

    let expired: Vec<FourTuple> = udp
        .iter()
        .filter(|(_, s)| s.created.elapsed() > UDP_SESSION_TTL)
        .map(|(k, _)| *k)
        .collect();
    for k in expired {
        if let Some(s) = udp.remove(&k) {
            unsafe { libc::close(s.fd) };
        }
    }
}
