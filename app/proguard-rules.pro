# The JNI seam is resolved by name from native code, so it must survive shrinking.
-keep class io.omnishield.bridge.NativeBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# The Rust core calls these back by name and signature (see core/src/jvm.rs). Renaming or
# removing them would break UID attribution and the routing-loop guard at runtime, with no
# compile-time error to warn about it.
-keepclassmembers class io.omnishield.vpn.OmniShieldVpnService {
    public boolean protect(int);
    public int lookupUid(int, java.lang.String, int, java.lang.String, int);
    public java.lang.String packageForUid(int);
}
