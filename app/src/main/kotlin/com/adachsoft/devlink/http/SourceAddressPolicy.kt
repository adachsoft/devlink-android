package com.adachsoft.devlink.http

import java.net.InetAddress

object SourceAddressPolicy {
    fun accepts(remoteIp: String): Boolean {
        var address = remoteIp.trim()
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length - 1)
        }
        if (address.isEmpty()) {
            return false
        }

        if (!address.contains(':')) {
            return parseIpv4(address)?.let(::isAllowedIpv4) ?: false
        }

        val zoneSeparator = address.indexOf('%')
        if (zoneSeparator >= 0) {
            val zone = address.substring(zoneSeparator + 1)
            if (address.indexOf('%', zoneSeparator + 1) >= 0 || zone.isEmpty() ||
                !zone.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
            ) {
                return false
            }
            address = address.substring(0, zoneSeparator)
        }

        if (!address.contains(':') || !address.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return false
        }

        val bytes = try {
            InetAddress.getByName(address).address
        } catch (_: Exception) {
            return false
        }

        if (bytes.size == 4) {
            return isAllowedIpv4(bytes)
        }
        if (bytes.size != 16) {
            return false
        }

        if (isIpv4Mapped(bytes)) {
            return isAllowedIpv4(bytes.copyOfRange(12, 16))
        }

        val firstByte = bytes[0].toInt() and 0xff
        if ((firstByte and 0xfe) == 0xfc) {
            return true
        }
        if (firstByte == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80) {
            return true
        }
        return bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1
    }

    private fun parseIpv4(address: String): ByteArray? {
        val parts = address.split('.')
        if (parts.size != 4) {
            return null
        }

        val bytes = ByteArray(4)
        for (index in parts.indices) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) {
                return null
            }
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) {
                return null
            }
            bytes[index] = octet.toByte()
        }
        return bytes
    }

    private fun isAllowedIpv4(address: ByteArray): Boolean {
        if (address.size != 4) {
            return false
        }

        val first = address[0].toInt() and 0xff
        val second = address[1].toInt() and 0xff
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 169 && second == 254) ||
            first == 127
    }

    private fun isIpv4Mapped(address: ByteArray): Boolean =
        address.take(10).all { it.toInt() == 0 } &&
            (address[10].toInt() and 0xff) == 0xff &&
            (address[11].toInt() and 0xff) == 0xff
}
