# WhiteDNS VPN Android

Native Android VPN app powered by the Mihomo core through the FlClash Android JNI path.

- `Connect` starts Android `VpnService`, fetches the WhiteDNS Mihomo YAML subscription, initializes Mihomo, and passes the Android TUN fd into the core.
- `Disconnect` stops the Mihomo TUN/listeners and tears down the Android VPN service.

Default subscription:

```text
https://sub.whitedns.one/sub/mihomo.yaml
```

## Build

The app builds the native Mihomo core from the local `FlClash/` source tree. There is no public `libclash.aar` artifact in this flow; `scripts/build-flclash-core.sh` compiles FlClash `core` as `libclash.so` for Android and generates the JNI headers used by CMake.

```bash
./scripts/build-flclash-core.sh
./gradlew assembleDebug
```

The script expects:

- JDK 17
- Go
- Android SDK
- Android NDK
- local `FlClash/` source tree

Local checks:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

## Release

Release builds require release signing credentials. The FlClash core build runs automatically before CMake/release packaging.

```bash
cp keystore.properties.example keystore.properties
# edit keystore.properties with the real keystore path and passwords
make release
```

`make release` runs tests, builds signed release APKs for `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, and `universal`, verifies APK signatures when `apksigner` is available, and writes outputs to `release/`.

You can also pass signing values without a local properties file:

```bash
export WHITEDNS_RELEASE_STORE_FILE=/absolute/path/to/whitedns-release.jks
export WHITEDNS_RELEASE_STORE_PASSWORD=...
export WHITEDNS_RELEASE_KEY_ALIAS=whitedns
export WHITEDNS_RELEASE_KEY_PASSWORD=...
make release
```
