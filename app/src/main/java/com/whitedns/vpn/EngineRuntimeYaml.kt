package com.whitedns.vpn

internal object EngineRuntimeYaml {
    fun build(endpoint: SocksEndpoint): String {
        require(endpoint.host == "127.0.0.1" && endpoint.port in 1..65535)
        return """
            proxies:
              - name: WhiteVPN Engine
                type: socks5
                server: 127.0.0.1
                port: ${endpoint.port}
                udp: false
            proxy-groups:
              - name: WhiteDNS Proxy
                type: select
                proxies:
                  - WhiteVPN Engine
              - name: WhiteDNS Auto
                type: select
                proxies:
                  - WhiteVPN Engine
            rules:
              - NETWORK,UDP,REJECT
              - MATCH,WhiteDNS Proxy
        """.trimIndent() + "\n"
    }
}
