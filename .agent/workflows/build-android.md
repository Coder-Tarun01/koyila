---
description: turbo-build for android bridge
---
// turbo-all

1. Build the Rust bridge for Android
```bash
cd android-bridge && cargo ndk -t aarch64-linux-android -o ../android-client/app/src/main/jniLibs build --release
```
