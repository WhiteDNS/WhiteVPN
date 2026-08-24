---
status: proposed
---

# Separate lifecycle phase, data-plane ownership, and route health

WhiteVPN will model Lifecycle Phase, Data Plane Ownership, and Route Health as independent facts, and will declare the lifecycle Inactive only after ownership is confirmed Absent. A single state value is simpler, but it cannot truthfully represent an active but unhealthy route, a failed replacement that preserved the previous route, or a release timeout where traffic-path ownership is uncertain.
