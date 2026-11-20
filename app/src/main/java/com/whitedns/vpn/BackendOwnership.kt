package com.whitedns.vpn

/** A failed or pending teardown keeps ownership; an obsolete lease cannot clear a newer session. */
internal class BackendOwnership {
    private var owner: BackendLease? = null
    @Synchronized fun claim(lease: BackendLease): Boolean {
        if (owner != null) return false
        owner = lease
        return true
    }
    @Synchronized fun accepts(lease: BackendLease): Boolean = owner == lease
    @Synchronized fun confirmRelease(lease: BackendLease): Boolean {
        if (owner != lease) return false
        owner = null
        return true
    }
    @Synchronized fun vacant(): Boolean = owner == null
}

internal object EngineSessionOwnership {
    val leases = BackendOwnership()
}
