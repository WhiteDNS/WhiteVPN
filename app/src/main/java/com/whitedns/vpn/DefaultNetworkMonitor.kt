package com.whitedns.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.net.Inet6Address
import java.net.NetworkInterface

data class DefaultNetworkCandidate(
    val name: String,
    val index: Int,
    val hasInternet: Boolean,
    val isVpn: Boolean,
    val isValidated: Boolean,
    val isWifi: Boolean,
    val isEthernet: Boolean,
    val isCellular: Boolean,
    val isExpensive: Boolean,
    val isConstrained: Boolean,
    val hasIpv6: Boolean,
)

object DefaultNetworkSelector {
    fun choose(candidates: List<DefaultNetworkCandidate>): DefaultNetworkCandidate? {
        return candidates
            .filter { it.name.isNotBlank() && it.index >= 0 && it.hasInternet && !it.isVpn }
            .maxWithOrNull(compareBy<DefaultNetworkCandidate> { it.priority() }
                .thenBy { if (it.isExpensive) 0 else 1 }
                .thenBy { if (it.isConstrained) 0 else 1 })
    }

    private fun DefaultNetworkCandidate.priority(): Int {
        return when {
            isValidated && (isWifi || isEthernet) -> 4
            isValidated && isCellular -> 3
            isValidated -> 2
            else -> 1
        }
    }
}

class DefaultNetworkMonitor(private val context: Context) {
    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var defaultNetworkChangeListener: ((DefaultNetworkCandidate?) -> Unit)? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var isRegistered = false
    private var lastReported: DefaultNetworkCandidate? = null
    private var reportedNoNetwork = false

    fun start() {
        if (callback != null) return
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                notifyDefaultInterface()
            }

            override fun onLost(network: Network) {
                notifyDefaultInterface()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                notifyDefaultInterface()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                notifyDefaultInterface()
            }
        }
        callback = networkCallback

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            isRegistered = true
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity.registerNetworkCallback(request, networkCallback)
            isRegistered = true
        }
        notifyDefaultInterface()
    }

    fun stop() {
        if (isRegistered) {
            callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        }
        isRegistered = false
        callback = null
        lastReported = null
        reportedNoNetwork = false
    }

    fun setDefaultNetworkChangeListener(listener: ((DefaultNetworkCandidate?) -> Unit)?) {
        defaultNetworkChangeListener = listener
        notifyDefaultInterface()
    }

    fun hasUsableIpv6DefaultNetwork(): Boolean {
        return selectDefaultNetwork()?.hasIpv6 == true
    }

    fun hasUsableDefaultNetwork(): Boolean {
        return selectDefaultNetwork() != null
    }

    fun currentDefaultNetwork(): DefaultNetworkCandidate? {
        return selectDefaultNetwork()
    }

    private fun notifyDefaultInterface() {
        val selected = selectDefaultNetwork()
        if (selected == null) {
            if (!reportedNoNetwork) {
                DiagnosticLogger.info(context, "network.default.none")
            }
            lastReported = null
            reportedNoNetwork = true
            defaultNetworkChangeListener?.invoke(null)
            return
        }

        reportedNoNetwork = false
        if (selected != lastReported) {
            DiagnosticLogger.info(
                context,
                "network.default.selected",
                "name=${selected.name} index=${selected.index} validated=${selected.isValidated} expensive=${selected.isExpensive} constrained=${selected.isConstrained} hasIpv6=${selected.hasIpv6}",
            )
            lastReported = selected
            defaultNetworkChangeListener?.invoke(selected)
        }
    }

    private fun selectDefaultNetwork(): DefaultNetworkCandidate? {
        return DefaultNetworkSelector.choose(
            connectivity.allNetworks.mapNotNull { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                val linkProperties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
                val name = linkProperties.interfaceName.orEmpty()
                val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
                DefaultNetworkCandidate(
                    name = name,
                    index = index,
                    hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    isExpensive = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    isConstrained = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
                    } else {
                        false
                    },
                    hasIpv6 = linkProperties.linkAddresses.any { linkAddress ->
                        val address = linkAddress.address
                        address is Inet6Address && !address.isLoopbackAddress && !address.isLinkLocalAddress
                    },
                )
            },
        )
    }

}
