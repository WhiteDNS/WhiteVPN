# WhiteDNS VPN Android

Minimal Android VPN app powered by sing-box. The first version intentionally shows only one button:

- `Connect` starts Android `VpnService`, fetches the default sing-box subscription, and starts sing-box.
- `Disconnect` stops sing-box and tears down the Android VPN service.

Default subscription:

```text
https://whitedns-sub.whitedns.workers.dev/encrypted
```

The app decrypts this AES-GCM subscription on device, decodes the base64
proxy-link payload, converts supported profiles into sing-box outbounds, and
tests only small sampled chunks so large subscriptions do not trigger full-list
delay scans on the user's device.

## Build

The app sources compile with a compile-only libbox stub until the real sing-box Android bridge is built. To make a runnable VPN APK, generate `app/libs/libbox.aar` first:

```bash
scripts/build-libbox.sh
./gradlew assembleDebug
```

`scripts/build-libbox.sh` uses pinned sing-box `v1.13.13` and expects:

- JDK 17
- Go 1.24.7 or a Go toolchain that can auto-download it
- Android SDK
- Android NDK

Local checks:

```bash
./gradlew test
./gradlew assembleDebug
```

## Release

Release builds require the real sing-box Android bridge and release signing
credentials.

```bash
scripts/build-libbox.sh
cp keystore.properties.example keystore.properties
# edit keystore.properties with the real keystore path and passwords
make release
```

`make release` runs tests, builds signed release APKs for `armeabi-v7a`,
`arm64-v8a`, `x86`, `x86_64`, and `universal`, verifies APK signatures when
`apksigner` is available, and writes outputs to `release/`.

You can also pass signing values without a local properties file:

```bash
export WHITEDNS_RELEASE_STORE_FILE=/absolute/path/to/whitedns-release.jks
export WHITEDNS_RELEASE_STORE_PASSWORD=...
export WHITEDNS_RELEASE_KEY_ALIAS=whitedns
export WHITEDNS_RELEASE_KEY_PASSWORD=...
make release
```
