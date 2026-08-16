//! The Rust → Kotlin call path.
//!
//! Three things genuinely require the JVM: `VpnService.protect()` (to keep our own upstream
//! sockets out of the tunnel and avoid a routing loop), UID attribution, and package-name
//! lookup. All three are *per connection*, never per packet, which is what makes the cost
//! acceptable.
//!
//! UID and package lookup go through small Kotlin helpers rather than calling
//! `getConnectionOwnerUid` directly, because building `InetSocketAddress` objects over JNI is
//! far more code than passing an address as a string.

use jni::objects::{GlobalRef, JObject, JString, JValue};
use jni::{JNIEnv, JavaVM};

pub struct JavaBridge {
    vm: JavaVM,
    service: GlobalRef,
}

impl JavaBridge {
    pub fn new(env: &mut JNIEnv, service: &JObject) -> Result<Self, jni::errors::Error> {
        Ok(Self {
            vm: env.get_java_vm()?,
            service: env.new_global_ref(service)?,
        })
    }

    /// Excludes `fd` from the tunnel. Without this every upstream socket we open would be
    /// routed straight back into our own TUN, producing an infinite loop.
    pub fn protect(&self, fd: i32) -> bool {
        let mut env = match self.vm.attach_current_thread() {
            Ok(e) => e,
            Err(e) => {
                log::error!("protect: cannot attach thread: {e}");
                return false;
            }
        };
        match env.call_method(&self.service, "protect", "(I)Z", &[JValue::Int(fd)]) {
            Ok(v) => v.z().unwrap_or(false),
            Err(e) => {
                log::error!("protect({fd}) failed: {e}");
                let _ = env.exception_clear();
                false
            }
        }
    }

    /// Owning UID of a connection, or -1 if unknown. Backed by
    /// `VpnService.getConnectionOwnerUid`, which requires API 29+.
    pub fn owner_uid(
        &self,
        protocol: i32,
        local_ip: &str,
        local_port: i32,
        remote_ip: &str,
        remote_port: i32,
    ) -> i32 {
        let mut env = match self.vm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return -1,
        };
        let local = match env.new_string(local_ip) {
            Ok(s) => s,
            Err(_) => return -1,
        };
        let remote = match env.new_string(remote_ip) {
            Ok(s) => s,
            Err(_) => return -1,
        };
        let res = env.call_method(
            &self.service,
            "lookupUid",
            "(ILjava/lang/String;ILjava/lang/String;I)I",
            &[
                JValue::Int(protocol),
                JValue::Object(&local),
                JValue::Int(local_port),
                JValue::Object(&remote),
                JValue::Int(remote_port),
            ],
        );
        match res {
            Ok(v) => v.i().unwrap_or(-1),
            Err(e) => {
                log::debug!("lookupUid failed: {e}");
                let _ = env.exception_clear();
                -1
            }
        }
    }

    /// Package name for a UID, or an empty string.
    pub fn package_for_uid(&self, uid: i32) -> String {
        let mut env = match self.vm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return String::new(),
        };
        let res = env.call_method(
            &self.service,
            "packageForUid",
            "(I)Ljava/lang/String;",
            &[JValue::Int(uid)],
        );
        match res {
            Ok(v) => match v.l() {
                Ok(obj) if !obj.is_null() => env
                    .get_string(&JString::from(obj))
                    .map(|s| s.into())
                    .unwrap_or_default(),
                _ => String::new(),
            },
            Err(e) => {
                log::debug!("packageForUid failed: {e}");
                let _ = env.exception_clear();
                String::new()
            }
        }
    }
}

// The GlobalRef and JavaVM are both safe to use from any thread once created; the tunnel
// thread attaches on demand.
unsafe impl Send for JavaBridge {}
unsafe impl Sync for JavaBridge {}
