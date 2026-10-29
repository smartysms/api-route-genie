package com.routegenie.client

import com.routegenie.Limits

enum class HostClass { LOOPBACK, PRIVATE, PUBLIC }

/**
 * Host classification by the text of the host, without any DNS lookup (so it is instant and cannot be steered by a name
 * server). A name such as localhost.evil.com is PUBLIC; only `localhost`, `*.localhost` and literal addresses are local.
 * Known limit (research 03, 7.1): a public name that resolves to a local address is still PUBLIC here, which is the safe side.
 */
object HostClasses {
    fun classify(host: String): HostClass {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase().removeSuffix(".")
        if (h == "localhost" || h.endsWith(".localhost")) return HostClass.LOOPBACK
        ipv4(h)?.let { return classifyV4(it) }
        if (':' in h) return classifyV6(h)
        return HostClass.PUBLIC
    }

    fun isLoopback(host: String): Boolean = classify(host) == HostClass.LOOPBACK

    private fun ipv4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val n = parts.map { p -> if (p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' }) p.toInt() else return null }
        return if (n.all { it in 0..255 }) n.toIntArray() else null
    }

    private fun classifyV4(a: IntArray): HostClass = when {
        a[0] == 127 -> HostClass.LOOPBACK
        a[0] == 10 -> HostClass.PRIVATE
        a[0] == 172 && a[1] in 16..31 -> HostClass.PRIVATE
        a[0] == 192 && a[1] == 168 -> HostClass.PRIVATE
        a[0] == 169 && a[1] == 254 -> HostClass.PRIVATE
        else -> HostClass.PUBLIC
    }

    private fun classifyV6(h: String): HostClass {
        val mapped = h.removePrefix("::ffff:")
        if (mapped != h) ipv4(mapped)?.let { return classifyV4(it) }
        if (h == "::1" || h == "0:0:0:0:0:0:0:1") return HostClass.LOOPBACK
        val first = h.substringBefore(':')
        val word = first.toIntOrNull(HEX_RADIX) ?: return HostClass.PUBLIC
        return when {
            word and ULA_MASK == ULA_PREFIX -> HostClass.PRIVATE
            word and LINK_LOCAL_MASK == LINK_LOCAL_PREFIX -> HostClass.PRIVATE
            else -> HostClass.PUBLIC
        }
    }

    private const val HEX_RADIX = 16
    private const val ULA_MASK = 0xFE00
    private const val ULA_PREFIX = 0xFC00
    private const val LINK_LOCAL_MASK = 0xFFC0
    private const val LINK_LOCAL_PREFIX = 0xFE80
}

/**
 * The decision logic of "never surprise-send": a request to a host that is not on this machine or the private network needs
 * the user's confirmation once per host. The guard asks nothing itself; the UI asks and then calls [confirm].
 * Thread-safe, bounded by [Limits.CONFIRMED_HOSTS_MAX] (oldest confirmation dropped first).
 */
class HostGuard(initiallyConfirmed: Collection<String> = emptyList()) {
    private val confirmed = LinkedHashSet<String>()

    init {
        initiallyConfirmed.forEach { confirm(it) }
    }

    fun hostOf(spec: RequestSpec): String? = (UrlSupport.parse(spec.url) as? UrlResult.Ok)?.url?.host?.lowercase()

    /** True when sending [spec] first needs a confirmation. An unparsable URL needs none: the send fails by itself. */
    fun needsConfirmation(spec: RequestSpec): Boolean = hostOf(spec)?.let { needsConfirmation(it) } ?: false

    fun needsConfirmation(host: String): Boolean = HostClasses.classify(host) == HostClass.PUBLIC && !isConfirmed(host)

    /** For [SendOptions.redirectHostAllowed]: local, private or confirmed hosts may be followed to. */
    fun isAllowed(host: String): Boolean = !needsConfirmation(host)

    @Synchronized
    fun isConfirmed(host: String): Boolean = host.lowercase() in confirmed

    @Synchronized
    fun confirm(host: String) {
        val h = host.lowercase()
        confirmed.remove(h)
        confirmed.add(h)
        while (confirmed.size > Limits.CONFIRMED_HOSTS_MAX) confirmed.remove(confirmed.first())
    }

    @Synchronized
    fun forget(host: String) {
        confirmed.remove(host.lowercase())
    }

    @Synchronized
    fun snapshot(): List<String> = confirmed.toList()
}
