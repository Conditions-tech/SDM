package io.github.conditionstech.sdm.engine

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

// The Windows app's tests/unit/test_policy.py (ADR-0007, ADR-0017), for the Android engine.
class NetworkPolicyTest {
    private val default = NetworkPolicy()
    private val permissive = NetworkPolicy(allowLocalNetwork = true, allowLoopback = true)

    private fun ip(text: String): InetAddress = NetworkPolicy.parseIpLiteral(text)!!

    private fun assertBlocked(policy: NetworkPolicy, address: String, expected: String) {
        val error = assertThrows(address, BlockedDestinationException::class.java) {
            policy.check(ip(address), "host.test")
        }
        assertTrue("$address -> ${error.message}", error.message!!.contains(expected))
    }

    @Test
    fun publicAddressesAreAllowed() {
        for (address in listOf("8.8.8.8", "93.184.215.14", "2606:4700:4700::1111", "1.1.1.1")) {
            default.check(ip(address), "example.com")
        }
    }

    @Test
    fun thisDeviceIsBlockedByDefault() {
        for (address in listOf("127.0.0.1", "127.8.9.10", "::1", "::ffff:127.0.0.1")) {
            assertBlocked(default, address, "this device")
        }
        NetworkPolicy(allowLoopback = true).check(ip("127.0.0.1"), "host")
    }

    @Test
    fun privateNetworksNeedOptIn() {
        val private = listOf(
            "10.0.0.5", "172.16.3.4", "172.31.255.255", "192.168.1.10", "100.64.0.1",
            "198.18.0.1", "192.0.2.1", "fd12::1", "fec0::1", "2001:db8::1",
        )
        for (address in private) {
            assertBlocked(default, address, "local or private")
            NetworkPolicy(allowLocalNetwork = true).check(ip(address), "nas")
        }
        default.check(ip("172.32.0.1"), "just outside 172.16.0.0/12")
    }

    @Test
    fun specialAddressesAreAlwaysBlocked() {
        val special = listOf(
            "169.254.169.254", "fe80::1", "0.0.0.0", "::", "224.0.0.1", "ff02::1",
            "240.0.0.1", "255.255.255.255", "100::1",
        )
        for (address in special) assertBlocked(permissive, address, "special")
    }

    @Test
    fun ipv4HiddenInsideIpv6IsCheckedAsIpv4() {
        val cases = mapOf(
            "64:ff9b::7f00:1" to "this device", // NAT64 wrapping 127.0.0.1
            "2002:7f00:1::" to "this device", // 6to4 wrapping 127.0.0.1
            "64:ff9b::c0a8:101" to "local or private", // NAT64 wrapping 192.168.1.1
            "2002:c0a8:101::1" to "local or private", // 6to4 wrapping 192.168.1.1
            "64:ff9b::a9fe:a9fe" to "special", // NAT64 wrapping 169.254.169.254
            "2002:a9fe:a9fe::" to "special", // 6to4 wrapping 169.254.169.254
        )
        for ((address, expected) in cases) assertBlocked(default, address, expected)
        default.check(ip("64:ff9b::808:808"), "wraps 8.8.8.8")
        default.check(ip("2002:808:808::1"), "wraps 8.8.8.8")
        NetworkPolicy(allowLocalNetwork = true).check(ip("64:ff9b::c0a8:101"), "nas.test")
    }

    @Test
    fun urlHostCheckCoversIpLiteralsOnly() {
        default.checkUrlHost("example.com") // host names are checked after the DNS lookup
        for (literal in listOf("127.0.0.1", "::1", "[::1]", "10.1.2.3")) {
            assertThrows(literal, BlockedDestinationException::class.java) { default.checkUrlHost(literal) }
        }
    }

    @Test
    fun literalParsingNeverLooksNamesUp() {
        assertNull(NetworkPolicy.parseIpLiteral("example.com"))
        assertNull(NetworkPolicy.parseIpLiteral("2130706433")) // left to the DNS hook
        assertNull(NetworkPolicy.parseIpLiteral("300.1.1.1"))
        assertEquals("10.0.0.1", NetworkPolicy.parseIpLiteral("10.0.0.1")!!.hostAddress)
    }

    @Test
    fun dnsHookRefusesNamesThatResolveToBlockedAddresses() {
        val fake = Dns { name ->
            when (name) {
                "rebind.test" -> listOf(ip("93.184.215.14"), ip("127.0.0.1")) // one bad answer is enough
                else -> listOf(ip("93.184.215.14"))
            }
        }
        val dns = PolicyDns(default, fake)
        assertEquals(1, dns.lookup("good.test").size)
        val error = assertThrows(BlockedDestinationException::class.java) { dns.lookup("rebind.test") }
        assertTrue(error.message!!.contains("rebind.test points to this device"))
    }
}
