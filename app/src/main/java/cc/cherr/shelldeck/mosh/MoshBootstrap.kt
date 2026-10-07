package cc.cherr.shelldeck.mosh

import cc.cherr.shelldeck.data.HostRecord
import cc.cherr.shelldeck.ssh.ConnectionState
import cc.cherr.shelldeck.ssh.startupCommandLine

fun validateMoshHost(host: HostRecord) {
    require(host.protocol in setOf("ssh", "mosh"))
    require(host.moshPort in 0..65535)
    require(host.protocol != "mosh" || (host.jumpHostId == null && host.proxyId == null)) {
        "Mosh 暂不支持跳板机和 SOCKS 代理"
    }
}
class MoshFailure(val state: ConnectionState) : Exception(state.message)

object MoshBootstrap {
    // Intentionally not a data class: the session key must never enter toString/logs.
    class Connection(val port: Int, val key: String)
    const val INSTALL_HELP = "远端未找到 mosh-server，请通过 SSH 登录后自行安装：\n\nDebian / Ubuntu\nsudo apt install mosh\n\nFedora\nsudo dnf install mosh\n\nAlpine（root 可省略 sudo）\nsudo apk add mosh\n\n安装后还需允许所选 UDP 端口；默认范围为 60000–61000。ShellDeck 不会自动安装或修改防火墙。"
    fun command(port: Int, startup: String): String {
        require(port in 0..65535)
        startupCommandLine(startup).fill(0)
        // Explicit marker distinguishes missing executable from other startup failures.
        return "command -v mosh-server >/dev/null 2>&1 || { printf 'SHELLDECK_MOSH_MISSING\\n'; exit 127; }; " +
            "MOSH_SERVER_NETWORK_TMOUT=86400 mosh-server new -s -c 256 -l LANG=C.UTF-8" +
            (if (port == 0) "" else " -p $port") +
            (if (startup.isBlank()) "" else " -- /bin/sh -lc " + quote(startup))
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    fun parse(output: String): Connection {
        if (output.lineSequence().any { it.trim() == "SHELLDECK_MOSH_MISSING" })
            throw MoshFailure(ConnectionState.MOSH_MISSING)
        val matches = Regex("(?m)^MOSH CONNECT ([0-9]{1,5}) ([A-Za-z0-9+/]{22})\\r?$").findAll(output).toList()
        if (matches.size != 1) throw MoshFailure(ConnectionState.MOSH_START_FAILED)
        val port = matches.single().groupValues[1].toInt()
        if (port !in 1..65535) throw MoshFailure(ConnectionState.MOSH_START_FAILED)
        return Connection(port, matches.single().groupValues[2])
    }
}
