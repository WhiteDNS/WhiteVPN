package com.whitedns.vpn

data class SubscriptionCatalog(
    val profiles: List<ConnectionProfile>,
    val fetchedAt: Long,
)
