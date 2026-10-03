# Standalone connection engines

Status: 2026-10-02. AmneziaWG replaces OpenConnect at the user's request. It uses public,
pinned Amnezia sources and requires a user-supplied compatible server configuration.
No private OpenConnect source, JNI controller, authentication UI, CI token or build job
is required. Legacy encrypted OpenConnect records remain readable/deletable and are
explicitly unavailable; they cannot start another engine.

## Engine availability

| Engine | Integration | Local availability |
| --- | --- | --- |
| SSH | Verified host keys, password/private key, standalone TCP SOCKS | Android 8+, all four APK ABIs |
| Psiphon | Automatic/CDN/direct, optional egress region, public signed bootstrap | ARM, ARM64, x86-64; unavailable on x86 |
| Tor | Direct/custom/default bridges, bootstrap, obfs4/Snowflake/Conjure | ARM, ARM64, x86-64; Conjure connectivity remains unverified |
| DNS tunnels | DNSTT/VayDNS through zeddns, MasterDNS controller | ARM, ARM64, x86-64; compatible user server required |
| AmneziaWG | Public v3 core, native VpnService, protected UDP sockets | Android 8+, all four APK ABIs |
| IKEv2 | Android provisioning and separate platform controller | Android 11+ and IPsec tunnel feature |

The Home/Subscriptions connection card and Settings expose Saved engine profiles.
Add/Edit/Delete/Select and profile tests are available. Saving does not select or connect.
AmneziaWG accepts a .conf file selected with Android’s document picker, a pasted vpn://
client link, or a full configuration pasted into the encrypted profile form, including
Jc/Jmin/Jmax, S1-S4, H1-H4, I1-I5 and supported v3 fields. Unknown fields are rejected
rather than silently ignored. Address, DNS, peers with endpoints and AllowedIPs are
required; embedded application lists are rejected because WhiteVPN's app settings apply.
The effective DNS, MTU and routes are shown without keys. Native profiles require VPN
access on Home before selection; saved proxy preferences are not silently rewritten.

## Effective settings

| Setting | Mihomo subscription | SSH/Psiphon/Tor/DNS SOCKS | AmneziaWG | IKEv2 |
| --- | --- | --- | --- | --- |
| Destination routing and encrypted application DNS | Existing controls | Existing controls | From .conf | Platform profile |
| App inclusion/exclusion | Existing controls | Existing controls, own UID exempt | WhiteVPN app settings | Platform policy; no WhiteVPN app controls |
| Proxy-only access and authenticated LAN sharing | Available | Available | Unavailable | Unavailable |
| Automatic selection and chains | Existing controls | Standalone | Standalone | Standalone |
| Location, clean-IP/fronting, TLS integrity probe | Existing controls | Unavailable | Unavailable | Unavailable |
| Mihomo WireGuard override fields | WireGuard candidates only | Unavailable | Configure in .conf | Unavailable |
| UDP through the selected proxy | Protocol dependent | Rejected; TCP-only | Supported by native tunnel | Supported by platform |
| Engine traffic rates | Existing core metrics | Mihomo proxy counters | Native tunnel counters | Explicitly unavailable |

Inactive controls are disabled and explained, while saved global preferences are preserved.
Category and Settings notices describe the selected engine. Native profiles do not display
saved Mihomo DNS/routing values as effective settings. The old WARP/Amnezia noise override
page is labeled as a Mihomo WireGuard override; it is separate from the AmneziaWG backend.

Psiphon direct mode hides unused CDN fields. DNSTT and VayDNS in the pinned zeddns adapter
support UDP, TCP, DoT and DoH resolver transports; MasterDNS supports numeric UDP resolvers
and its actual encryption methods. Tunnel-bootstrap DNS is distinct from application DNS.
Numeric DoT resolver certificates are verified against their IP SAN in a documented local
patch to a copied zeddns tree; the reviewed partner checkout remains unchanged.

Connection-test timeout and download size apply to engine tests. SOCKS profiles can be
tested while stopped or through the matching active route. Native tests require the matching
established native connection and never replace another VPN. Mihomo subscription batch
tests are blocked while an engine session is active; batch concurrency is a Mihomo setting.

## Ownership, routing and security

RouteProfileRef.Mihomo preserves subscription IDs/fingerprints. Versioned engine profiles
have stable UUIDs in a separate Android Keystore AES-GCM vault using AtomicFile. Corruption
or key loss fails closed. IKEv2 certificates use Android KeyChain aliases. Deleting a
selected engine leaves an unresolved selection instead of choosing another route.

BackendSessionPlan separates Mihomo, SOCKS, AmneziaWG and platform IKEv2. Replacement
preflights the target, cancels/joins startup and testers, and requires confirmed shutdown.
Stale callbacks and metrics are rejected by lease and sender UID. Uncertain resource
ownership blocks replacement. Failure does not select a different engine or weaken lockdown.

SOCKS backends use loopback SOCKS consumed by a synthetic Mihomo profile without fallback.
Unsupported proxied UDP is rejected. Explicit user-selected DIRECT routing rules remain
effective. WhiteVPN's UID is exempt to avoid capturing engine/bootstrap sockets; its own
routed tests and application-DNS probes explicitly use Mihomo. SOCKS engines are rejected
under lockdown because the UID exemption is incompatible with that policy.

AmneziaWG applies .conf addresses, routes, DNS and MTU to VpnService, with WhiteVPN app
selection. Native endpoint names are resolved through the physical network before TUN
establishment. Every UDP bind/rebind is protected by VpnService through authenticated
worker IPC before it can receive/send tunnel traffic; failure refuses the bind.

Psiphon/DNS and AmneziaWG run in the private :enginecore process. Their Go runtimes never
load beside Mihomo's libclash runtime in the main process. The worker owns native handles;
shutdown confirms Binder death and VPN network disappearance before the next session.
Only numeric native byte counters cross IPC; native UAPI keys and raw diagnostics do not.
Traffic samples expire after five seconds and clear on shutdown; device/UID counters are
never presented as engine-specific rates.

A started native handle or successful platform request is insufficient for Connected.
Readiness requires routed, certificate-validated HTTPS through WhiteVPN's owned VPN network.
DNS and socket probes are cancellable and have bounded timeouts. IKEv2 additionally requires
Android 13+ platform CONNECTED state; Android 11-12 uses the owned VPN network and HTTPS.
Platform ownership survives process restoration until observed release.

SSH confirms a fingerprint on first use and rejects changed host keys. Tor authenticates
SAFECOOKIE control and couples child lifetime to its authenticated owning connection.
Raw exception/native output is suppressed; diagnostic stages contain no keys, cookies or
credentials. Late/no-op disconnect foreground-service commands are acknowledged before
stopping, preventing Android's foreground startup timeout crash.

## Source pins and builds

Partner sources: ZedSecure 2c01da26bed98b883503850df1b7f793d28f7f54 and its reviewed core
manifest. The archived original manifest documents provenance; it does not enable removed
or unrelated engines. The minimal gomobile library contains only Psiphon, zeddns and
MasterDNS. It excludes Xray/sing-box and retains existing Mihomo.

Amnezia Android parser: v3.1.4, fb7575a54e35d9a19cb1f64cdd90bf7075163b55 (Apache-2.0).
Amnezia Go core: github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260814 (MIT).
The small JNI adapter uses Go 1.26.3, API 26 and NDK 27+. Full licenses, go.sum and pins
are included. Build manifests record adapter and binary SHA-256 hashes.

Public native pipeline:

    python scripts/build-enginecore.py
    NDK="$ANDROID_NDK_HOME" bash scripts/build-tor-engine.sh
    python scripts/build-tor-transports.py
    python scripts/build-amneziawg.py
    python scripts/verify-engine-artifacts.py --require core tor amneziawg

The enginecore script preserves local fork replacements in pinned gomobile using the
documented copied-tool patch. It also copies zeddns, fixes IP-address DoT verification and
runs a regression test rejecting an untrusted TLS resolver before packaging.
The public CI builds all required engines without a private OpenConnect checkout.

Verification checks expected bindings/JNI symbols, required artifacts, ABI, ELF dependencies,
executable permission/headers, checksums and 16 KB segment alignment. Optional artifacts
missing on a device produce a preflight explanation. Source-output and stripped-APK hashes
are recorded separately. Alignment inspection is not a 16 KB-device runtime test.

The developer build uses existing Mihomo artifacts (-x :app:buildFlClashCore); the old local
FlClash checkout does not match the existing script's pin. No source marker was fabricated.
Clean CI fetches that script's pin. Release Kotlin/Java and R8 are checked, but signed
assembleRelease still requires the project's existing signing and payload-secret setup.
Existing R8 Kotlin metadata warnings do not prevent compilation/shrinking.

## Validation and remaining acceptance

Unit coverage includes old selection migration, encrypted-profile codec, capability and
settings matrix, backend plans, AWG parser/v3 fields, retired OpenConnect, stale leases,
shutdown races, counters, UDP rejection, secret redaction and original subscription suites.

Real API 35 tests exercise AmneziaWG's complete VpnService path with a reproducible local
gateway: owned network, routed HTTPS, TCP and UDP echo, 5 MB download, nonzero native
upload/download rates, three full connect/disconnect cycles, runtime isolation and a
Psiphon worker switch. Unreachable peers remain Starting until cancellation or failure;
cancellation and readiness timeout release the network and metrics without engine fallback.
The fixture is outside the APK and generates disposable keys on every run. Build instructions
are in native/amneziawg/test-gateway/README.md.

Previously completed API 35 checks include Psiphon's full VpnService HTTPS/download/counters,
worker cancellation/death and Keystore persistence. SSH's in-process server test covered
SOCKS, credentials, host-key confirmation/change and ten reconnects. Tor direct, default
obfs4 and default Snowflake completed real bootstrap/HTTPS/confirmed shutdown.

Conjure's working-directory persistence bug was fixed, but public phantom TCP connections
still timed out after station registration. A working bridge/network is needed for acceptance.
No compatible user DNS-tunnel or IKEv2 server credentials were supplied.

Remaining device acceptance: Galaxy A17 phone UI/crash behavior, Iranian network performance,
Android 8/10 availability, Android 11/12 IKEv2 observation, other ABIs at runtime, 16 KB device,
Wi-Fi/mobile transitions, revocation/always-on/lockdown, and signed release behavior.
The local Windows emulator repeatedly exits with host access violation 0xC0000005 when
MainActivity opens; headless Android service/JNI tests work. This is not evidence of a
phone app crash fix. The reported original phone crash still lacks a device stack trace.

Infrastructure remains independent: SSH, DNS tunnels, AmneziaWG and IKEv2 require the user's
server/configuration; Psiphon and Tor use their public networks. No ZedSecure servers are
assumed. Server deployment, general engine share/import formats, cross-engine chains and
engine fallback remain outside this change. AmneziaWG file/link import is now supported.

## Previous local artifacts and check results (1.6.4)

Version 1.6.4 (80): build/engines/WhiteVPN-amneziawg-arm64-debug.apk and
build/engines/WhiteVPN-amneziawg-debug.apk. These replace the earlier engine-fixes APKs.
Build identity and SHA-256 values: build/engines/WhiteVPN-amneziawg-apks.json.

All 330 unit tests across 50 suites passed with zero failures/errors/skips. Final debug
builds for four ABIs plus universal, test APK assembly, release Kotlin/Java compilation
and R8 passed (amneziawg-final-validation.log, 4m22s). License notices were subsequently
packaged and the required tasks passed again (amneziawg-final-packaged-notices.log, 42s).

The final API 35 instrumentation run passed all five tests (109.611 seconds) in
amneziawg-final-release-observation-tests.log. Besides AWG routing/counters/cancellation/
timeout, it rechecked full Psiphon VpnService HTTPS/download/counters/disconnect and
a late disconnect without an active service. This run exposed that VPN network
observation can lag native shutdown: Psiphon network disappearance took about 14 seconds.
Release observation now allows 30 seconds. A completed-but-uncertain cleanup can be
retried without treating Stopping as an unconditional no-op; active cleanup remains serialized.
Unconfirmed ownership still blocks replacement.

The copied zeddns DoT regression passed before the final minimal core build. All 19 new
engine artifacts passed source-output ABI/dependency/16 KB/JNI validation. Both final APKs
passed v2 signature and zipalign -c -P 16 checks; all 20 packaged 64-bit libraries in the
universal APK have 16 KB load alignment. The ARM64 APK native hashes match the corresponding
universal entries. Full upstream Amnezia license texts are included in APK assets.
These checks do not replace the remaining phone, Iran-network, policy, IKEv2/DNS server,
Conjure or 16 KB-device acceptance work listed above. Signed release assembly remains
dependent on the existing release signing configuration.

## AmneziaWG imports and consistent engine forms (1.6.5)

The AmneziaWG editor has **Import configuration file** and **Import vpn:// link** actions.
The system ACTION_OPEN_DOCUMENT picker works with phone storage and document providers,
without broad storage permissions. Accepted inputs are UTF-8 .conf text (including BOM
and CRLF), text files containing a vpn:// link, and URL-safe Base64 links with optional
padding. The decoder accepts raw client configurations or Qt qCompress/zlib payloads.
Exported Amnezia JSON containers are supported only when exactly one distinct AWG
last_config.config is embedded. API/subscription/administration-only keys, other protocols,
ambiguous client configurations and unknown configuration attributes are rejected.
No URL is fetched or endpoint resolved during importing.

The qCompress/Base64 wrapper and embedded AWG export structure were checked against
[Amnezia’s import controller](https://github.com/amnezia-vpn/amnezia-client/blob/dev/client/core/controllers/selfhosted/importController.cpp)
and [configuration keys](https://github.com/amnezia-vpn/amnezia-client/blob/dev/client/core/utils/constants/configKeys.h).
Decoded data is capped at 64 KiB, encoded file input is bounded, UTF-8 is strict, JSON
nesting and decompression output are limited, and parser errors never expose keys.
The supplied user link/credentials are not embedded in the app, logs, or test fixtures.

A valid import fills the draft and supplies a default editable name if needed. Save,
selection and connection remain separate actions. Invalid input/cancelled file selection
preserve the current draft. Activity recreation retains drafts only in memory; credentials
are never serialized into saved-state Bundles or unencrypted preferences. Process death
discards unsaved drafts. Saved profiles continue to use the Keystore-encrypted catalog.
Sensitive editor/link windows use FLAG_SECURE and disable autofill/state persistence.

All engine editors, selectors, lists, confirmation/test/trust dialogs now use WhiteVPN’s
Vazirmatn font, light/dark green design tokens, rounded dialog surfaces, outlined fields,
consistent spacing and buttons. Native Android spinners were replaced by styled Material
dropdowns. Dynamic authentication/bridge/DNS choices preserve the draft while showing
only the applicable fields. Data/configuration inputs are LTR; labels and layout follow
the app language. Forms scroll within the available phone/TV screen and retain D-pad access.

Debug-only, non-exported EngineFormsTestActivity hosts UI regression checks without
starting a VPN or contacting a subscription/server. Its FileProvider exposes only the
cache/engine-import-tests directory; neither host nor provider is included in release.
Instrumentation screenshots contain empty forms or generated test credentials only.

### Verified import/UI delivery

Version **1.6.5 (81)**: build/engines/WhiteVPN-engine-imports-arm64-debug.apk
(Galaxy A17/other ARM64 phones) and build/engines/WhiteVPN-engine-imports-debug.apk
(universal). Identity/checksums are recorded in WhiteVPN-engine-imports-apks.json.
These supersede the 1.6.4 APKs above for file/link import and form styling.

All **338 unit tests in 51 suites** pass (zero failures/errors/skips), including eight
import regression tests. Final debug packaging for all four ABIs plus universal and
Android test APK assembly passed in engine-link-dialog-fix-build.log.

All **five API 35 form/import instrumentation tests** pass in 46.038 seconds
(engine-imports-api35-acceptance.log): file-provider content, in-memory retention across
activity recreation, cancellation preservation, invalid/valid link input followed by
encrypted save without selection/connection, dynamic engine options, all six engine
forms in English/light and Persian/dark, and actual system document-picker launch.
Dialog surfaces, text colors/fonts, outlined fields and secure editor windows are checked.
Seventeen screenshots were inspected/captured using the debug-only software-rendered
host; this does not replace full MainActivity/phone acceptance. Persian URL/file extension
literals use directional isolation so vpn:// and .conf display in their correct order.

The tests exposed and fixed a link-dialog ClassCastException: Material reparents its
EditText under an input FrameLayout; error messages now target the ancestor TextInputLayout.
They also found a palette inconsistency after theme changes. Engine form/dialog colors
now honor the saved app theme explicitly. Material dropdowns receive the outlined box
background instead of the inherited underlined EditText background.

Both downloadable APKs pass v2 signature and zipalign -c -P 16 checks. All 19 required
engine native artifacts pass verification inside the universal APK
(engine-imports-native-apk-verification.log / engine-imports-native-manifest.json).
Every native library in the ARM64 APK is byte-identical to the 1.6.4 ARM64 APK; this update
changes import/UI code and resources rather than connection-engine native payloads.
The emulator was stopped after acceptance. No connection was made using the user's link,
and its credentials were not saved in the app, repository, build logs or test fixtures.

Final release Kotlin/Java compilation and R8 passed in 4m42s
(engine-imports-final-release-check.log). A final resource-only packaging pass shortened
the English IKEv2 EAP label to fit its selector and passed in 1m11s
(engine-imports-final-label-packaging.log). All 45 DEX/native ZIP entries in the final
universal APK are byte-identical to the UI-tested APK. Both final copies were checked
again for signatures/alignment and their final SHA-256 values recorded. Release manifests
exclude the debug-only form activity/FileProvider. Signed release assembly still depends
on the project's existing signing configuration.


### Long secret fields and password visibility (1.6.6)

The shared engine input previously forced LTR layout direction inside an RTL
TextInputLayout. In Persian, Material's end-icon dummy drawable reserved the right
side while the eye icon occupied the left, allowing long password text underneath it.
The new regression fails on the 1.6.5 APK with a 56-pixel overlap in the Persian SSH
password field (password-spacing-old-apk-regression.log).

Inputs now inherit the container's layout direction, so Material reserves icon space
on the correct physical side. Technical values retain LTR text direction and text-start
alignment. START gravity remains in place for localized hints, keeping Persian floating
labels aligned with their outline cutouts. No fixed icon-width padding or credential
truncation is introduced. Password visibility, cursor selection, raw values, application
fonts/colors and secure editor windows are preserved.

The new regression covers ten secret input variants: SSH password/private key/key
passphrase, DNSTT/VayDNS SOCKS password, MasterDNS encryption key, IKEv2 password/PSK,
AmneziaWG multiline configuration and the vpn:// import dialog. It uses long generated
strings, checks actual text viewport/icon bounds and unchanged values when hidden,
revealed and hidden again, in English/light and Persian/dark (60 spacing checks).
All six form/import instrumentation tests pass on API 35 in 67.89 seconds
(password-spacing-final-api35-acceptance.log). Final hidden/revealed screenshots were
retrieved and visually reviewed; credentials in them are generated test data only.
The debug-only software-rendered host continues to isolate these checks from VPN startup.

Final debug/test APK assembly passed in 1m37s
(password-spacing-final-alignment-build.log). Version **1.6.6 (82)** is available as
build/engines/WhiteVPN-password-spacing-arm64-debug.apk and
build/engines/WhiteVPN-password-spacing-debug.apk. Their final SHA-256 values are recorded
in WhiteVPN-password-spacing-apks.json. Both pass v2 signature verification and
zipalign -c -P 16 4 (password-spacing-final-apk-verification.json).
All DEX entries in both delivered APKs match the UI-tested x86_64 APK. All 35 universal
native libraries (10 in ARM64) are byte-identical to the 1.6.5 delivery
(password-spacing-payload-verification.json). The emulator was stopped after validation.

Final release Kotlin/Java compilation and R8, together with refreshed unit checks,
passed in 3m39s (password-spacing-final-release-checks.log). All 338 unit tests in
51 suites pass with zero failures/errors/skips. The existing signing configuration
is still required for signed release APK assembly; the delivered APKs use debug signing.


## Unified profiles and Psiphon country selection (1.6.7)

The former Subscriptions tab is now **Profiles**. Built-in/user Mihomo sources and
saved engine profiles appear in one list with one selected badge. The Add action
offers a subscription/connection link or SSH, Psiphon, Tor, DNS tunnels, IKEv2 and
AmneziaWG. Engine cards offer tests and Edit/Delete options, and report device/library
unavailability. Subscription Connections still opens the existing node/test picker;
a selected Mihomo source also exposes its location filter under Options.

Provisioning and engine selection have been removed from the home page, connection
node picker and settings index. The home page has one selected Profile row linking
to Profiles. The VPN/Proxy preference has moved to Connection settings. Saving a
new engine profile updates the list without selecting or connecting it. Selecting
a saved profile returns to the VPN tab; the user starts a connection explicitly.
Selecting the remembered Mihomo source clears the standalone-engine reference even
when its subscription ID has not changed. Existing source/node fingerprints and
chain preferences remain intact. Chains and automatic node selection stay Mihomo-only.

The Psiphon exit-country field is a non-editable Material dropdown, with Automatic
first and country names sorted in the app language. Existing lowercase codes are
canonicalized, and a previously saved country is retained even if absent from the
latest advertised list. Changing Psiphon mode preserves the country choice.
The initial catalogue comes from the public
[Psiphon Android region selector at c2c043da](https://github.com/Psiphon-Inc/psiphon-android/blob/c2c043da1208b34d28c3fdeef72b43dcd470dacb/app/src/main/java/com/psiphon3/psiphonlibrary/RegionListPreference.java).
Active core onAvailableEgressRegions notices update bounded non-secret metadata in
a no-backup AtomicFile, read afresh across engine/UI processes. The catalogue does
not guarantee that every country is reachable from every network.

A second non-exported debug host exercises the real MainActivity navigation with
software rendering; it disables only the live startup update check. Production
MainActivity keeps hardware acceleration and its existing update behavior. Both
debug hosts are excluded from release. The document-picker regression now waits
for the form host to resume after Back, avoiding a delayed Back action affecting
the next navigation test.

The selected bottom tab is retained across activity recreation using an integer
Bundle field. Unsaved engine drafts remain in memory, as before; their credentials
are never added to that Bundle. The real-navigation regression recreates the
activity with a Psiphon form open, verifies the draft/name and Profiles tab, then
saves without connecting. Scrolling profile content is clipped outside the status
bar/cutout inset. Compact cards omit optional action icons to retain readable
single-line labels; unavailable engine tests are visibly disabled. Shared card
text uses existing tokens on the existing surface color (minimum checked text
contrast 4.58:1 in light mode), including selected badges and action buttons.

The API 35 real Psiphon regression passed both tests in 40.698 seconds
(unified-profiles-psiphon-live-regression.log): routed HTTPS, a 5 MB download,
nonzero upload/download samples, confirmed disconnect/closed local proxy, and
a subsequent disconnect with no active VPN. This automatic-region test did not
produce a nonempty cached region advertisement; the initial catalogue remains
the fallback. No claim is made that each country was live-tested.


### Final verification and delivery

Version **1.6.7 (83)** is delivered as:

- `build/engines/WhiteVPN-unified-profiles-arm64-debug.apk` for Galaxy A17 and other ARM64 phones.
- `build/engines/WhiteVPN-unified-profiles-debug.apk` for all four supported ABIs.

File sizes and SHA-256 identities are recorded in `WhiteVPN-unified-profiles-apks.json`.
Both final APKs pass v2 signature verification and `zipalign -c -P 16 4`
(`unified-profiles-final-apk-verification.json`). Their DEX entries match the final
UI-tested APK. The 10 ARM64 and 35 universal native libraries are unchanged from
the 1.6.6 delivery (`unified-profiles-payload-verification.json`). Native core
rebuilding was excluded for this UI-only change; existing native artifacts were verified.

All **344 unit tests in 52 suites** pass, with zero failures, errors or skipped tests.
Final debug and test APK assembly passed in 3m23s
(`unified-profiles-final-state-retention-build.log`). Release Kotlin/Java compilation,
R8 and refreshed unit checks passed in 5m2s
(`unified-profiles-final-release-checks.log`, result exit 0). The final merged release
manifest excludes both debug test activities and their debug FileProvider. These
delivered APKs are debug-signed; signed release assembly requires the existing
project signing configuration.

The seven form/import instrumentation regressions pass on API 35 in
`unified-profiles-final-state-retention-ui-tests.log`. That combined run exposed
a navigation-test harness issue: the test clicked a partially visible home row
without scrolling. After correcting that test interaction, the final targeted
navigation and country-dropdown rerun passed **both tests in 40.98s**
(`unified-profiles-final-navigation-country-acceptance.log`). All eight unique
UI regressions therefore pass across these final runs, including the open-form
recreation/draft/tab check, save without selection/connection, returning home on
selection, switching back to the remembered Mihomo source, localized dropdown
labels, and preservation of the selected country.

Responsive navigation/country checks passed at **320, 375, 414 and 768 dp** in
English/light and Persian/dark (`unified-profiles-responsive-results.json`). Actual
screenshots at these widths were reviewed. This responsive run preceded only the
final tab-state retention change; the layout and renderer are unchanged. Live
Psiphon validation above also precedes that tab-only change; its backend is unchanged.

Emulator validation does not establish Galaxy A17 device acceptance, all Android
versions/ABIs, every exit country, mandatory VPN policies or 16 KB-page runtime
compatibility. APK alignment validation is separate from those device checks.


## Current-main integration and beta channel (1.7.0-beta.1)

The engine/UI work is integrated on a separate codex/connection-engines-beta branch
from main at 1f07af01b36013c701060df5621cebb43e36ab4a (1.6.10, code 85).
The candidate uses versionName 1.7.0-beta.1 and versionCode 86. Earlier 1.6.x
artifact descriptions above record local development deliveries, not replacements
for the existing GitHub release tags. Current main and stable releases are preserved.

The merge retains patched Mihomo 1.19.30/Go 1.26.8, strict verified runtime health,
startup repair policy, update downloads/provider, widgets, network failure handling,
and public/private built-in subscription provisioning. The Profiles list renders
every configured built-in source. Explicit engine selection never falls back to a
Mihomo source. The platform IKEv2 controller refreshes widgets, whose commands use
the route dispatcher and live state rather than trusting cached connection state.
The debug import provider has a separate class so manifest merging preserves the
production update provider. Both remain non-exported.

A reusable native-build workflow is a required dependency of PR/debug/release builds.
It builds pinned minimal core, Tor/transports and AmneziaWG and transfers verified
artifacts to the Android job. APK contents are checked before uploading or attaching
builds. The Mihomo cache only covers Mihomo outputs, preventing restoration of older
engine binaries over freshly built ones. Native Go compiler pins match current main.
Beta tags must match the app version and the GitHub Release must be Pre-release.

Initial integrated verification: 374 unit tests in 55 suites, no failures/errors/skips,
plus debug/release Kotlin compilation passed in 6m36s. Source/native/full packaging,
UI and signed release validation continue; these initial results alone do not
establish full release or device acceptance.
