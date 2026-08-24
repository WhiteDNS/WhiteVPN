# WhiteVPN

WhiteVPN resolves subscriptions into usable upstream routes and governs how device or proxy-client traffic enters those routes. This glossary separates concepts currently overloaded by words such as connection, VPN, mode, runtime, health, and selection.

## Connection lifecycle

**Connection Lifecycle**:
The progression from a connect request through establishment, an optional Connection Session, and confirmed release.
_Avoid_: VPN lifecycle, service lifecycle, runtime lifecycle

**Connection Session**:
A continuous period in which WhiteVPN owns an accepted data plane in either Tunnel Access or Proxy-Only Access. It begins at Session Readiness and ends only after Release Completion.
_Avoid_: VPN session, connection, runtime

**Connect Request**:
A request to establish a Connection Session. Acceptance of the request is not proof that establishment succeeded.
_Avoid_: Connect event, start VPN

**Establishment Attempt**:
One bounded effort to establish a data plane from a Session Plan. Several Route Candidates may be tried before the attempt succeeds or fails.
_Avoid_: Connection Session, startup

**Replacement Attempt**:
An effort to replace an existing session's data plane or Active Route. A failed replacement does not end the session when the previous accepted data plane remains available.
_Avoid_: Refresh, reconnect attempt

**Lifecycle Phase**:
The current lifecycle condition: Inactive, Establishing, Active, or Releasing. Operation Failure, Data Plane Ownership, and Route Health are independent facts.
_Avoid_: VPN state, service state, health state

**WhiteVPN Data Plane**:
The traffic-carrying capability provided through a device tunnel, proxy entries, and an upstream route.
_Avoid_: Runtime, core, connection

**Data Plane Ownership**:
Whether WhiteVPN is known to retain traffic-carrying resources: Absent, Owned, or Uncertain.
_Avoid_: Started, stopped, connected

**Session Readiness**:
The point at which the requested data plane is installed and its configured acceptance checks have passed. It is not proof that every application or destination can reach the internet.
_Avoid_: Internet works, connected

**Route Health**:
A time-bounded observation of the Active Route: Unknown, Healthy, Unhealthy, or Recovering. It is independent of Lifecycle Phase and Data Plane Ownership.
_Avoid_: VPN state, readiness, internet proof

**Operation Failure**:
An unsuccessful establishment, replacement, route switch, recovery, or release, together with its stage and resulting Data Plane Ownership.
_Avoid_: Error state

**Disconnect Request**:
A request to end a Connection Session. It may be rejected by policy and is not equivalent to being disconnected.
_Avoid_: Disconnected, stop event

**Release**:
The process of returning all data-plane ownership. Release is complete only when Data Plane Ownership is Absent.
_Avoid_: UI disconnect, stop request

**Release Completion**:
Confirmation that WhiteVPN no longer owns any traffic-carrying resource from the session.
_Avoid_: Disconnect timeout, stopped display

**Recovery**:
A policy-driven Replacement Attempt triggered by unhealthy routing or an environmental change.
_Avoid_: Refresh, retry

**Route Rotation**:
A Replacement Attempt that deliberately excludes the current route or endpoint while choosing another.
_Avoid_: Refresh VPN

**Live Route Switch**:
A change of Active Route without rebuilding the accepted data plane. A successful live switch preserves the Connection Session.
_Avoid_: Reconnect, refresh

## Platform policies and traffic entry

**Always-on Policy**:
A platform policy requiring WhiteVPN to be started or restored automatically. It expresses desired activation, not Session Readiness.
_Avoid_: Kill switch, active session

**Lockdown Policy**:
A fail-closed platform policy, presented as the kill switch, that blocks direct device traffic while the required tunnel is unavailable.
_Avoid_: Always-on VPN, connected state

**Device Access Preference**:
The user's preferred way for device traffic to enter WhiteVPN: Tunnel Access or Proxy-Only Access.
_Avoid_: Connection mode, current mode

**Effective Device Access**:
The access used by an establishment attempt after mandatory platform policies are applied. It may differ from the Device Access Preference.
_Avoid_: Selected mode, requested mode

**Tunnel Access**:
A device tunnel automatically admits traffic from eligible applications into WhiteVPN.
_Avoid_: VPN mode, TUN mode

**Proxy-Only Access**:
No device tunnel admits application traffic; only clients explicitly configured for a Proxy Entry enter WhiteVPN.
_Avoid_: Proxy mode, local VPN

**Proxy Entry**:
An HTTP or SOCKS endpoint through which an explicitly configured client enters WhiteVPN.
_Avoid_: Tunnel, VPN endpoint

**LAN Proxy Access**:
Optional access to a Proxy Entry from private-network clients, with authenticated or unauthenticated use.
_Avoid_: Share VPN on LAN, LAN tunnel

**App Tunnel Policy**:
The rule deciding which eligible applications enter Tunnel Access: All Apps, Exclude Selected Apps, or Only Selected Apps.
_Avoid_: Split-tunnel mode, Off, VPN apps

**Eligible App**:
An installed application that the device permits WhiteVPN to include in or exclude from Tunnel Access.
_Avoid_: Launchable app

## Subscriptions and route profiles

**Subscription**:
A named definition that WhiteVPN resolves into a usable snapshot of route choices.
_Avoid_: Source, config, catalog, profile list

**Subscription Source**:
The origin of subscription content: WhiteVPN-managed, remote HTTPS, or inline user content.
_Avoid_: Input, URL, subscription

**Subscription Snapshot**:
The most recent valid resolved version of a Subscription. A prior snapshot may remain usable when refresh fails.
_Avoid_: Cache, raw config, subscription

**Configured Subscription**:
The Subscription chosen for ordinary, non-chained establishment attempts. It is not necessarily the source of every profile in an Active Route.
_Avoid_: Active subscription, current subscription

**Route Catalog**:
The Route Profiles exposed by one Subscription Snapshot.
_Avoid_: Subscription, global catalog

**Route Profile**:
A logical, selectable upstream transport definition from a Route Catalog. It may depend on more than one underlying relay.
_Avoid_: Connection, server, node, proxy

**Profile Fingerprint**:
An opaque token used to find a Route Profile again within its Subscription. It is not globally unique, permanently stable, or a security fingerprint.
_Avoid_: Profile ID, global fingerprint

**Profile Reference**:
The combination of Subscription identity and Profile Fingerprint that unambiguously identifies a desired Route Profile.
_Avoid_: Bare fingerprint, profile name

**Route Preference**:
The user's desired ordinary route choice: either one fixed Profile Reference or automatic choice constrained by profile filters.
_Avoid_: Selection, active connection

**Profile Region**:
The declared or inferred country associated with a Route Profile and used before establishment for filtering.
_Avoid_: Location, egress country

**Egress Country**:
The country observed for traffic after an Active Route has been established.
_Avoid_: Profile region, location label

**Subscription Refresh**:
An attempt to replace a Subscription Snapshot with newly resolved content. It is not a connectivity lifecycle operation.
_Avoid_: Route refresh, reconnect

## Route construction and alternate endpoints

**Route Candidate**:
A Route Profile or Route Chain eligible for an Establishment Attempt.
_Avoid_: Connection, server candidate

**Session Plan**:
The fully resolved device-access, application, routing, DNS, sharing, and Route Candidate choices for one Establishment Attempt.
_Avoid_: Runtime config, settings, partial plan

**Active Route**:
The Route Profile or Resolved Chain currently carrying proxied traffic for a Connection Session.
_Avoid_: Active connection, selected server

**Route Chain**:
An ordered route containing at least two Chain Hops.
_Avoid_: Connection chain, proxy list

**Chain Hop**:
One logical Route Profile occupying a position in a Route Chain. A hop may itself expand into dependent relays.
_Avoid_: Server, socket, physical relay

**Chain Position**:
One of Pre-Base, Base, or Post-Base, ordered from the client side toward the destination.
_Avoid_: Before, After, slot

**Hop Preference**:
The desired value for a Chain Position: disabled, automatically chosen, or one fixed Profile Reference.
_Avoid_: Hop selection

**Resolved Chain**:
The concrete compatible sequence produced after all automatic Hop Preferences have been resolved.
_Avoid_: Chain settings, runtime configuration

**Profile Endpoint**:
The network address and port originally supplied by a Route Profile.
_Avoid_: Profile, server identity

**Fronting Endpoint**:
A user-supplied alternate address, optionally with a forced port, tried in place of Profile Endpoints while preserving their logical identity. Original endpoints remain the fallback when overrides fail.
_Avoid_: Fronting IP, clean IP, validated endpoint

**Managed Endpoint Candidate**:
An alternate address and port from WhiteVPN's managed endpoint pool. The product label Clean IP does not guarantee safety, reachability, TLS validity, or successful session establishment.
_Avoid_: Working IP, validated IP

**Endpoint Assessment**:
Time-stamped reachability or quality observations for an alternate endpoint.
_Avoid_: Endpoint Candidate, validation guarantee

## Routing and DNS

**Routing Policy**:
The rule set deciding whether traffic already admitted into WhiteVPN uses Direct Egress or the Active Route.
_Avoid_: Routing mode, device access mode

**Subscription Routing**:
A Routing Policy supplied by the configured Subscription Snapshot.

**Iran-Direct Routing**:
A Routing Policy that sends Iranian destinations through Direct Egress and other admitted traffic through the Active Route.
_Avoid_: Iran bypass

**All-Through Routing**:
A Routing Policy that sends all traffic already admitted into WhiteVPN through the Active Route.
_Avoid_: Global proxy, all device traffic

**Direct Egress**:
Traffic leaving through the device's underlying network without using a Route Profile.
_Avoid_: Bypass connection, active route

**DNS Transport Policy**:
The upstream transport used for DNS handled by WhiteVPN: Managed Encrypted DNS, custom DoH, or custom DoT. It does not by itself state how client DNS entered WhiteVPN or which egress carries resolver traffic.
_Avoid_: DNS privacy mode, automatic DNS

**Managed Encrypted DNS**:
WhiteVPN's managed choice of encrypted upstream DNS transports.
_Avoid_: Automatic DNS

**DNS Egress**:
The Direct Egress or Active Route used to reach an upstream DNS resolver.
_Avoid_: DNS mode, DNS transport

## Observation and diagnostics

**Egress Reachability Probe**:
A bounded HTTPS request through one Route Candidate or Active Route to one probe destination. Success is evidence only for that path, destination, and time.
_Avoid_: Health check, internet proof, tunnel health

**Profile HTTPS Delay**:
The elapsed time observed for an HTTPS probe through one Route Profile. It is not ICMP latency, round-trip time, or whole-device tunnel performance.
_Avoid_: Ping, VPN latency, connection delay

**Profile Download Throughput**:
The rate observed while downloading a bounded payload through one Route Profile.
_Avoid_: Internet speed, VPN speed, bandwidth

**Profile Test Run**:
A delay or throughput measurement run against one or more Route Profiles, independent of whether a Connection Session is active.
_Avoid_: Test session, VPN test, session health
