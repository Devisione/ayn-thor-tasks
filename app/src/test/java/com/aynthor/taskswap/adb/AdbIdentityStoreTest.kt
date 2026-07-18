package com.aynthor.taskswap.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AdbIdentityStoreTest {

    @Test
    fun encodeParse_roundTripsPairedIdentity() {
        val cert = "CERT-BYTES".toByteArray()
        val key = "KEY-BYTES".toByteArray()
        val bytes = AdbIdentityStore.encode(
            paired = true,
            connectionPort = 41235,
            cert = cert,
            key = key
        )
        val backup = AdbIdentityStore.parse(bytes)
        assertNotNull(backup)
        assertTrue(backup!!.paired)
        assertEquals(41235, backup.connectionPort)
        assertEquals(Base64.getEncoder().encodeToString(cert), backup.certPem)
        assertEquals(Base64.getEncoder().encodeToString(key), backup.keyPem)
        assertTrue(AdbIdentityStore.decodePem(backup.certPem!!).contentEquals(cert))
        assertTrue(AdbIdentityStore.decodePem(backup.keyPem!!).contentEquals(key))
    }

    @Test
    fun parse_treatsMissingPortAsNull() {
        val bytes = AdbIdentityStore.encode(
            paired = false,
            connectionPort = null,
            cert = null,
            key = null
        )
        val backup = AdbIdentityStore.parse(bytes)
        assertNotNull(backup)
        assertFalse(backup!!.paired)
        assertNull(backup.connectionPort)
        assertNull(backup.certPem)
        assertNull(backup.keyPem)
    }
}
