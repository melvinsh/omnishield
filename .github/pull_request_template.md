## What this changes

<!-- And why. If there is an issue, link it. -->

## Which suites you ran

<!-- CI runs all of these. Saying which ones you ran locally tells the reviewer what to
     look at first. If you could not run the instrumented ones, say so rather than leaving
     it blank. -->

- [ ] `cd core && cargo test` (101)
- [ ] `./gradlew testDebugUnitTest` (58)
- [ ] `./gradlew connectedDebugAndroidTest` (36, needs an emulator)
- [ ] `./gradlew lint`
- [ ] `cargo fmt --check` and `cargo clippy --all-targets -- -D warnings`

## Anything that needs checking on a real device

<!-- CI cannot cover the LAN routing behaviour: QEMU's NAT has no LAN peer to connect
     inward from. Nor can it measure idle CPU. If your change touches either, say what you
     saw on hardware. -->
