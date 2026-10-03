# Local AmneziaWG integration fixture

This is disposable test infrastructure, not a deployable VPN server and not an APK dependency.
It uses the pinned public AmneziaWG core and its MIT netstack adapter, with gVisor from go.mod.
The helper copies the adapter into the ignored build directory, exposes Stack(), and disables
HandleLocal so encrypted overlay packets can be forwarded. The upstream license travels with
that copy. The fixture main.go is released under MIT (LICENSE).

Build: python scripts/build-amneziawg-test-gateway.py

Run the resulting gateway from build/engines/amnezia-test-gateway. It generates fresh random
client and server keys for every run, listens on UDP 55182, and writes client.conf privately.
The config targets the standard Android emulator host address 10.0.2.2. The overlay supplies
TCP and UDP echo on 10.55.0.1:4444 and DNS on 10.55.0.1:53 (forwarded through Cloudflare DoH).
TCP and UDP forwarding use the host's physical Internet connection. Do not expose this fixture
to untrusted networks. Stop the owned process after tests and discard client.conf.

For an authorized test emulator with VPN consent granted, push client.conf to the app's
cache/awgfixture.conf via adb run-as, then run AmneziaVpnInstrumentedTest. No credentials or
fixture configs are bundled in either APK. Without an external fixture the tests are skipped.
Tests check routed HTTPS, TCP, UDP, actual native counters, repeated disconnect, worker runtime
isolation, switching to Psiphon, cancellation, and readiness timeout cleanup.
