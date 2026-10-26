package io.github.conditionstech.sdm.engine

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Which IP addresses SDM may connect to (ADR-0007, the same rules as the Windows app).
 *
 * This defends against server-side request forgery: a link, redirect or DNS answer that points
 * SDM at this phone, the router or another device on the Wi-Fi. Addresses are checked:
 * 1. for IP literals in a URL (http://10.0.0.1/...), before each request and redirect hop;
 * 2. for host names, inside OkHttp's [Dns] hook ([PolicyDns]), so the checked addresses are
 *    exactly the ones OkHttp connects to;
 * 3. once more on the connected socket (the downloader's network interceptor), as a safety net.
 *
 * [allowLocalNetwork]: private ranges such as 192.168.x.x (a NAS). [allowLoopback]: this phone;
 * only tests turn it on. Link-local (including 169.254.169.254), multicast, unspecified and
 * reserved addresses are always blocked.
 */
data class NetworkPolicy(
    val allowLocalNetwork: Boolean = false,
    val allowLoopback: Boolean = false,
) {
    /** Throws [BlockedDestinationException] if [host], which resolved to [address], is not allowed. */
    fun check(address: InetAddress, host: String) {
        val ip = embeddedIpv4(address) ?: address
        val shown = ip.hostAddress?.substringBefore('%')
        when (classify(ip)) {
            Kind.PUBLIC -> return
            Kind.LOOPBACK -> if (!allowLoopback) {
                throw BlockedDestinationException(
                    "$host points to this device ($shown); SDM does not download from it.",
                )
            }
            Kind.LOCAL -> if (!allowLocalNetwork) {
                throw BlockedDestinationException(
                    "$host is on a local or private network ($shown); SDM does not download from it.",
                )
            }
            Kind.SPECIAL -> throw BlockedDestinationException(
                "$host points to a special network address ($shown); SDM does not download from it.",
            )
        }
    }

    /** Checks a URL's host if it is an IP literal; host names are checked after DNS lookup. */
    fun checkUrlHost(host: String) {
        val literal = parseIpLiteral(host) ?: return
        check(literal, host)
    }

    private enum class Kind { PUBLIC, LOOPBACK, LOCAL, SPECIAL }

    private fun classify(ip: InetAddress): Kind = when (ip) {
        is Inet4Address -> classify4(ip.address)
        is Inet6Address -> classify6(ip.address)
        else -> Kind.SPECIAL
    }

    private fun classify4(b: ByteArray): Kind {
        val v = toLong(b)
        fun inNet(net: String, bits: Int) = inPrefix(v, net, bits)
        return when {
            inNet("127.0.0.0", 8) -> Kind.LOOPBACK
            inNet("0.0.0.0", 8) || inNet("169.254.0.0", 16) || inNet("224.0.0.0", 4) ||
                inNet("240.0.0.0", 4) -> Kind.SPECIAL // includes 255.255.255.255
            inNet("10.0.0.0", 8) || inNet("172.16.0.0", 12) || inNet("192.168.0.0", 16) ||
                inNet("100.64.0.0", 10) || inNet("192.0.0.0", 24) || inNet("192.0.2.0", 24) ||
                inNet("198.18.0.0", 15) || inNet("198.51.100.0", 24) ||
                inNet("203.0.113.0", 24) -> Kind.LOCAL
            else -> Kind.PUBLIC
        }
    }

    private fun classify6(b: ByteArray): Kind {
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return when {
            b.all { it == 0.toByte() } -> Kind.SPECIAL // ::
            b.sliceArray(0..14).all { it == 0.toByte() } && b[15] == 1.toByte() -> Kind.LOOPBACK
            first == 0xFF -> Kind.SPECIAL // multicast
            first == 0xFE && second and 0xC0 == 0x80 -> Kind.SPECIAL // fe80::/10 link-local
            first == 0xFE && second and 0xC0 == 0xC0 -> Kind.LOCAL // fec0::/10 old site-local
            first and 0xFE == 0xFC -> Kind.LOCAL // fc00::/7 unique local
            first and 0xE0 != 0x20 -> Kind.SPECIAL // outside 2000::/3: reserved
            // Inside 2000::/3, the ranges that are not reachable on the internet.
            first == 0x20 && second == 0x01 && (b[2].toInt() and 0xFE) == 0 -> Kind.LOCAL // 2001::/23
            first == 0x20 && second == 0x01 && b[2] == 0x0D.toByte() && b[3] == 0xB8.toByte() ->
                Kind.LOCAL // 2001:db8::/32 documentation
            first == 0x3F && second == 0xFF && (b[2].toInt() and 0xF0) == 0 -> Kind.LOCAL // 3fff::/20
            else -> Kind.PUBLIC
        }
    }

    companion object {
        /**
         * The IPv4 address an IPv6 address really leads to, if it carries one (ADR-0017):
         * ::ffff:127.0.0.1 (IPv4-mapped), 64:ff9b::7f00:1 (NAT64) and 2002:7f00:1:: (6to4) all
         * lead to 127.0.0.1, so they are checked as that address.
         */
        fun embeddedIpv4(address: InetAddress): Inet4Address? {
            if (address !is Inet6Address) return null
            val b = address.address
            val mapped = b.sliceArray(0..9).all { it == 0.toByte() } &&
                b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
            val nat64 = b[0] == 0x00.toByte() && b[1] == 0x64.toByte() &&
                b[2] == 0xFF.toByte() && b[3] == 0x9B.toByte() && b.sliceArray(4..11).all { it == 0.toByte() }
            return when {
                mapped || nat64 -> InetAddress.getByAddress(b.sliceArray(12..15)) as Inet4Address
                b[0] == 0x20.toByte() && b[1] == 0x02.toByte() ->
                    InetAddress.getByAddress(b.sliceArray(2..5)) as Inet4Address
                else -> null
            }
        }

        private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /**
         * The address an IP literal host stands for, or null for a host name. Never looks
         * anything up: only literals reach [InetAddress.getByName].
         */
        fun parseIpLiteral(host: String): InetAddress? {
            val bare = host.removePrefix("[").removeSuffix("]").substringBefore('%')
            return when {
                bare.contains(':') -> runCatching { InetAddress.getByName(bare) }.getOrNull()
                IPV4_LITERAL.matches(bare) && bare.split('.').all { it.toInt() <= 255 } ->
                    InetAddress.getByName(bare)
                else -> null
            }
        }

        private fun toLong(b: ByteArray): Long =
            b.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }

        private fun inPrefix(value: Long, net: String, bits: Int): Boolean {
            val base = toLong(net.split('.').map { it.toInt().toByte() }.toByteArray())
            val mask = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
            return value and mask == base and mask
        }
    }
}

/** OkHttp's DNS hook: refuses a host if any address it resolves to is blocked by the policy. */
class PolicyDns(
    private val policy: NetworkPolicy,
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        addresses.forEach { policy.check(it, hostname) }
        return addresses
    }
}
