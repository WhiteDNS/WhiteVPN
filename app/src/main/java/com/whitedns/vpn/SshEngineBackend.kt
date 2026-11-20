package com.whitedns.vpn

import android.content.Context
import com.jcraft.jsch.UserInfo
import com.whitedns.vpn.engines.models.SshProfile
import com.whitedns.vpn.engines.partner.SshController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

internal object SshHostKeyTrustPolicy {
    fun confirm(message: String, ask: (String) -> Boolean): Boolean =
        !message.contains("changed", true) && !message.contains("WARNING", true) && ask(message)
}

internal class SshEngineBackend(context: Context, private val profile: EngineProfile) : AbstractEngineBackend() {
    private val knownHosts = File(context.noBackupFilesDir, "engine-ssh-known-hosts").apply { if (!exists()) createNewFile() }
    private val userInfo = object : UserInfo {
        override fun getPassphrase(): String = profile.value("keyPassphrase")
        override fun getPassword(): String = profile.value("password")
        override fun promptPassword(message: String): Boolean = false
        override fun promptPassphrase(message: String): Boolean = false
        override fun showMessage(message: String) = Unit
        override fun promptYesNo(message: String): Boolean {
            // JSch's changed-key prompt must never be turned into first-use trust.
            return SshHostKeyTrustPolicy.confirm(message, EngineTrustPrompts::ask)
        }
    }
    private val controller = SshController(
        profile = SshProfile(profile.value("host"), profile.number("port", 22), profile.value("username"),
            profile.value("authType", "password"), profile.value("password"), profile.value("privateKey"), profile.value("keyPassphrase")),
        knownHosts = knownHosts, userInfo = userInfo,
        cipher = "auto", compression = false, listenPort = EngineNativeAvailability.allocatePort(),
    )
    private var endpoint: SocksEndpoint? = null
    override val socksEndpoint get() = endpoint
    override val running get() = controller.isRunning
    override suspend fun start() {
        val port = runInterruptible(Dispatchers.IO) { controller.start() }
        check(port > 0) { "SSH authentication or host-key verification failed" }
        endpoint = SocksEndpoint(port)
        waitForPort(port, { running })
        state.value = BackendEvent.Ready
    }
    override suspend fun stop(): Boolean {
        EngineTrustPrompts.cancel()
        val stopped = runInterruptible(Dispatchers.IO) { controller.stop() }
        if (stopped) state.value = BackendEvent.Stopped
        return stopped
    }
}
