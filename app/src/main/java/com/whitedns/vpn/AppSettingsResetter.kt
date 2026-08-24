package com.whitedns.vpn

import android.content.Context

internal object AppSettingsResetter {
    private val preferenceNames = listOf(
        "white_dns_language",
        "white_dns_theme",
        "white_dns_connection_location",
        "white_dns_split_tunnel",
        "white_dns_fronting_ip",
        "white_dns_tls_integrity",
        "white_dns_connection_options",
        "white_dns_routing",
        "white_dns_privacy",
        "white_dns_connection_mode",
        "white_dns_lan_sharing",
        "white_dns_connection_selection",
        "white_dns_connection_test",
        "white_dns_connection_chain",
    )

    fun reset(context: Context) {
        preferenceNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
        }
        SubscriptionStore(context).saveSelectedSubscriptionId(SubscriptionStore.DEFAULT_SUBSCRIPTION_ID)
    }
}
