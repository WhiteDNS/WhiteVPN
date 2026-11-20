package com.whitedns.vpn.engines.models

data class SshProfile(val host: String, val port: Int = 22, val username: String = "", val authType: String = "password", val password: String = "", val privateKey: String = "", val keyPassphrase: String = "") {
    companion object { const val AUTH_PASSWORD = "password"; const val AUTH_KEY = "key" }
}
