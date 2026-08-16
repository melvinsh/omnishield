//! smoltcp `Device` implementation over the TUN file descriptor.
//!
//! The device itself performs no I/O. The runtime loop reads packets off the descriptor and
//! pushes them into `rx`, then drains `tx` back to the descriptor after polling. Keeping
//! syscalls out of the token implementations means the whole stack can be driven by one
//! `poll()` over the TUN fd plus every upstream socket, instead of blocking inside smoltcp.
//!
//! Buffers are recycled through a shared pool. Every packet in either direction used to be a
//! fresh `Vec` — on the transmit side a `vec![0u8; len]`, which also memset bytes smoltcp was
//! about to overwrite. At a few thousand packets a second that is the single hottest
//! allocation site in the process, and packet buffers are all roughly one MTU, so they reuse
//! almost perfectly.

use std::cell::RefCell;
use std::collections::VecDeque;
use std::os::unix::io::RawFd;
use std::rc::Rc;

use smoltcp::phy::{Checksum, ChecksumCapabilities, Device, DeviceCapabilities, Medium};
use smoltcp::time::Instant;

/// Cap on either direction's backlog.
///
/// Both queues were unbounded. `rx` is fed by the TUN drain and `tx` by smoltcp, and neither
/// had any check — a stalled writer could grow them until the process died. IP is a lossy
/// medium and TCP recovers from a drop, so bounding them is both safe and honest.
const MAX_QUEUED: usize = 512;

/// Cap on retained spare buffers, so a burst does not permanently hold its peak footprint.
const MAX_POOLED: usize = 64;

type Pool = Rc<RefCell<Vec<Vec<u8>>>>;

pub struct TunDevice {
    /// Kept for diagnostics; the runtime loop reads and writes the descriptor directly.
    #[allow(dead_code)]
    pub fd: RawFd,
    mtu: usize,
    rx: VecDeque<Vec<u8>>,
    tx: Rc<RefCell<VecDeque<Vec<u8>>>>,
    pool: Pool,
    dropped: u64,
}

fn take(pool: &Pool) -> Vec<u8> {
    pool.borrow_mut().pop().unwrap_or_default()
}

fn give(pool: &Pool, mut buf: Vec<u8>) {
    let mut p = pool.borrow_mut();
    if p.len() < MAX_POOLED {
        buf.clear();
        p.push(buf);
    }
}

impl TunDevice {
    pub fn new(fd: RawFd, mtu: usize) -> Self {
        Self {
            fd,
            mtu,
            rx: VecDeque::new(),
            tx: Rc::new(RefCell::new(VecDeque::new())),
            pool: Rc::new(RefCell::new(Vec::new())),
            dropped: 0,
        }
    }

    /// Queues a copy of a packet read off the TUN, reusing a pooled buffer for the copy.
    pub fn push_rx_from(&mut self, packet: &[u8]) {
        if self.rx.len() >= MAX_QUEUED {
            self.dropped += 1;
            return;
        }
        let mut buf = take(&self.pool);
        buf.extend_from_slice(packet);
        self.rx.push_back(buf);
    }

    /// Removes the next packet smoltcp wants written to the TUN.
    pub fn pop_tx(&mut self) -> Option<Vec<u8>> {
        self.tx.borrow_mut().pop_front()
    }

    /// Returns a buffer obtained from [`pop_tx`] once it has been written to the descriptor.
    pub fn recycle(&mut self, buf: Vec<u8>) {
        give(&self.pool, buf);
    }

    #[allow(dead_code)]
    pub fn has_tx(&self) -> bool {
        !self.tx.borrow().is_empty()
    }

    /// Packets dropped because a queue was at its cap. Surfaced for diagnostics rather than
    /// hidden — a nonzero value here explains stalls that would otherwise look inexplicable.
    #[allow(dead_code)]
    pub fn dropped(&self) -> u64 {
        self.dropped
    }

    /// Injects a fully-formed packet toward the device (used for synthesized DNS replies,
    /// which bypass smoltcp entirely).
    pub fn inject_tx(&mut self, packet: Vec<u8>) {
        let mut tx = self.tx.borrow_mut();
        if tx.len() >= MAX_QUEUED {
            self.dropped += 1;
            return;
        }
        tx.push_back(packet);
    }
}

impl Device for TunDevice {
    type RxToken<'a> = TunRxToken;
    type TxToken<'a> = TunTxToken;

    fn receive(&mut self, _timestamp: Instant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let buffer = self.rx.pop_front()?;
        Some((
            TunRxToken {
                buffer,
                pool: Rc::clone(&self.pool),
            },
            TunTxToken {
                tx: Rc::clone(&self.tx),
                pool: Rc::clone(&self.pool),
            },
        ))
    }

    fn transmit(&mut self, _timestamp: Instant) -> Option<Self::TxToken<'_>> {
        Some(TunTxToken {
            tx: Rc::clone(&self.tx),
            pool: Rc::clone(&self.pool),
        })
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip; // TUN carries bare IP packets, no link layer
        caps.max_transmission_unit = self.mtu;

        // The kernel does not checksum anything on a TUN, so smoltcp must do all of it.
        let mut checksum = ChecksumCapabilities::default();
        checksum.ipv4 = Checksum::Both;
        checksum.tcp = Checksum::Both;
        checksum.udp = Checksum::Both;
        checksum.icmpv4 = Checksum::Both;
        checksum.icmpv6 = Checksum::Both;
        caps.checksum = checksum;
        caps
    }
}

pub struct TunRxToken {
    buffer: Vec<u8>,
    pool: Pool,
}

impl smoltcp::phy::RxToken for TunRxToken {
    fn consume<R, F>(self, f: F) -> R
    where
        F: FnOnce(&[u8]) -> R,
    {
        let result = f(&self.buffer);
        // smoltcp has copied out whatever it needed; the buffer goes back for the next packet.
        give(&self.pool, self.buffer);
        result
    }
}

pub struct TunTxToken {
    tx: Rc<RefCell<VecDeque<Vec<u8>>>>,
    pool: Pool,
}

impl smoltcp::phy::TxToken for TunTxToken {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        // A recycled buffer, resized rather than freshly zeroed. `f` writes the whole frame,
        // so the previous contents are irrelevant.
        let mut buffer = take(&self.pool);
        buffer.resize(len, 0);
        let result = f(&mut buffer);
        let mut tx = self.tx.borrow_mut();
        if tx.len() < MAX_QUEUED {
            tx.push_back(buffer);
        } else {
            drop(tx);
            give(&self.pool, buffer);
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use smoltcp::phy::{RxToken, TxToken};

    fn dev() -> TunDevice {
        TunDevice::new(-1, 1500)
    }

    #[test]
    fn rx_round_trips_a_packet() {
        let mut d = dev();
        d.push_rx_from(&[1, 2, 3, 4]);
        let (rx, _tx) = d.receive(Instant::from_millis(0)).unwrap();
        let seen = rx.consume(|b| b.to_vec());
        assert_eq!(seen, vec![1, 2, 3, 4]);
    }

    #[test]
    fn consumed_rx_buffer_returns_to_the_pool() {
        let mut d = dev();
        d.push_rx_from(&[9; 100]);
        assert_eq!(d.pool.borrow().len(), 0, "buffer is in flight, not pooled");
        let (rx, _tx) = d.receive(Instant::from_millis(0)).unwrap();
        rx.consume(|_| ());
        assert_eq!(d.pool.borrow().len(), 1, "consuming must recycle");
    }

    #[test]
    fn transmit_reuses_a_pooled_buffer_rather_than_allocating() {
        let mut d = dev();
        // Seed the pool with a distinctively large buffer and remember where it lives.
        let mut seed = Vec::with_capacity(4096);
        seed.extend_from_slice(&[7u8; 4096]);
        let addr = seed.as_ptr();
        d.recycle(seed);

        let tok = d.transmit(Instant::from_millis(0)).unwrap();
        tok.consume(64, |buf| buf.fill(0xAB));
        let out = d.pop_tx().unwrap();
        assert_eq!(out.len(), 64);
        assert!(out.iter().all(|&b| b == 0xAB));
        assert_eq!(out.as_ptr(), addr, "should have reused the pooled allocation");
    }

    #[test]
    fn write_then_recycle_makes_the_buffer_available_again() {
        let mut d = dev();
        let tok = d.transmit(Instant::from_millis(0)).unwrap();
        tok.consume(32, |b| b.fill(1));
        let pkt = d.pop_tx().unwrap();
        assert_eq!(d.pool.borrow().len(), 0);
        d.recycle(pkt);
        assert_eq!(d.pool.borrow().len(), 1);
    }

    #[test]
    fn rx_queue_is_bounded() {
        let mut d = dev();
        for _ in 0..(MAX_QUEUED + 50) {
            d.push_rx_from(&[0u8; 40]);
        }
        assert_eq!(d.rx.len(), MAX_QUEUED, "rx must not grow without bound");
        assert_eq!(d.dropped(), 50);
    }

    #[test]
    fn inject_tx_is_bounded() {
        let mut d = dev();
        for _ in 0..(MAX_QUEUED + 10) {
            d.inject_tx(vec![0u8; 40]);
        }
        assert_eq!(d.tx.borrow().len(), MAX_QUEUED);
        assert_eq!(d.dropped(), 10);
    }

    #[test]
    fn pool_itself_is_bounded() {
        let mut d = dev();
        for _ in 0..(MAX_POOLED * 3) {
            d.recycle(vec![0u8; 1500]);
        }
        assert_eq!(d.pool.borrow().len(), MAX_POOLED, "pool must not hoard");
    }
}
