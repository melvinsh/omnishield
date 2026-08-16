//! Root CA management and per-SNI leaf minting.
//!
//! The CA key is generated on the device on first use and never leaves it. Shipping a shared
//! CA key in the APK would mean anyone holding the APK could forge certificates for every
//! OmniShield user — the single worst thing a TLS-intercepting app can do.
//!
//! Leaves are minted on demand for whatever SNI the client asked for and cached, because
//! generating a keypair per connection would add tens of milliseconds to every handshake.

use std::num::NonZeroUsize;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use lru::LruCache;
use rcgen::{
    BasicConstraints, CertificateParams, DistinguishedName, DnType, ExtendedKeyUsagePurpose,
    IsCa, Issuer, KeyPair, KeyUsagePurpose,
};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::sign::CertifiedKey;

const CA_CERT_FILE: &str = "omnishield-ca.pem";
const CA_KEY_FILE: &str = "omnishield-ca.key";
const LEAF_CACHE: usize = 256;

pub struct CertAuthority {
    /// Owns both the CA parameters and its signing key; rcgen 0.14 signs through this type.
    issuer: Issuer<'static, KeyPair>,
    ca_cert_der: CertificateDer<'static>,
    ca_pem: String,
    cache: Mutex<LruCache<String, Arc<CertifiedKey>>>,
}

impl CertAuthority {
    /// Loads the CA from `dir`, generating and persisting one if absent.
    pub fn load_or_create(dir: &Path) -> Result<Self, String> {
        let cert_path = dir.join(CA_CERT_FILE);
        let key_path = dir.join(CA_KEY_FILE);

        if cert_path.exists() && key_path.exists() {
            match Self::load(&cert_path, &key_path) {
                Ok(ca) => return Ok(ca),
                Err(e) => log::warn!("existing CA unusable ({e}); regenerating"),
            }
        }
        let ca = Self::generate()?;
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
        std::fs::write(&cert_path, &ca.ca_pem).map_err(|e| e.to_string())?;
        std::fs::write(&key_path, ca.issuer.key().serialize_pem()).map_err(|e| e.to_string())?;
        Ok(ca)
    }

    fn load(cert_path: &PathBuf, key_path: &PathBuf) -> Result<Self, String> {
        let ca_pem = std::fs::read_to_string(cert_path).map_err(|e| e.to_string())?;
        let key_pem = std::fs::read_to_string(key_path).map_err(|e| e.to_string())?;
        let ca_key = KeyPair::from_pem(&key_pem).map_err(|e| e.to_string())?;
        let issuer = Issuer::from_ca_cert_pem(&ca_pem, ca_key).map_err(|e| e.to_string())?;
        let ca_cert_der = pem_to_der(&ca_pem)?;

        Ok(Self {
            issuer,
            ca_cert_der,
            ca_pem,
            cache: Mutex::new(LruCache::new(NonZeroUsize::new(LEAF_CACHE).unwrap())),
        })
    }

    fn generate() -> Result<Self, String> {
        let ca_key = KeyPair::generate().map_err(|e| e.to_string())?;

        let mut dn = DistinguishedName::new();
        dn.push(DnType::CommonName, "OmniShield Root CA");
        dn.push(DnType::OrganizationName, "OmniShield");

        let mut params = CertificateParams::new(Vec::<String>::new()).map_err(|e| e.to_string())?;
        params.distinguished_name = dn;
        params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
        params.key_usages = vec![
            KeyUsagePurpose::KeyCertSign,
            KeyUsagePurpose::CrlSign,
            KeyUsagePurpose::DigitalSignature,
        ];

        let cert = params.self_signed(&ca_key).map_err(|e| e.to_string())?;
        let ca_pem = cert.pem();
        let ca_cert_der = CertificateDer::from(cert.der().to_vec());
        let issuer = Issuer::new(params, ca_key);

        log::info!("generated a new OmniShield root CA");
        Ok(Self {
            issuer,
            ca_cert_der,
            ca_pem,
            cache: Mutex::new(LruCache::new(NonZeroUsize::new(LEAF_CACHE).unwrap())),
        })
    }

    /// PEM the user installs into the Android trust store.
    pub fn ca_pem(&self) -> &str {
        &self.ca_pem
    }

    /// Mints (or returns a cached) leaf certificate for `sni`, signed by this CA.
    pub fn leaf_for(&self, sni: &str) -> Result<Arc<CertifiedKey>, String> {
        if let Some(hit) = self
            .cache
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(sni)
            .cloned()
        {
            return Ok(hit);
        }

        let leaf_key = KeyPair::generate().map_err(|e| e.to_string())?;
        let mut dn = DistinguishedName::new();
        dn.push(DnType::CommonName, sni);

        let mut params =
            CertificateParams::new(vec![sni.to_string()]).map_err(|e| e.to_string())?;
        params.distinguished_name = dn;
        params.is_ca = IsCa::NoCa;
        params.use_authority_key_identifier_extension = true;
        params.key_usages = vec![
            KeyUsagePurpose::DigitalSignature,
            KeyUsagePurpose::KeyEncipherment,
        ];
        params.extended_key_usages = vec![ExtendedKeyUsagePurpose::ServerAuth];

        let leaf = params
            .signed_by(&leaf_key, &self.issuer)
            .map_err(|e| e.to_string())?;

        let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(leaf_key.serialize_der()));
        let signing_key = rustls::crypto::ring::sign::any_supported_type(&key_der)
            .map_err(|e| e.to_string())?;

        // The chain must include the CA itself, or clients that only pinned the root will
        // fail to build a path.
        let certified = Arc::new(CertifiedKey::new(
            vec![
                CertificateDer::from(leaf.der().to_vec()),
                self.ca_cert_der.clone(),
            ],
            signing_key,
        ));

        self.cache
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .put(sni.to_string(), Arc::clone(&certified));
        Ok(certified)
    }
}

fn pem_to_der(pem: &str) -> Result<CertificateDer<'static>, String> {
    let body: String = pem
        .lines()
        .filter(|l| !l.starts_with("-----"))
        .collect::<Vec<_>>()
        .join("");
    let der = base64_decode(&body)?;
    Ok(CertificateDer::from(der))
}

/// Minimal base64 decoder — avoids pulling a crate in for one call site.
fn base64_decode(input: &str) -> Result<Vec<u8>, String> {
    const TABLE: &[u8; 64] =
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut lookup = [255u8; 256];
    for (i, &c) in TABLE.iter().enumerate() {
        lookup[c as usize] = i as u8;
    }

    let mut out = Vec::with_capacity(input.len() * 3 / 4);
    let mut acc: u32 = 0;
    let mut bits = 0u32;
    for b in input.bytes() {
        if b == b'=' || b.is_ascii_whitespace() {
            continue;
        }
        let v = lookup[b as usize];
        if v == 255 {
            return Err(format!("invalid base64 byte {b}"));
        }
        acc = (acc << 6) | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_ca_and_signs_leaves() {
        let ca = CertAuthority::generate().expect("ca");
        assert!(ca.ca_pem().contains("BEGIN CERTIFICATE"));

        let leaf = ca.leaf_for("example.com").expect("leaf");
        // Leaf plus the CA itself, so clients can build a path to the installed root.
        assert_eq!(leaf.cert.len(), 2);
    }

    #[test]
    fn caches_leaves_per_sni() {
        let ca = CertAuthority::generate().expect("ca");
        let a = ca.leaf_for("example.com").unwrap();
        let b = ca.leaf_for("example.com").unwrap();
        assert!(Arc::ptr_eq(&a, &b), "second call must hit the cache");

        let c = ca.leaf_for("other.com").unwrap();
        assert!(!Arc::ptr_eq(&a, &c));
    }

    #[test]
    fn persists_and_reloads_same_ca() {
        let dir = std::env::temp_dir().join(format!("omnishield-ca-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();

        let first = CertAuthority::load_or_create(&dir).expect("create");
        let second = CertAuthority::load_or_create(&dir).expect("reload");
        assert_eq!(
            first.ca_pem(),
            second.ca_pem(),
            "reload must not regenerate the CA — a new root would invalidate the one the \
             user installed into the trust store"
        );
        // A reloaded CA must still be able to sign.
        assert!(second.leaf_for("example.com").is_ok());

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn base64_roundtrip_of_der_is_nonempty() {
        let ca = CertAuthority::generate().expect("ca");
        let der = pem_to_der(ca.ca_pem()).expect("decode");
        assert!(!der.is_empty());
        assert_eq!(der.as_ref(), ca.ca_cert_der.as_ref());
    }
}
