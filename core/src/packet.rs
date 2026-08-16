//! Minimal IP/TCP/UDP header parsing.
//!
//! The TUN interface hands us raw IP packets with no link-layer header. We only need enough
//! of each header to make a routing decision (which 5-tuple, which protocol) before either
//! handing the frame to smoltcp or handling it ourselves. smoltcp does the real parsing for
//! anything it owns; this module exists so the runtime can triage without paying for a full
//! parse of every packet.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

pub const PROTO_TCP: u8 = 6;
pub const PROTO_UDP: u8 = 17;
pub const PROTO_ICMP: u8 = 1;
pub const PROTO_ICMPV6: u8 = 58;

/// TCP flag bits we care about.
pub const TCP_FIN: u8 = 0x01;
pub const TCP_SYN: u8 = 0x02;
pub const TCP_RST: u8 = 0x04;
pub const TCP_ACK: u8 = 0x10;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Endpoints {
    pub src: IpAddr,
    pub dst: IpAddr,
    pub src_port: u16,
    pub dst_port: u16,
}

#[derive(Debug, Clone, Copy)]
pub struct PacketInfo {
    pub protocol: u8,
    pub endpoints: Endpoints,
    /// TCP flags, or 0 for non-TCP.
    pub tcp_flags: u8,
    /// Offset of the transport payload within the original buffer.
    pub payload_offset: usize,
}

impl PacketInfo {
    pub fn is_syn(&self) -> bool {
        self.protocol == PROTO_TCP
            && (self.tcp_flags & TCP_SYN) != 0
            && (self.tcp_flags & TCP_ACK) == 0
    }
}

/// Parses an IPv4 or IPv6 packet far enough to identify its 5-tuple.
///
/// Returns `None` for anything we cannot route: truncated buffers, unknown IP versions, or
/// IPv6 packets whose extension-header chain we do not walk. Callers must treat `None` as
/// "drop", never as "forward unfiltered" — silently passing a packet we failed to understand
/// would be a filtering bypass.
pub fn parse(buf: &[u8]) -> Option<PacketInfo> {
    if buf.is_empty() {
        return None;
    }
    match buf[0] >> 4 {
        4 => parse_ipv4(buf),
        6 => parse_ipv6(buf),
        _ => None,
    }
}

fn parse_ipv4(buf: &[u8]) -> Option<PacketInfo> {
    if buf.len() < 20 {
        return None;
    }
    let ihl = ((buf[0] & 0x0f) as usize) * 4;
    if ihl < 20 || buf.len() < ihl {
        return None;
    }

    // Fragmented packets other than the first carry no transport header. We cannot filter
    // what we cannot parse, so they are dropped rather than passed through.
    let frag_off = u16::from_be_bytes([buf[6], buf[7]]) & 0x1fff;
    if frag_off != 0 {
        return None;
    }

    let protocol = buf[9];
    let src = IpAddr::V4(Ipv4Addr::new(buf[12], buf[13], buf[14], buf[15]));
    let dst = IpAddr::V4(Ipv4Addr::new(buf[16], buf[17], buf[18], buf[19]));
    finish(buf, protocol, src, dst, ihl)
}

fn parse_ipv6(buf: &[u8]) -> Option<PacketInfo> {
    if buf.len() < 40 {
        return None;
    }
    let protocol = buf[6];
    let mut s = [0u8; 16];
    let mut d = [0u8; 16];
    s.copy_from_slice(&buf[8..24]);
    d.copy_from_slice(&buf[24..40]);
    let src = IpAddr::V6(Ipv6Addr::from(s));
    let dst = IpAddr::V6(Ipv6Addr::from(d));

    // Extension headers are not walked. Anything that is not a bare TCP/UDP/ICMPv6 next
    // header is dropped rather than guessed at.
    finish(buf, protocol, src, dst, 40)
}

fn finish(
    buf: &[u8],
    protocol: u8,
    src: IpAddr,
    dst: IpAddr,
    header_len: usize,
) -> Option<PacketInfo> {
    let rest = &buf[header_len..];
    let (src_port, dst_port, tcp_flags, payload_offset) = match protocol {
        PROTO_TCP => {
            if rest.len() < 20 {
                return None;
            }
            let data_off = ((rest[12] >> 4) as usize) * 4;
            if data_off < 20 || rest.len() < data_off {
                return None;
            }
            (
                u16::from_be_bytes([rest[0], rest[1]]),
                u16::from_be_bytes([rest[2], rest[3]]),
                rest[13],
                header_len + data_off,
            )
        }
        PROTO_UDP => {
            if rest.len() < 8 {
                return None;
            }
            (
                u16::from_be_bytes([rest[0], rest[1]]),
                u16::from_be_bytes([rest[2], rest[3]]),
                0,
                header_len + 8,
            )
        }
        PROTO_ICMP | PROTO_ICMPV6 => (0, 0, 0, header_len),
        _ => return None,
    };

    Some(PacketInfo {
        protocol,
        endpoints: Endpoints {
            src,
            dst,
            src_port,
            dst_port,
        },
        tcp_flags,
        payload_offset,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// IPv4 + TCP SYN to 93.184.216.34:443 from 10.0.0.2:51000.
    fn ipv4_syn() -> Vec<u8> {
        let mut p = vec![0u8; 40];
        p[0] = 0x45; // v4, IHL 5
        p[9] = PROTO_TCP;
        p[12..16].copy_from_slice(&[10, 0, 0, 2]);
        p[16..20].copy_from_slice(&[93, 184, 216, 34]);
        p[20..22].copy_from_slice(&51000u16.to_be_bytes());
        p[22..24].copy_from_slice(&443u16.to_be_bytes());
        p[32] = 5 << 4; // data offset 5 words
        p[33] = TCP_SYN;
        p
    }

    #[test]
    fn parses_ipv4_tcp_syn() {
        let info = parse(&ipv4_syn()).expect("should parse");
        assert_eq!(info.protocol, PROTO_TCP);
        assert_eq!(info.endpoints.dst_port, 443);
        assert_eq!(info.endpoints.src_port, 51000);
        assert!(info.is_syn());
        assert_eq!(info.payload_offset, 40);
    }

    #[test]
    fn rejects_truncated() {
        assert!(parse(&[0x45, 0, 0]).is_none());
        assert!(parse(&[]).is_none());
    }

    #[test]
    fn rejects_unknown_version() {
        assert!(parse(&[0x75, 0, 0, 0]).is_none());
    }

    #[test]
    fn rejects_non_initial_fragment() {
        let mut p = ipv4_syn();
        p[7] = 1; // non-zero fragment offset
        assert!(parse(&p).is_none());
    }

    #[test]
    fn ack_is_not_syn() {
        let mut p = ipv4_syn();
        p[33] = TCP_SYN | TCP_ACK;
        assert!(!parse(&p).unwrap().is_syn());
    }
}
