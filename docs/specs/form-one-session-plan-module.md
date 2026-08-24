# Form one Session Plan module

## Problem Statement

WhiteVPN currently assembles one connection establishment across a large service method. Subscription Snapshot, Route Candidate, fronting endpoint, Split Tunnel policy, routing, DNS privacy, LAN sharing, Connection Mode, TUN ownership, controller selection, and retry settings are passed or reread at different stages. Mihomo runtime generation also accepts nine separate values rather than the resolved intent of the Establishment Attempt.

Automatic-bridge and DPI fallback can execute several runtime attempts for one Connect Request. Because those attempts reread mutable preferences, one Establishment Attempt can combine values from different preference versions. The service is therefore both the Android adapter and the owner of planning rules, while tests observe isolated helpers instead of the complete resolved input passed to runtime generation.

## Solution

Form one in-process Session Plan module. At the start of concrete establishment, the Android adapter captures the current Subscription Snapshot and relevant preferences once. A pure planner combines that immutable input with the requested Route Candidate, fronting endpoint, Split Tunnel plan, and retry variant into a Session Plan. The plan contains the complete runtime YAML and resolved settings needed by Mihomo runtime generation and later establishment decisions.

Mihomo runtime generation accepts the Session Plan rather than a scalar parameter list. Existing Subscription Snapshot, connection-chain, location, connection-type, fronting, split-tunnel, routing, DNS, LAN, and selection policy modules remain authoritative for their own rules. Controller I/O, health validation, fallback execution, native Core ownership, and lifecycle publication remain outside the planner. Profile Test Run remains an independent operation and uses a separate narrow runtime input.

## User Stories

1. As a WhiteVPN user, I want one Connect Request to use a coherent set of preferences, so that retries cannot mix old and new settings.
2. As a WhiteVPN user, I want the selected Subscription Snapshot fixed for the Establishment Attempt, so that route selection and generated runtime refer to the same catalog and YAML.
3. As a WhiteVPN user, I want explicit Profile selection preserved in the Session Plan, so that runtime selection honors my chosen Profile.
4. As a WhiteVPN user, I want automatic connection-type filters preserved in the Session Plan, so that fallback candidates remain within my selected types.
5. As a WhiteVPN user, I want an explicit country choice preserved in the Session Plan, so that automatic selection cannot silently broaden its location.
6. As a WhiteVPN user, I want a Route Chain hop to use the same resolved settings as an ordinary route, so that chaining does not create a second configuration path.
7. As a WhiteVPN user, I want fronting endpoint overrides applied before runtime generation, so that the generated Mihomo document and reported endpoint agree.
8. As a WhiteVPN user, I want an explicitly configured fronting port retained, so that fronting does not discard the configured endpoint port.
9. As a WhiteVPN user, I want connection options applied once to the Subscription Snapshot YAML, so that retry behavior does not depend on later preference changes.
10. As a WhiteVPN user, I want Split Tunnel policy carried unchanged into runtime generation and TUN establishment, so that application routing remains coherent.
11. As a WhiteVPN user, I want Routing Mode carried unchanged into runtime generation, so that a retry cannot switch routing policy.
12. As a WhiteVPN user, I want DNS privacy mode and endpoints captured together, so that generated DNS settings cannot combine values from different edits.
13. As a WhiteVPN user, I want LAN sharing captured once, so that listeners and authentication are generated from one decision.
14. As a WhiteVPN user, I want Always-on and Lockdown policy reflected in Effective Device Access, so that a Proxy preference cannot suppress required Tunnel Access.
15. As a WhiteVPN user, I want Proxy-Only Access to avoid starting TUN when Android policy does not require it.
16. As a WhiteVPN user, I want DPI bypass included only in the retry variant that requested it, so that ordinary and fallback runtime documents remain distinct.
17. As a WhiteVPN user, I want automatic bridge eligibility resolved from the selected route mode, so that explicit, filtered, fronted, chained, or country-specific attempts are not bridged automatically.
18. As a WhiteVPN user, I want automatic bridge fallback to reuse the same captured preferences, so that disabling the bridge does not change unrelated settings.
19. As a WhiteVPN user, I want quick-speed eligibility resolved consistently with automatic selection eligibility, so that it does not run for explicit or constrained routes.
20. As a maintainer, I want runtime generation to consume one immutable Session Plan, so that adding a runtime setting does not widen a long argument list at every caller.
21. As a maintainer, I want one pure planning seam, so that planning behavior can be tested without Android, network, controller, or native Core I/O.
22. As a maintainer, I want existing deep policy modules called by the planner, so that Session Plan does not duplicate connection policy.
23. As a maintainer, I want retry-specific changes represented as pure planning inputs, so that retries do not reopen preference stores.
24. As a maintainer, I want Profile Test Run kept separate from Connection Session planning, so that diagnostic execution does not acquire connection lifecycle semantics.
25. As a maintainer, I want live controller selection, delay probes, health validation, and release behavior outside Session Plan, so that the planner remains deterministic.

## Implementation Decisions

- The module and canonical immutable value are named Session Plan, matching the existing domain term.
- The external test seam is a pure `SessionPlanner.resolve` operation over a request and one captured preference snapshot. No interface with a single implementation is introduced.
- The Android service remains the production adapter. It reads mutable preference stores once before automatic-bridge and DPI retry orchestration and supplies immutable values to the planner.
- A Session Plan is produced for a concrete runtime attempt. Retry variants may change only explicit retry inputs, such as bridge allowance or DPI bypass; they reuse the same captured preference snapshot and Subscription Snapshot.
- The Session Plan retains the lossless Subscription Snapshot and the fully patched Mihomo runtime YAML. It does not introduce a protocol-neutral proxy model.
- DNS mode, DoH URL, and DoT endpoint are one immutable DNS runtime setting in the plan.
- Effective Device Access is resolved before runtime startup. Always-on or Lockdown forces Tunnel Access; otherwise the stored Connection Mode determines Tunnel Access or Proxy-Only Access.
- Fronting, connection-option, DPI-bypass, automatic-selection, and automatic-bridge transformations delegate to the existing policy and patcher modules.
- Automatic selection, bridge eligibility, quick-speed eligibility, and initial selector selections are derived once by the planner for the concrete attempt.
- Mihomo runtime generation accepts a Session Plan and reads runtime YAML, Split Tunnel, LAN, routing, DNS, and selector values from it.
- Profile Test Run uses a separate narrow runtime input because it does not create a Connection Session and must not satisfy Session Plan invariants artificially.
- Controller startup, selector verification, adaptive fallback, connectivity validation, delay persistence, TUN setup, lifecycle state, and cleanup remain execution concerns outside the planner.
- Subscription refresh, cache, fallback, and compilation remain owned by Subscription Snapshot resolution and occur before Session Plan construction.
- Route Chain construction and candidate ordering remain owned by the existing chain and selection policy modules. The plan receives their resolved inputs rather than reimplementing them.
- No new dependency, repository layer, factory, or persistence schema is introduced.

## Testing Decisions

- Planner tests use the production `SessionPlanner.resolve` seam with literal immutable requests and captured preferences.
- The first tracer proves that fronting, connection options, DNS, LAN, routing, effective access, automatic selection, bridge output, and selector preselection appear together in one plan.
- Focused cases cover explicit Profile and connection-type constraints disabling automatic bridge and quick-speed behavior.
- Focused cases cover Always-on or Lockdown forcing Tunnel Access despite a Proxy connection preference.
- Focused cases cover bridge-enabled and bridge-disabled retry plans built from the same captured preferences.
- Plan-to-runtime document mapping coverage proves that the Session Plan supplies runtime YAML, Split Tunnel, LAN, routing, DNS, and selector values; existing runtime-builder tests cover how those values generate Mihomo files.
- Existing Split Tunnel, connection-chain, connection-mode, Mihomo-selection, fronting, and runtime-builder tests remain prior art for their deep policy modules.
- Tests do not mock the policy modules called by the pure planner. Literal Subscription Snapshots and their resulting plan are the independent source of truth.
- The focused planner test runs red before implementation, then the focused planner and runtime-builder tests run green before the full debug JVM unit suite.
- Android/emulator validation is not required for this in-process refactor unless compilation or existing instrumentation reveals an Android adapter regression. Unit and build proof do not claim live tunnel, DNS, LAN, or network validation.

## Out of Scope

- Redesigning Connection Lifecycle, Data Plane ownership, release, observation, persistence, or broadcasts.
- Changing Subscription Snapshot loading, compilation, freshness, cache, fallback, or persistence.
- Replacing Mihomo YAML with a neutral proxy model.
- Rewriting Connection Chain, Split Tunnel, location, connection-type, routing, DNS, LAN, fronting, or selector policy internals.
- Changing controller selection, adaptive fallback, health checks, delay measurement, TLS validation, or native Core behavior.
- Queuing or persisting Session Plans across process death.
- Introducing a public planning service, dependency-injection framework, or speculative runtime-engine abstraction.
- Treating Profile Test Run as a Connection Session.
- Changing user-facing settings, copy, or navigation.

## Further Notes

- The deletion test holds: removing Session Plan would redistribute coherent preference capture and runtime-input assembly across ordinary connection, Route Chain, automatic-bridge fallback, DPI fallback, replacement, and rotation paths.
- The module gains depth from one immutable output used by both runtime generation and establishment execution while keeping Android reads and execution I/O outside its pure seam.
- Tracker publication is pending repository issue-tracker setup and valid GitHub authentication. The repository copy is the implementation and review source until publication succeeds.
