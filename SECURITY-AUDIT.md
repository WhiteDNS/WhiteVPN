# WhiteVPN — Security Audit

**Scope:** `com.whitedns.vpn` Android application at commit `a23a317` (versionName 0.0.9, versionCode 73).
**Date:** 2026-08-01
**Reviewed:** Android manifest and component export surface, network security config, TLS handling,
payload cryptography, subscription import and parsing, generated Mihomo runtime config, VPN service
lifecycle, local storage, JNI bridge to ByeByeDPI, build and signing configuration, and git history.

Severity reflects impact on a user of this app in its intended setting — a censorship-circumvention
client whose threat model includes a hostile network operator and hostile subscription providers.

---

## Summary

| # | Finding | Severity | Status |
|---|---------|----------|--------|
| 1 | Payload decryption keys hardcoded in source and git history | High | Mitigated (rotation still required) |
| 2 | Clean-IP speed test performs TLS without hostname verification | High | Fixed |
| 3 | Mihomo DNS resolver bound to `0.0.0.0` | Medium | Fixed |
| 4 | YAML injection through imported Clash/Xray JSON subscriptions | Medium | Fixed |
| 5 | Imported subscriptions could silently disable certificate validation | Medium | Fixed |
| 6 | Device-to-device transfer of app data not disabled | Medium | Fixed |
| 7 | Diagnostics redaction covered a single literal token | Low | Fixed |
| 8 | Broadcast receivers implicitly exported below API 33 | Low | Fixed |
| 9 | `local.properties`, `.idea/`, `.kotlin/` committed | Low | Fixed |
| 10 | `gradle.properties` pinned one machine's JDK path | Low | Fixed |
| 11 | Diagnostics clipboard copy not flagged sensitive | Low | Fixed |
| 12 | Scanner sockets proceed when `VpnService.protect()` fails | Low | Reported, unchanged |
| 13 | Mihomo YAML subscriptions may still set `skip-cert-verify` | Low | Reported, unchanged |
| 14 | `jniStopProxy` closes `server_fd` without synchronisation | Low | Reported, unchanged |
| 15 | Firebase Analytics merges advertising-ID permissions into the APK | Medium | Reported, unchanged |

---

## 1. Payload decryption keys hardcoded in source and git history — High

`WhiteDnsConfig.kt` carried the AES-GCM passphrases for both the Mihomo subscription payload and the
encrypted clean-IP list as `const val` literals. They are present in every shipped APK and in the git
history from the earliest commit in this repository (`08546a1` renames but does not change
`SUBSCRIPTION_ENCRYPTION_KEY`). `DiagnosticLogger.sanitizeForLog` additionally embedded a
subscription token literal.

Anyone who unpacks the APK — which is the expected posture for an adversary in this threat model —
can decrypt both endpoints. The encryption is obfuscation, not confidentiality. Key derivation is a
bare `SHA-256(passphrase)` with no salt and no KDF, so it also offers no work factor.

**Change made.** The literals are removed from source. `app/build.gradle.kts` injects them as
`BuildConfig` fields from `WHITEDNS_MIHOMO_SUBSCRIPTION_KEY` / `WHITEDNS_ENCRYPTED_IP_LIST_KEY` or
from a gitignored `secrets.properties`, mirroring the existing `keystore.properties` convention.
Release builds fail via `validateReleaseInputs` when the keys are missing; debug builds compile with
an empty value and `EncryptedPayloadCodec` now rejects a blank passphrase instead of deriving a key
from the empty string.

**Still required, and not doable from the client side:** rotate both passphrases and update the
Cloudflare Workers that serve the payloads. The current values must be treated as public. Consider
purging them from git history (`git filter-repo`) once rotated. Longer term, an authenticated
endpoint plus a per-install key would be a real control; a shared symmetric key shipped in the
binary can only ever raise the effort bar.

## 2. Clean-IP speed test performs TLS without hostname verification — High

`CleanIpScanner.measureDownloadBytesPerSecond` (`CleanIp.kt`) wrapped a raw socket with
`SSLSocketFactory.getDefault()` and called `startHandshake()` directly. A plain `SSLSocket` validates
the certificate chain but does **not** check that the certificate belongs to the requested host — that
check lives in `HttpsURLConnection`, not in `SSLSocket` (CWE-297).

The scanner connects to arbitrary candidate IPs and speaks HTTPS to them as `speed.cloudflare.com`.
Any party able to present a chain signed by any trusted CA — an on-path network operator with a
mis-issued or coerced certificate — could terminate the connection, return bytes fast, and have their
IP ranked best. The measured throughput feeds directly into endpoint selection, so this steers which
endpoint the user's traffic subsequently uses.

**Change made.** `endpointIdentificationAlgorithm = "HTTPS"` is set on the socket's `SSLParameters`
before the handshake, and the probe fails closed if it cannot be applied. Legitimate Cloudflare edge
IPs continue to pass, since SNI is already set to the speed-test host.

## 3. Mihomo DNS resolver bound to `0.0.0.0` — Medium

`MihomoRuntimeConfigBuilder.flClashRuntimeYaml` emitted `dns.listen: 0.0.0.0:1053`. `allow-lan:
false` gates the proxy listeners but not the DNS listener, so while the VPN was running the device
answered DNS queries from any peer on the same network. On shared or public Wi-Fi this is an open
resolver usable for amplification, and it lets a co-located party probe the user's resolver.

**Change made.** Bound to `127.0.0.1:1053`, with a regression test.

## 4. YAML injection through imported Clash/Xray JSON subscriptions — Medium

`MihomoLinkConfigBuilder.build` emitted each proxy property as `<key>: <value>`. Values went through
`quote()`; keys did not. For subscriptions imported as Clash JSON, `JsonSubscriptionImporter.clashProxies`
passes proxy objects through verbatim — only `name`, `type`, `server` and `port` are validated, and
every other key is attacker-chosen. A JSON key containing a newline therefore appended arbitrary
lines to the generated profile that the core then loads, allowing injected proxies, rules, or
`skip-cert-verify: true` on a legitimate-looking entry.

**Change made.** Proxies whose keys or values contain a line break are dropped before serialisation.
Quoting alone is insufficient here because a YAML scalar may legally span lines. Regression test
added.

## 5. Imported subscriptions could silently disable certificate validation — Medium

`allowInsecure` from a `trojan://` link (`SubConvConverter.parseTrojan`) and from imported Xray JSON
(`JsonSubscriptionImporter.xrayProxy`, `SubConvConverter.downloadSettings`) was translated into
`skip-cert-verify: true`. Anyone who could get a user to paste a link could turn off TLS validation
on the tunnel itself, with no indication in the UI.

**Change made.** The translation layers now always emit `skip-cert-verify: false`. See finding 13 for
the path that remains.

## 6. Device-to-device transfer of app data not disabled — Medium

The manifest set `android:allowBackup="false"` but referenced neither `android:dataExtractionRules`
nor `android:fullBackupContent`; both XML files existed as unmodified templates. On Android 12 and
above `allowBackup="false"` does not cover device-to-device transfer, so cached subscription YAML —
which holds proxy UUIDs and passwords — would be copied to a new device during setup.

**Change made.** Both files are now referenced from the manifest and exclude every domain.

## 7. Diagnostics redaction covered a single literal token — Low

`DiagnosticLogger.sanitizeForLog` replaced exactly one hardcoded subscription token. The debug log
and the appended mihomo stderr can contain proxy UUIDs, passwords, and the runtime controller
secret, none of which were redacted. Logging is `BuildConfig.DEBUG`-only, which bounds the impact to
debug builds — but those logs are copied to the clipboard by a long-press on the connect button and
are therefore likely to be pasted into a support chat.

**Change made.** Redaction now covers the injected payload keys plus credential-bearing field names
(`uuid`, `password`, `secret`, `token`, `psk`, `private-key`, `short-id`) across YAML, JSON and query
strings.

## 8. Broadcast receivers implicitly exported below API 33 — Low

`MainActivity.onStart` and `WhiteDnsTileService.registerStateReceiver` used a bare `registerReceiver`
on pre-Tiramisu devices. `minSdk` is 26, so on Android 8–12 any installed app could broadcast
`Actions.STATE_CHANGED` and drive the UI and quick-settings tile into a false "connected" state.
The broadcast is not used to take privileged action, so this is display spoofing rather than control
— but for a VPN, convincing the user they are protected when they are not is the harmful case.

**Change made.** Both call sites use `ContextCompat.registerReceiver` with `RECEIVER_NOT_EXPORTED`,
which applies a signature-level permission on older releases. The sender already scopes the intent
with `setPackage`.

## 9–11. Repository hygiene — Low

- `local.properties` was tracked, exposing a contributor's home directory path and username. Along
  with `.idea/` and `.kotlin/errors/*.log` it is now untracked and gitignored.
- `gradle.properties` set `org.gradle.java.home` to `/opt/homebrew/Cellar/openjdk@17/...`, which
  fails on every machine that is not that one. Removed; the daemon JDK comes from
  `gradle/gradle-daemon-jvm.properties` and `JAVA_HOME`.
- The diagnostics clipboard copy now sets `ClipDescription.EXTRA_IS_SENSITIVE` so Android 13+ does
  not render the log in the clipboard preview.

`app/google-services.json` contains a Firebase Android API key. That key is designed to be public
and is not treated as a finding; confirm the Firebase project has App Check and per-service rules
enabled so it cannot be replayed.

---

## Reported, deliberately left unchanged

These are real observations, but changing them is a product decision rather than a clear defect, so
they are documented rather than patched.

**12. Scanner sockets proceed when `VpnService.protect()` fails.** `CleanIpScanner.protectBestEffort`
logs `continuingUnprotected=true` and carries on. The existing test
`defaultProbeContinuesWhenSocketProtectionReturnsFalse` asserts this behaviour, so it is intentional.
The consequence is that scan traffic is routed through the tunnel rather than around it — a
measurement-accuracy and routing-loop concern, not a plaintext leak. Failing the probe closed would
trade scan reliability for that; worth deciding explicitly.

**13. Mihomo YAML subscriptions may still set `skip-cert-verify`.** Finding 5 covers the paths where
the app translates links or JSON into a profile. When a user pastes a full Mihomo YAML config it is
stored and executed as-is, so it can still disable certificate validation. Rewriting a user's own
config is intrusive and would break intentional self-signed setups; surfacing a warning in the
subscription UI when an imported profile contains `skip-cert-verify: true` would be the better fix.

**15. Firebase Analytics merges advertising-ID permissions into the APK.** `AndroidManifest.xml`
declares five permissions. The *built* APK requests ten, because `firebase-analytics` contributes
`com.google.android.gms.permission.AD_ID`, `android.permission.ACCESS_ADSERVICES_ATTRIBUTION`,
`android.permission.ACCESS_ADSERVICES_AD_ID`, `com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE`
and `WAKE_LOCK` through manifest merging. This was found by dumping the manifest of a built APK — it
is not visible in the source manifest.

For a censorship-circumvention client this matters more than usual: the app collects a resettable
advertising identifier and install-referrer data that can correlate a user across apps, and the
permission list is what a privacy-conscious user inspects on the store listing. The app only logs
four coarse events (`app_opened`, `vpn_connected`, `connection_try_failed`, `vpn_disconnected`), none
of which need an advertising ID.

Suggested change, not applied because it alters analytics behaviour and is a product call:

```xml
<uses-permission android:name="com.google.android.gms.permission.AD_ID" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_ADSERVICES_AD_ID" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_ADSERVICES_ATTRIBUTION" tools:node="remove" />
<uses-permission android:name="com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE" tools:node="remove" />
```

Pair it with `FirebaseAnalytics.setAnalyticsCollectionEnabled` gated on the existing privacy-policy
acceptance, so nothing is reported before the user consents.

**14. `jniStopProxy` closes `server_fd` without synchronisation.** In `byedpi_bridge.cpp` the stop
path calls `shutdown`/`close` on `server_fd` from the caller's thread while the proxy thread may
still be using it, and `g_proxy_mutex` does not cover that field. This is a file-descriptor reuse
race. It is currently unreachable because `DpiBypassPreferenceStore.isEnabled()` is hardcoded to
`false` — it must be addressed before the ByeByeDPI feature is re-enabled.

---

## What was checked and found sound

- Network security config: cleartext disabled, system trust anchors only (no user CAs), certificate
  pinning on `whitedns.workers.dev` with a pin set that expires 2028-01-01.
- Controller secret: 256-bit `SecureRandom`, freshly generated per runtime, redacted from log tails.
- Component exports: the VPN service is not exported and is permission-guarded; the tile service is
  guarded by `BIND_QUICK_SETTINGS_TILE`; no exported receivers or providers.
- `PendingIntent`s are explicit and `FLAG_IMMUTABLE`; the state broadcast is `setPackage`-scoped.
- Permission set is minimal — no location, storage, contacts, or `QUERY_ALL_PACKAGES`.
- No WebView, no `addJavascriptInterface`, no `Runtime.exec`, no custom `TrustManager` or
  `HostnameVerifier` overrides, no world-readable storage modes.
- Subscription fetch enforces HTTPS and caps the response at 2 MiB.
- DoH/DoT endpoint parsing rejects userinfo, fragments, and out-of-range ports.
- `minifyEnabled` is on for release; release signing is validated before `assembleRelease`.

## Verification

`./gradlew :app:testDebugUnitTest --rerun-tasks` — 147 tests, 0 failures, run clean before and after
the changes. This also exercises manifest processing and resource merging, so the manifest and XML
edits are validated. Four new regression tests cover findings 2–5:

- `MihomoRuntimeConfigBuilderTest.flClashRuntimeYamlBindsTheDnsListenerToLoopbackOnly`
- `UserSubscriptionImporterTest.clashJsonProxyCarryingALineBreakInAKeyIsDropped`
- `UserSubscriptionImporterTest.importedProxiesNeverDisableCertificateVerification`
- `UserSubscriptionImporterTest.xrayJsonAllowInsecureDoesNotDisableCertificateVerification`

Two existing tests were updated because `skip-cert-verify: false` is now emitted explicitly on
vless and trojan proxies, and `WhiteDnsConfigTest` no longer asserts the literal passphrases.

`./gradlew assembleDebug` — debug APKs built for all five splits after compiling the FlClash Mihomo
core for `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`. Dumping the built manifest confirms
`allowBackup=false` alongside both `fullBackupContent` and `dataExtractionRules`, and the
signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` that `ContextCompat.registerReceiver`
relies on. That same dump is how finding 15 was found.

**Not verified here:** a signed release build, and therefore the new `validateReleaseInputs` guard on
missing payload keys — it needs release signing credentials that are not on the audit machine. The
runtime behaviour of findings 2, 3 and 5 against live endpoints also needs one on-device connect.
Run `make release` and a manual connect before merging.

**Windows note:** `:app:buildFlClashCore` invokes `scripts/build-flclash-core.sh` via
`commandLine(...)`, which Gradle cannot exec on Windows. Running the script through `bash` first and
then `assembleDebug -x buildFlClashCore` works. Using `commandLine("bash", script)` would make the
documented build work on Windows too; not changed here as it is outside the audit's scope.
