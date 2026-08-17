//! Prebuilt filter caches on disk.
//!
//! Building the filters is by far the most expensive thing a tunnel start does. The lists total
//! ~13 MB of text; parsing them produces a `String` per domain, sorting and merging holds the
//! old blob alive beside the new one, and the ABP engine is compiled from ~135k rules. All of
//! that produces a structure that is entirely determined by its inputs — so it is worth
//! writing down.
//!
//! A cache file is only used when its stored key matches the caller's. The key is computed on
//! the Kotlin side from the name, size and modification time of every list file, which means
//! validating it costs a few `stat` calls rather than re-reading 13 MB to hash it. Any refresh
//! rewrites a whole file and therefore changes the key.
//!
//! Every failure path here is "fall back to parsing the lists". A cache is an optimisation; if
//! anything about it is doubtful — missing, truncated, wrong key, wrong version — the correct
//! response is to ignore it, never to serve a filter that might be wrong.

use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

const MAGIC: &[u8; 4] = b"OSFC";
/// Bump on any change to what the payload means, so an old file is refused rather than
/// misread. The key alone would not catch a format change, because the *inputs* are unchanged.
const VERSION: u32 = 1;

pub const DNS_CACHE: &str = "filters.bin";
pub const CONTENT_CACHE: &str = "content.bin";

fn path(data_dir: &str, name: &str) -> Option<PathBuf> {
    if data_dir.is_empty() {
        return None;
    }
    // `cache_dir` is already a directory dedicated to this; nesting another "cache" inside it
    // would just produce filtercache/cache/.
    let dir = Path::new(data_dir);
    fs::create_dir_all(dir).ok()?;
    Some(dir.join(name))
}

/// Writes `payload` under `key`. Failures are logged and swallowed — a cache that cannot be
/// written must never fail the tunnel start it was meant to speed up.
pub fn write(data_dir: &str, name: &str, key: &str, payload: &[u8]) {
    let Some(p) = path(data_dir, name) else {
        return;
    };
    // Written to a temporary and renamed, so a kill mid-write leaves the previous cache intact
    // rather than a half-file that the next start has to detect and discard.
    let tmp = p.with_extension("tmp");
    let result = (|| -> std::io::Result<()> {
        let mut f = fs::File::create(&tmp)?;
        f.write_all(MAGIC)?;
        f.write_all(&VERSION.to_le_bytes())?;
        f.write_all(&(key.len() as u32).to_le_bytes())?;
        f.write_all(key.as_bytes())?;
        f.write_all(payload)?;
        f.sync_all()?;
        fs::rename(&tmp, &p)
    })();
    match result {
        Ok(()) => log::info!("wrote {name} ({} bytes)", payload.len()),
        Err(e) => {
            log::warn!("could not write {name}: {e}");
            let _ = fs::remove_file(&tmp);
        }
    }
}

/// Reads the payload stored under `key`, or `None` if there is nothing usable.
pub fn read(data_dir: &str, name: &str, key: &str) -> Option<Vec<u8>> {
    let p = path(data_dir, name)?;
    let mut f = fs::File::open(&p).ok()?;
    let mut buf = Vec::new();
    f.read_to_end(&mut buf).ok()?;

    let head = 4 + 4 + 4;
    if buf.len() < head || &buf[0..4] != MAGIC {
        return None;
    }
    if u32::from_le_bytes(buf[4..8].try_into().ok()?) != VERSION {
        log::info!("{name} is from an older format; rebuilding");
        return None;
    }
    let key_len = u32::from_le_bytes(buf[8..12].try_into().ok()?) as usize;
    if buf.len() < head + key_len {
        return None;
    }
    if &buf[head..head + key_len] != key.as_bytes() {
        log::info!("{name} was built from different lists; rebuilding");
        return None;
    }
    Some(buf[head + key_len..].to_vec())
}

/// Removes both caches. Used when something downstream rejected them, so a bad file cannot be
/// retried forever.
pub fn invalidate(data_dir: &str) {
    for name in [DNS_CACHE, CONTENT_CACHE] {
        if let Some(p) = path(data_dir, name) {
            let _ = fs::remove_file(p);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmpdir(tag: &str) -> String {
        let d = std::env::temp_dir().join(format!("omnishield-cache-test-{tag}"));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d.to_string_lossy().into_owned()
    }

    #[test]
    fn round_trips_a_payload() {
        let dir = tmpdir("roundtrip");
        write(&dir, DNS_CACHE, "key-a", b"hello world");
        assert_eq!(
            read(&dir, DNS_CACHE, "key-a").as_deref(),
            Some(&b"hello world"[..])
        );
    }

    #[test]
    fn a_different_key_is_a_miss() {
        let dir = tmpdir("key");
        write(&dir, DNS_CACHE, "key-a", b"payload");
        assert!(
            read(&dir, DNS_CACHE, "key-b").is_none(),
            "lists changed; the cache must not be served"
        );
    }

    #[test]
    fn missing_file_is_a_miss_not_an_error() {
        let dir = tmpdir("missing");
        assert!(read(&dir, DNS_CACHE, "any").is_none());
    }

    #[test]
    fn truncation_and_corruption_are_misses() {
        let dir = tmpdir("corrupt");
        write(&dir, DNS_CACHE, "key", b"0123456789");
        let p = path(&dir, DNS_CACHE).unwrap();

        let full = fs::read(&p).unwrap();
        for cut in [0usize, 2, 4, 8, 11] {
            fs::write(&p, &full[..cut.min(full.len())]).unwrap();
            assert!(
                read(&dir, DNS_CACHE, "key").is_none(),
                "truncated to {cut} must be refused"
            );
        }

        // Wrong magic entirely.
        fs::write(&p, b"XXXXnot a cache file at all").unwrap();
        assert!(read(&dir, DNS_CACHE, "key").is_none());
    }

    #[test]
    fn a_stale_version_is_refused() {
        let dir = tmpdir("version");
        write(&dir, DNS_CACHE, "key", b"payload");
        let p = path(&dir, DNS_CACHE).unwrap();
        let mut raw = fs::read(&p).unwrap();
        raw[4..8].copy_from_slice(&(VERSION + 1).to_le_bytes());
        fs::write(&p, raw).unwrap();
        assert!(read(&dir, DNS_CACHE, "key").is_none());
    }

    #[test]
    fn invalidate_removes_both() {
        let dir = tmpdir("invalidate");
        write(&dir, DNS_CACHE, "k", b"a");
        write(&dir, CONTENT_CACHE, "k", b"b");
        invalidate(&dir);
        assert!(read(&dir, DNS_CACHE, "k").is_none());
        assert!(read(&dir, CONTENT_CACHE, "k").is_none());
    }

    #[test]
    fn an_empty_data_dir_is_handled() {
        write("", DNS_CACHE, "k", b"x");
        assert!(read("", DNS_CACHE, "k").is_none());
    }
}
