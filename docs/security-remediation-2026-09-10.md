# Security remediation — 10 September 2026

The three approved changes are implemented. Security regression tests pass. The broader Android lint check still reports six pre-existing errors. Changes are uncommitted and have not been published.

## Changes

1. **F-03: subscription configuration boundary.** The patched native `config.UnmarshalRawConfig` validates the final Android YAML before `ParseRawConfig` can apply side effects. It decodes one mapping/document, rejects unknown root keys, root merges, duplicate keys, extra listeners/tunnels, alternate servers and TLS server settings, and requires the app's mixed port, authenticated loopback controller, loopback DNS and disabled native TUN. Nested proxy aliases, proxies/providers/rules and explicit LAN-sharing settings remain supported. Invalid setup returns an error without replacing the active configuration. Stop and shutdown now close the patched inbound and TCP/UDP tunnel collections. Geodata updater controls are removed by the runtime builder; bundled geodata and routing rules remain.
2. **F-01: native dependency updates.** Go is pinned to **1.26.8** locally and in CI. Updated effective dependencies include `x/crypto v0.56.0`, `x/net v0.57.0`, `x/text v0.41.0`, `compress v1.18.7`, and DHCP commit `c76316d4aa82`. The existing Mihomo **v1.19.30** / FlClash integration remains pinned. The build marker now includes the Go version and both patch contents, preventing stale native outputs after these changes. Native regression tests run on fresh builds.
3. **F-02: remove unused payload encryption.** Removed AES payload decoding, encrypted IP-list fetching/scanning, embedded payload-key fields, CI key inputs and release-key requirements for those payloads. Built-in subscriptions use the existing plain HTTPS loader. The legacy public-cache filename remains because its contents were already plaintext YAML. Manual fronting, cached endpoints, HTTPS validation and VPN protocol encryption remain.

Primary files: `scripts/build-flclash-core.sh`, both `scripts/patches/*v1.19.30*.patch` files, `.github/workflows/android.yml`, `app/build.gradle.kts`, `secrets.properties.example`, and `MihomoRuntime.kt`, `SubscriptionSnapshot.kt`, `WhiteDnsConfig.kt`, `WhiteDnsVpnService.kt`, `StartupScanPolicy.kt`, `DiagnosticLogger.kt`. Removed `EncryptedIpList.kt` and its obsolete codec tests. Added native regression tests in the patch and `NativeSecurityBoundaryTest.kt`; updated affected JVM tests. Two pre-existing Android tests were corrected to the actual application ID and current “WhiteVPN Private” label. Concurrent UI/speed-test changes were preserved.

## Verification

| Check | Result |
|---|---|
| Shell syntax and source diff whitespace | Pass; patch-file context whitespace checked through patch application |
| Native `go test` for config/listener packages | Pass; malicious YAML variants, nested aliases, LAN settings, real TCP/UDP cleanup and repeated stop |
| `go mod verify` | Pass |
| Fresh native build | Pass: armeabi-v7a, arm64-v8a, x86, x86_64 |
| `testDebugUnitTest` | 286 passed, none skipped |
| `testReleaseUnitTest` | 286 passed, none skipped |
| `assembleDebug assembleDebugAndroidTest` | Pass |
| `connectedDebugAndroidTest` | 12 passed on Android 11 / ARM64 |
| Final universal APK inspection | All four libraries contain Go 1.26.8 and the security guard; match Gradle's stripped outputs |
| APK DEX inspection | Removed payload classes and key/endpoint fields absent |
| Independent source review | No remaining findings after correcting geodata compatibility |

The Android regression sends listener, tunnel, quoted-key, root-merge, duplicate-key, unknown-key and extra-document inputs through the runtime builder and actual `Core.quickSetup`. All are rejected without opening the injected ports. A valid nested-alias configuration starts; socket enumeration confirms default proxy, DNS and controller bind to loopback. HTTPS through Mihomo returns HTTP 204. An authenticated hostile controller reload is rejected while the active proxy remains available. Stop closes the mixed listener; shutdown leaves the injected ports unreachable.

Reproduction commands:

```sh
FORCE_FLCLASH_CORE_BUILD=1 ./scripts/build-flclash-core.sh
WHITEDNS_PRIVATE_MIHOMO_SUBSCRIPTION_URL=https://example.com/security-test-subscription \
  ./gradlew testDebugUnitTest testReleaseUnitTest assembleDebug assembleDebugAndroidTest
WHITEDNS_PRIVATE_MIHOMO_SUBSCRIPTION_URL=https://example.com/security-test-subscription \
  ./gradlew connectedDebugAndroidTest
```

The debug test APK uses a placeholder private subscription URL. It is not a production release. Universal APK SHA-256: `d971d3d833a8fab959e94805ba456f6b6861ab2b2fad637a60f65617f717d0ee`.

## Remaining findings and limits

- Android source-mode `govulncheck v1.7.0` reports **zero vulnerable symbols and zero vulnerable imported packages**. Scanning each final stripped APK library reports only **GO-2026-5932**, the unmaintained OpenPGP package. The exact dependency graphs for all four Android ABIs exclude OpenPGP. This is the scanner's conservative module-level fallback when it cannot extract symbols, rather than evidence that OpenPGP is packaged. See [govulncheck limitations](https://pkg.go.dev/golang.org/x/vuln/cmd/govulncheck) and [GO-2026-5932](https://pkg.go.dev/vuln/GO-2026-5932).
- `lintDebug` fails with six existing errors: four API-27 navigation-bar attributes used with minSdk 26, the deprecated Quick Settings tile launch call, and `QUERY_ALL_PACKAGES`. All six affected files are byte-identical to the starting commit. These are outside the three approved remediations.
- Runtime testing used ARM64 Android 11. The other ABIs were compiled and inspected, not executed. HTTPS-through-core testing used a controlled DIRECT route; live production VPN credentials/protocol endpoints were not exercised.
- Initial Android UI checks were obstructed by an emulator System UI ANR. After dismissing that dialog and correcting the stale assertions, the complete suite passed.
