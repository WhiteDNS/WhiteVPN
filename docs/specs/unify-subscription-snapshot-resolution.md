# Unify Subscription Snapshot resolution

## Problem Statement

WhiteVPN resolves its WhiteVPN-managed Subscription and user-defined Subscriptions through different code paths. Each path currently makes its own decisions about HTTPS loading, encrypted payloads, compilation, freshness, cache validity, last-known-good fallback, failure recording, and persistence. User Subscription source kind is inferred repeatedly from free-form text, while metadata and canonical Mihomo YAML are persisted separately and interpreted by multiple callers.

This makes Subscription Snapshot behavior depend on which caller and Subscription Source happened to be used. A refresh fix in one path does not automatically protect the others, and tests exercise helper functions instead of the interface used by connection establishment.

## Solution

Deepen one Subscription Snapshot module. Persisted Subscription resolution sits behind a small resolver interface: a caller supplies a Subscription identity and refresh policy and receives a resolved, lossless Mihomo Subscription Snapshot plus its resolution origin. The module also exposes its internal source loader and compiler to pre-persistence add, update, and test operations. It owns typed Subscription Sources, bounded HTTPS loading, managed-payload decryption, compilation through the existing importer, cache validation, one freshness policy, last-known-good fallback, failure recording, and snapshot persistence ordering.

The module keeps canonical Mihomo YAML rather than introducing a protocol-neutral model. Existing default and user persistence formats remain readable through one production adapter, while newly saved user metadata records an explicit source kind and a dedicated fetched timestamp. Legacy records infer source kind during migration and persist the inferred kind when their metadata can be rewritten.

## User Stories

1. As a WhiteVPN user, I want the configured Subscription to resolve consistently, so that connection behavior does not depend on whether the Subscription is managed or user-defined.
2. As a WhiteVPN user, I want a fresh valid Subscription Snapshot reused without a network request, so that connection establishment is fast and resilient.
3. As a WhiteVPN user, I want a stale Subscription Snapshot refreshed, so that route choices remain current.
4. As a WhiteVPN user, I want the last-known-good Subscription Snapshot retained when refresh fails, so that a temporary source outage does not remove usable routes.
5. As a WhiteVPN user, I want an invalid cached document rejected, so that freshness alone cannot make corrupt content usable.
6. As a WhiteVPN user, I want a successful refresh to replace the previous snapshot only after compilation succeeds, so that invalid remote content cannot destroy the last-known-good snapshot.
7. As a WhiteVPN user, I want manually requested refresh failures reported, so that the interface does not claim stale content was newly fetched.
8. As a WhiteVPN user, I want background refresh to distinguish fresh cache reuse, successful refresh, and fallback, so that diagnostics describe what actually happened.
9. As a user adding inline subscription content, I want it treated explicitly as inline content, so that later refresh does not reinterpret it as a URL.
10. As a user adding an HTTPS subscription, I want it treated explicitly as remote HTTPS, so that refresh uses the network source safely.
11. As a user with a legacy saved Subscription, I want its source kind migrated without losing the Subscription, so that upgrades remain compatible.
12. As a user, I want non-HTTPS remote user sources rejected, so that Subscription credentials and content are not fetched over an insecure transport.
13. As a user, I want oversized remote content rejected before compilation, so that a source cannot consume unbounded memory.
14. As a user, I want WhiteVPN-managed encrypted content decrypted according to explicit managed-source metadata, so that decryption is not hidden in a caller-specific fetch path.
15. As a user, I want imported Base64, JSON, Mihomo YAML, and supported share links to resolve into the same canonical snapshot shape, so that downstream routing sees one format.
16. As a user, I want arbitrary valid Mihomo keys preserved in canonical YAML, so that advanced subscription behavior is not lost during resolution.
17. As a user, I want profile fingerprints and route catalogs derived from the canonical YAML, so that selection and chaining continue to use the existing identity rules.
18. As a user, I want cancellation to stop refresh work rather than silently falling back, so that lifecycle cancellation remains authoritative.
19. As a maintainer, I want one freshness rule for all Subscription Sources, so that changing the refresh interval has one implementation location.
20. As a maintainer, I want HTTPS, compilation, cache, and fallback failures localized behind the resolver seam, so that callers do not duplicate recovery logic.
21. As a maintainer, I want default and user persistence differences hidden behind one adapter, so that legacy migration does not leak into connection establishment.
22. As a maintainer, I want tests to use the same resolver interface as production callers, so that internal refactors do not invalidate behavior tests.
23. As a maintainer, I want a deterministic clock and in-memory adapters in tests, so that freshness and fallback tests do not sleep or use the network.
24. As a maintainer, I want existing user-facing Subscription CRUD behavior preserved, so that this architecture change does not redesign subscription management.

## Implementation Decisions

- The deep module is named Subscription Snapshot resolution, matching the existing domain term.
- Its external interface resolves one Subscription identity using either `IfStale` or `Force` refresh policy and can read a cached snapshot without network access.
- Resolution reports whether the result came from fresh cache, a successful refresh, or last-known-good fallback. Callers may use this for diagnostics but must use the returned Subscription Snapshot as canonical data.
- Subscription Sources are explicit sealed values: WhiteVPN-managed HTTPS, remote HTTPS, and inline content.
- Newly persisted user Subscriptions store source kind explicitly. Legacy records infer HTTPS versus inline when read, rewrite that metadata when possible, and retain wire compatibility.
- User Subscription freshness uses a dedicated fetched timestamp. Existing records migrate by treating their previous update timestamp as fetched time.
- One HTTPS loader enforces HTTPS, timeouts, response validation, and the existing maximum subscription size for both managed and user sources.
- Managed decryption is driven by explicit source metadata. The current managed URL path heuristic is retained only when constructing legacy managed-source metadata.
- One compiler delegates format normalization to the existing importer, then parses the resulting canonical YAML into the existing Mihomo Subscription Snapshot.
- Canonical, lossless Mihomo YAML remains the persisted source of truth. No protocol-neutral proxy model is introduced.
- Cache validity requires successful parsing and at least one Route Profile; a timestamp cannot make invalid content fresh.
- `IfStale` returns a valid fresh cache immediately, otherwise refreshes and falls back to a valid stale cache when refresh fails.
- `Force` always attempts refresh and propagates failure. Compilation happens before persistence, snapshot files are replaced atomically, and a metadata-write failure restores the previous snapshot.
- Coroutine cancellation is never converted into fallback or an ordinary refresh error. Remote loading is interruptible, and cancellation is checked again before persistence.
- The production persistence adapter hides the different legacy default and user layouts. This migration does not introduce a second parallel snapshot database.
- A successful refresh compiles before persistence. User display metadata and failure text are updated by the persistence adapter after snapshot content is known valid.
- Config Repository and User Subscription Manager refresh/cache callers delegate snapshot resolution to the new resolver. Add, update, and test operations have no persisted Subscription identity yet, so they reuse the module's source loader and compiler directly; downstream Session Plan, chain planning, and Mihomo runtime interfaces remain unchanged.

## Testing Decisions

- Resolver behavior tests use the pre-agreed Subscription Snapshot resolver seam. They provide in-memory persistence and source-loading adapters and observe returned resolutions and persisted snapshots.
- One focused storage-transaction regression exercises snapshot replacement directly because the Android persistence adapter is not available to local JVM tests.
- Tests do not mock the compiler, freshness implementation, or fallback branches; those behaviors remain behind the resolver interface.
- Tracer tests proceed vertically: fresh cache reuse, stale refresh, stale fallback, force-refresh failure, invalid cache recovery, and cancellation propagation.
- Known literal Mihomo documents are the independent source of truth for profile counts, fetched timestamps, and canonical YAML preservation.
- Source metadata migration is tested through its public wire conversion behavior.
- Existing importer tests remain prior art for Base64, JSON, Mihomo YAML, and share-link normalization.
- Existing Config Repository tests remain prior art for the thirty-minute freshness boundary and stale-cache fallback.
- The full debug JVM unit suite runs after focused resolver and importer tests. Android/emulator validation is required only if production persistence behavior cannot be proven by compilation and existing instrumentation coverage.

## Out of Scope

- Replacing Mihomo YAML with a protocol-neutral proxy model.
- Changing profile fingerprint algorithms, Route Catalog semantics, Route Preference, Route Chain planning, or Mihomo runtime generation.
- Redesigning Subscription management UI or user-facing copy.
- Adding new subscription protocols.
- Changing the refresh interval.
- Introducing file, QR-code, provider, or other speculative Subscription Source types.
- Removing legacy cache files before migration evidence shows they are no longer needed.
- Redesigning connection lifecycle, Session Plan, or Profile Test Run modules.

## Further Notes

- The deletion test holds: removing this module would redistribute HTTPS, compilation, freshness, fallback, and persistence rules across Config Repository and User Subscription Manager callers.
- The module gains leverage from one resolver interface and locality by keeping source and cache failure modes together.
- Tracker publication is pending repository issue-tracker setup and valid GitHub authentication. The repository copy is the implementation and review source until publication succeeds.
