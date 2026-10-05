package cc.cherr.shelldeck.ssh

import cc.cherr.shelldeck.data.HostRecord

/** Outer gateway first, destination last. Missing references and cycles never fall back to direct SSH. */
fun jumpRoute(target: HostRecord, find: (String) -> HostRecord?): List<HostRecord> {
    val route = mutableListOf<HostRecord>()
    val seen = mutableSetOf<String>()
    var current = target
    while (true) {
        require(seen.add(current.id)) { "跳板机配置形成循环" }
        require(route.size < 5) { "最多支持四层跳板机" }
        route.add(current)
        val next = current.jumpHostId ?: break
        current = requireNotNull(find(next)) { "跳板机不存在，请重新配置" }
    }
    return route.reversed()
}
