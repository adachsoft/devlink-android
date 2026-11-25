package com.adachsoft.devlink.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAddressPolicyTest {
    @Test
    fun acceptsPrivateAndLocalAddresses() {
        val addresses = listOf(
            "10.0.0.1",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.5",
            "169.254.10.2",
            "127.0.0.1",
            "::1",
            "fc00::1",
            "fd12:3456::1",
            "fe80::1",
            "::ffff:192.168.1.5",
        )

        addresses.forEach { address ->
            assertTrue("Expected $address to be accepted", SourceAddressPolicy.accepts(address))
        }
    }

    @Test
    fun rejectsPublicAndOutOfRangeAddresses() {
        val addresses = listOf(
            "8.8.8.8",
            "172.15.0.1",
            "172.32.0.1",
            "169.253.0.1",
            "2001:4860:4860::8888",
            "::ffff:8.8.8.8",
            "not-an-ip-address",
            "192.168.1.256",
        )

        addresses.forEach { address ->
            assertFalse("Expected $address to be rejected", SourceAddressPolicy.accepts(address))
        }
    }
}
