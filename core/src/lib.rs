//! OmniShield native core.
//!
//! Owns the packet hot path: the TUN read loop, the userspace TCP/IP stack, DNS filtering,
//! TLS interception and content rewriting. Kotlin drives lifecycle and UI only — no packet
//! ever crosses the JNI boundary.
//!
//! Layering, outermost first:
//!
//! | Module          | Layer                                                   |
//! |-----------------|---------------------------------------------------------|
//! | [`tun`]         | smoltcp device over the TUN descriptor                  |
//! | [`packet`]      | IP/TCP/UDP triage                                       |
//! | [`runtime`]     | the event loop, connection tables, firewall             |
//! | [`dns`]/[`filter`] | Layer 1 — DNS sinkholing                             |
//! | [`ca`]/[`mitm`] | Layer 2 — TLS termination                               |
//! | [`content`]     | Layer 3 — ABP network rules and cosmetic filtering       |

pub mod ca;
pub mod cache;
pub mod config;
pub mod content;
pub mod dns;
pub mod dns_cache;
pub mod doh;
pub mod events;
pub mod filter;
pub mod mitm;
pub mod net;
pub mod packet;
// Not android-gated: it is a plain self-pipe, so it is testable on the host even though its
// only caller is the android-only tunnel loop.
pub mod wake;

#[cfg(target_os = "android")]
mod jvm;
// Not android-gated: the connection structs and the reap rules have no JNI in them, and the
// retirement rules (undeliverable backlogs, orphaned handles, idle UDP sessions) are exactly
// the code that has burned us before — they stay testable on the host.
// Its only consumer is the android-gated loop, so a host build sees every item as dead.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
mod relay;
#[cfg(target_os = "android")]
mod runtime;
// Not android-gated: the device performs no I/O of its own — the runtime loop owns the
// descriptor — so its queueing and buffer recycling are testable on the host.
pub mod tun;

#[cfg(target_os = "android")]
mod android;
