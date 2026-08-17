//! JNI exports.
//!
//! Symbol names are mangled from `io.omnishield.bridge.NativeBridge`; renaming that class or
//! its package requires renaming every function here in lockstep.
//!
//! The runtime is handed to Kotlin as an opaque `long` produced by `Box::into_raw`. Only
//! `nativeStop` may consume it, and it nulls nothing on the Kotlin side — `NativeBridge`
//! is responsible for not calling through a stale handle.

use std::sync::atomic::Ordering;
use std::sync::Arc;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;

use crate::config::Config;
use crate::jvm::JavaBridge;
use crate::runtime::Runtime;

/// Initialise logging so `log::*` from Rust reaches logcat under the `omnishield` tag.
/// Safe to call more than once.
#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("omnishield"),
    );
    log::info!("omnishield-core {} initialised", env!("CARGO_PKG_VERSION"));
}

#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let version = format!("omnishield-core {}", env!("CARGO_PKG_VERSION"));
    new_string(env, &version)
}

/// Takes ownership of `tun_fd` and starts the tunnel thread. Returns an opaque handle, or 0
/// on failure.
#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeStart<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    service: JObject<'local>,
    tun_fd: jint,
    config_json: JString<'local>,
) -> jlong {
    let config = match env.get_string(&config_json) {
        Ok(s) => Config::from_json(&String::from(s)),
        Err(_) => Config::default(),
    };

    let bridge = match JavaBridge::new(&mut env, &service) {
        Ok(b) => Arc::new(b),
        Err(e) => {
            log::error!("cannot build JavaBridge: {e}");
            return 0;
        }
    };

    let runtime = Runtime::start(tun_fd, config, bridge);
    Box::into_raw(Box::new(runtime)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeStop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // Reconstituting the Box here is what frees the runtime; the tunnel thread is joined by
    // Runtime::stop before the allocation goes away.
    let mut runtime = unsafe { Box::from_raw(handle as *mut Runtime) };
    runtime.stop();
}

/// Replaces the live config (filter toggle, firewall UIDs, MITM opt-ins).
#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeUpdateConfig<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    config_json: JString<'local>,
) {
    let runtime = match as_runtime(handle) {
        Some(r) => r,
        None => return,
    };
    if let Ok(s) = env.get_string(&config_json) {
        let cfg = Config::from_json(&String::from(s));
        if let Ok(mut guard) = runtime.shared.config.write() {
            *guard = cfg;
        }
        // Any config change can move a verdict — filtering toggled, a snooze started or
        // ended, a different resolver — so cached answers from the old config must go.
        flush_dns_cache(runtime);
        // The loop no longer re-reads config on a 200 ms timer, so a setting the user just
        // toggled has to be pushed at it or it would not take effect until something else
        // happened to wake the tunnel.
        runtime.wake();
    }
}

/// Stages a blocklist for the next [`nativeCommitFilters`]. Returns lines accepted.
///
/// Staging rather than building: `extend` re-sorts and rewrites the whole blob, and calling it
/// once per list meant a full rebuild per list, each holding the old and new blobs at once.
#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeLoadFilters<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    list: JString<'local>,
) -> jint {
    let runtime = match as_runtime(handle) {
        Some(r) => r,
        None => return 0,
    };
    let text = match env.get_string(&list) {
        Ok(s) => String::from(s),
        Err(_) => return 0,
    };
    let mut filter = match runtime.shared.filter.write() {
        Ok(f) => f,
        Err(p) => p.into_inner(),
    };
    let staged = filter.stage_list(&text);
    drop(filter);
    staged as jint
}

/// Loads ABP-syntax lists (EasyList and friends) into the Layer 3 content filter. Returns the
/// number of rule lines seen.
#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeLoadContentRules<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    list: JString<'local>,
) -> jint {
    let runtime = match as_runtime(handle) {
        Some(r) => r,
        None => return 0,
    };
    let text = match env.get_string(&list) {
        Ok(s) => String::from(s),
        Err(_) => return 0,
    };
    let mut staged = runtime
        .staged_content
        .lock()
        .unwrap_or_else(|p| p.into_inner());
    staged.push(text);
    // The rule count is not known until the engine is compiled at commit, and on a cache hit
    // it is never derived from text at all — so it is reported through `nativeStats` instead
    // of returned here.
    staged.len() as jint
}

/// Loads both filters from the prebuilt cache identified by `key`.
///
/// Returns the DNS rule count on a hit, or -1 on a miss — in which case the caller must stage
/// the lists and call [`nativeCommitFilters`]. A hit skips reading, parsing, sorting and
/// engine-building entirely; the caller does not even open the 13 MB of list files.
#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeLoadCachedFilters<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    key: JString<'local>,
) -> jint {
    let runtime = match as_runtime(handle) {
        Some(r) => r,
        None => return -1,
    };
    let key = match env.get_string(&key) {
        Ok(s) => String::from(s),
        Err(_) => return -1,
    };
    let data_dir = cache_dir(runtime);

    let Some(dns_bytes) = crate::cache::read(&data_dir, crate::cache::DNS_CACHE, &key) else {
        return -1;
    };
    let Some(content_bytes) = crate::cache::read(&data_dir, crate::cache::CONTENT_CACHE, &key)
    else {
        return -1;
    };

    let mut filter = match runtime.shared.filter.write() {
        Ok(f) => f,
        Err(p) => p.into_inner(),
    };
    if !filter.load_cache_bytes(&dns_bytes) {
        log::warn!("dns filter cache rejected; rebuilding from lists");
        crate::cache::invalidate(&data_dir);
        return -1;
    }
    let count = filter.len();
    drop(filter);

    let mut content = match runtime.shared.content.write() {
        Ok(c) => c,
        Err(p) => p.into_inner(),
    };
    if !content.load_cache_bytes(&content_bytes) {
        log::warn!("content engine cache rejected; rebuilding from lists");
        crate::cache::invalidate(&data_dir);
        return -1;
    }
    let content_rules = content.rules();
    drop(content);

    runtime
        .shared
        .stats
        .filter_rules
        .store(count as u64, Ordering::Relaxed);
    flush_dns_cache(runtime);
    log::info!("filters restored from cache ({count} dns, {content_rules} content)");
    count as jint
}

/// Builds everything staged so far, persists it under `key`, and returns the DNS rule count.
#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeCommitFilters<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    key: JString<'local>,
) -> jint {
    let runtime = match as_runtime(handle) {
        Some(r) => r,
        None => return 0,
    };
    let key = match env.get_string(&key) {
        Ok(s) => String::from(s),
        Err(_) => return 0,
    };
    let data_dir = cache_dir(runtime);

    let mut filter = match runtime.shared.filter.write() {
        Ok(f) => f,
        Err(p) => p.into_inner(),
    };
    filter.commit();
    let count = filter.len();
    let dns_bytes = filter.to_cache_bytes();
    drop(filter);

    let lists = {
        let mut staged = runtime
            .staged_content
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        std::mem::take(&mut *staged)
    };
    let content_bytes = if lists.is_empty() {
        None
    } else {
        let mut content = match runtime.shared.content.write() {
            Ok(c) => c,
            Err(p) => p.into_inner(),
        };
        content.load(lists);
        Some(content.to_cache_bytes())
    };

    runtime
        .shared
        .stats
        .filter_rules
        .store(count as u64, Ordering::Relaxed);
    flush_dns_cache(runtime);

    // Written only once both halves succeeded, so a hit can never restore a DNS blob without
    // the matching engine.
    if let Some(cb) = content_bytes {
        crate::cache::write(&data_dir, crate::cache::DNS_CACHE, &key, &dns_bytes);
        crate::cache::write(&data_dir, crate::cache::CONTENT_CACHE, &key, &cb);
    }
    let content_rules = match runtime.shared.content.read() {
        Ok(c) => c.rules(),
        Err(p) => p.into_inner().rules(),
    };
    log::info!("filters built from lists ({count} dns, {content_rules} content)");
    count as jint
}

/// PEM of the device-unique root CA, for the install flow. Empty when Layer 2 is unavailable.
#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeCaPem<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let runtime = match as_runtime(handle) {
        Some(r) => r,
        None => return new_string(env, ""),
    };
    let pem = runtime.shared.ca_pem();
    new_string(env, &pem)
}

#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeClearFilters(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(runtime) = as_runtime(handle) {
        flush_dns_cache(runtime);
        if let Ok(mut f) = runtime.shared.filter.write() {
            f.clear();
        }
        runtime
            .shared
            .stats
            .filter_rules
            .store(0, Ordering::Relaxed);
    }
}

/// Removes and returns buffered events as a JSON array. Polled by Kotlin on a timer, which
/// keeps native-to-JVM calls off the packet path entirely.
#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeDrainEvents<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let runtime = match as_runtime(handle) {
        Some(r) => r,
        None => return new_string(env, "[]"),
    };
    let events = runtime.shared.events.drain();
    let json = serde_json::to_string(&events).unwrap_or_else(|_| "[]".to_string());
    new_string(env, &json)
}

#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeStats<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let runtime = match as_runtime(handle) {
        Some(r) => r,
        None => return new_string(env, "{}"),
    };
    let mut snapshot = runtime.shared.stats.snapshot();
    if let Ok(filter) = runtime.shared.filter.read() {
        snapshot.filter_bytes = filter.heap_bytes() as u64;
    }
    if let Ok(content) = runtime.shared.content.read() {
        snapshot.content_rules = content.rules() as u64;
    }
    if let Ok(cache) = runtime.shared.dns_cache.lock() {
        snapshot.dns_cached = cache.stats().0;
    }
    snapshot.doh_degraded = runtime.shared.doh_degraded.load(Ordering::Relaxed);
    let json = serde_json::to_string(&snapshot).unwrap_or_else(|_| "{}".to_string());
    new_string(env, &json)
}

/// Replaces the user's per-domain overrides.
///
/// Takes a JSON array of `{"domain": "...", "allow": true|false}`. Replaces rather than
/// merges, so an override removed in the UI is genuinely removed here too.
#[no_mangle]
pub extern "system" fn Java_io_omnishield_bridge_NativeBridge_nativeSetUserRules<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    rules_json: JString<'local>,
) -> jint {
    let runtime = match as_runtime(handle) {
        Some(r) => r,
        None => return 0,
    };
    let text = match env.get_string(&rules_json) {
        Ok(s) => String::from(s),
        Err(_) => return 0,
    };

    #[derive(serde::Deserialize)]
    struct Rule {
        domain: String,
        allow: bool,
    }

    let parsed: Vec<Rule> = serde_json::from_str(&text).unwrap_or_default();
    let mut filter = match runtime.shared.filter.write() {
        Ok(f) => f,
        Err(p) => p.into_inner(),
    };
    filter.set_user_rules(parsed.into_iter().map(|r| (r.domain, r.allow)));
    let count = filter.user_rule_count() as jint;
    drop(filter);
    // The whole point of an override is that it takes effect now. A cached answer from before
    // the user blocked a domain would keep resolving it and look like the setting was ignored.
    flush_dns_cache(runtime);
    count
}

// ---------------------------------------------------------------------------

fn cache_dir(runtime: &Runtime) -> String {
    match runtime.shared.config.read() {
        Ok(c) => c.cache_dir.clone(),
        Err(p) => p.into_inner().cache_dir.clone(),
    }
}

/// Drops every cached DNS answer.
///
/// Called from each mutator that could change what a name resolves to. Deliberately blunt: the
/// cost of throwing away a few hundred entries is a handful of extra upstream lookups, while
/// the cost of keeping one stale answer is a rule the user set being silently ignored.
fn flush_dns_cache(runtime: &Runtime) {
    runtime
        .shared
        .dns_cache
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .flush();
}

/// Borrows the runtime behind a handle without taking ownership. Returns `None` for 0 so a
/// call arriving after `nativeStop` is a no-op rather than a segfault.
fn as_runtime<'a>(handle: jlong) -> Option<&'a Runtime> {
    if handle == 0 {
        None
    } else {
        Some(unsafe { &*(handle as *const Runtime) })
    }
}

fn new_string(env: JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(v) => v.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
