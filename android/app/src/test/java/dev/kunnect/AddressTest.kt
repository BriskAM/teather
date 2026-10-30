package dev.kunnect
import org.junit.Assert.*
import org.junit.Test
class AddressTest {
    @Test fun onlyTailnetIPv4Accepted() {
        listOf("100.64.0.1", "100.127.255.255").forEach { assertTrue(it, TailnetAddress.valid(it)) }
        listOf("100.63.0.1", "100.128.0.1", "127.0.0.1", "10.0.2.2", "100.64.0.256", "100.064.0.1", "", "host.example").forEach { assertFalse(it, TailnetAddress.valid(it)) }
    }
}
