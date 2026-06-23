package io.nekohasekai.libbox

class CommandServer(
    private val handler: CommandServerHandler,
    private val platformInterface: PlatformInterface,
) {
    fun start() = Unit

    fun checkConfig(config: String?) = Unit

    fun startOrReloadService(config: String?, options: OverrideOptions?) {
        throw UnsatisfiedLinkError("app/libs/libbox.aar is required to run sing-box")
    }

    fun needWIFIState(): Boolean = false

    fun closeService() = Unit

    fun close() = Unit

    fun setError(message: String?) = Unit
}

class CommandClient(
    private val handler: CommandClientHandler,
    private val options: CommandClientOptions?,
) {
    fun connect() = Unit
    fun disconnect() = Unit
    fun urlTest(groupTag: String?) = Unit
}

interface CommandClientHandler {
    fun connected()
    fun disconnected(message: String?)
    fun clearLogs()
    fun initializeClashMode(modeList: StringIterator, currentMode: String?)
    fun setDefaultLogLevel(level: Int)
    fun updateClashMode(newMode: String?)
    fun writeConnectionEvents(events: ConnectionEvents?)
    fun writeGroups(message: OutboundGroupIterator)
    fun writeLogs(messageList: LogIterator?)
    fun writeStatus(message: StatusMessage?)
}

class CommandClientOptions {
    var statusInterval: Long = 0
    fun addCommand(command: Int) = Unit
}

interface CommandServerHandler {
    fun serviceStop()
    fun serviceReload()
    fun getSystemProxyStatus(): SystemProxyStatus?
    fun setSystemProxyEnabled(isEnabled: Boolean)
    fun writeDebugMessage(message: String?)
}

interface PlatformInterface {
    fun usePlatformAutoDetectInterfaceControl(): Boolean
    fun autoDetectInterfaceControl(fd: Int)
    fun openTun(options: TunOptions): Int
    fun useProcFS(): Boolean
    fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner
    fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener)
    fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener)
    fun getInterfaces(): NetworkInterfaceIterator
    fun underNetworkExtension(): Boolean
    fun includeAllNetworks(): Boolean
    fun clearDNSCache()
    fun readWIFIState(): WIFIState?
    fun localDNSTransport(): LocalDNSTransport?
    fun systemCertificates(): StringIterator
    fun sendNotification(notification: Notification)
}

class OverrideOptions {
    var autoRedirect: Boolean = false
    var includePackage: StringIterator? = null
    var excludePackage: StringIterator? = null
}

class SystemProxyStatus {
    var available: Boolean = false
    var enabled: Boolean = false
}

class Notification {
    var identifier: String = ""
    var typeID: Int = 0
    var typeName: String = ""
    var title: String = ""
    var body: String = ""
    var subtitle: String? = null
    var openURL: String? = null
}

object Libbox {
    const val CommandGroup = 1
    const val CommandStatus = 2
    const val DNSModeDisabled = 0
    const val InterfaceTypeWIFI = 1
    const val InterfaceTypeCellular = 2
    const val InterfaceTypeEthernet = 3
    const val InterfaceTypeOther = 4

    fun setup(options: SetupOptions?) = Unit
    fun redirectStderr(path: String?) = Unit
    fun setMemoryLimit(enabled: Boolean) = Unit
    fun version(): String = "stub"
    fun newCommandClient(handler: CommandClientHandler, options: CommandClientOptions?): CommandClient {
        return CommandClient(handler, options)
    }
    fun newCommandServer(handler: CommandServerHandler, platformInterface: PlatformInterface): CommandServer {
        return CommandServer(handler, platformInterface)
    }
}

class SetupOptions {
    var basePath: String? = null
    var workingPath: String? = null
    var tempPath: String? = null
    var fixAndroidStack: Boolean = false
    var commandServerListenPort: Int = 0
    var commandServerSecret: String? = null
    var logMaxLines: Long = 0
    var debug: Boolean = false
}

class TunOptions {
    var mtu: Int = 1500
    var autoRoute: Boolean = true
    var inet4Address: RoutePrefixIterator = EmptyRoutePrefixIterator
    var inet6Address: RoutePrefixIterator = EmptyRoutePrefixIterator
    var inet4RouteAddress: RoutePrefixIterator = EmptyRoutePrefixIterator
    var inet6RouteAddress: RoutePrefixIterator = EmptyRoutePrefixIterator
    var inet4RouteExcludeAddress: RoutePrefixIterator = EmptyRoutePrefixIterator
    var inet6RouteExcludeAddress: RoutePrefixIterator = EmptyRoutePrefixIterator
    var inet4RouteRange: RoutePrefixIterator = EmptyRoutePrefixIterator
    var inet6RouteRange: RoutePrefixIterator = EmptyRoutePrefixIterator
    var dnsServerAddress: StringBox? = null
    var isHTTPProxyEnabled: Boolean = false
    var httpProxyServer: String = "127.0.0.1"
    var httpProxyServerPort: Int = 2080
    var httpProxyBypassDomain: StringIterator = EmptyStringIterator
}

class StringBox {
    var value: String? = null
}

open class RoutePrefix(
    private val address: String = "",
    private val prefix: Int = 0,
) {
    fun address(): String = address
    fun prefix(): Int = prefix
}

interface RoutePrefixIterator {
    fun hasNext(): Boolean
    fun next(): RoutePrefix
}

object EmptyRoutePrefixIterator : RoutePrefixIterator {
    override fun hasNext(): Boolean = false
    override fun next(): RoutePrefix = RoutePrefix()
}

interface StringIterator {
    fun len(): Int
    fun hasNext(): Boolean
    fun next(): String
}

object EmptyStringIterator : StringIterator {
    override fun len(): Int = 0
    override fun hasNext(): Boolean = false
    override fun next(): String = ""
}

interface NetworkInterfaceIterator {
    fun hasNext(): Boolean
    fun next(): NetworkInterface
}

interface OutboundGroupIterator {
    fun hasNext(): Boolean
    fun next(): OutboundGroup
}

interface OutboundGroup {
    fun getTag(): String
    fun getItems(): OutboundGroupItemIterator
}

interface OutboundGroupItemIterator {
    fun hasNext(): Boolean
    fun next(): OutboundGroupItem
}

interface OutboundGroupItem {
    fun getTag(): String
    fun getURLTestDelay(): Int
}

interface LogIterator

class ConnectionEvents

class StatusMessage

class NetworkInterface {
    var name: String? = null
    var index: Int = -1
    var mtu: Int = 1500
    var dnsServer: StringIterator? = null
    var type: Int = Libbox.InterfaceTypeOther
    var addresses: StringIterator? = null
    var flags: Int = 0
    var metered: Boolean = false
}

interface InterfaceUpdateListener {
    fun updateDefaultInterface(name: String?, index: Int, isExpensive: Boolean, isConstrained: Boolean)
}

class ConnectionOwner {
    var userId: Int = -1
    var userName: String? = null
    fun setAndroidPackageNames(packages: StringIterator?) = Unit
}

class PlatformUser {
    var username: String? = null
    var uid: Int = -1
    var gid: Int = -1
    var homeDir: String? = null
}

class WIFIState(
    var ssid: String = "",
    var bssid: String = "",
)

interface LocalDNSTransport
