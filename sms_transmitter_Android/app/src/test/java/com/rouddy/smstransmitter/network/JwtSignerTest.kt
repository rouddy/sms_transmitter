package com.rouddy.smstransmitter.network

import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class JwtSignerTest {

    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    @Test
    fun `token is a verifiable ES256 JWT`() {
        repeat(50) {
            val token = JwtSigner(keyPair.private, nowSeconds = { 1_000L }).createToken()
            val (header, payload, signature) = token.split(".")

            val rawSignature = Base64.getUrlDecoder().decode(signature)
            assertEquals(64, rawSignature.size)

            val verified = Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(keyPair.public)
                update("$header.$payload".toByteArray())
                verify(rawSignature)
            }
            assertTrue(verified)
        }
    }

    @Test
    fun `claims carry iat, exp within the server limit and a unique jti`() {
        val signer = JwtSigner(keyPair.private, nowSeconds = { 1_000L })
        val first = claims(signer.createToken())
        val second = claims(signer.createToken())

        assertEquals("ES256", header(signer.createToken()).get("alg").asString)
        assertEquals(1_000L, first.get("iat").asLong)
        assertEquals(1_300L, first.get("exp").asLong)
        assertTrue(first.get("jti").asString != second.get("jti").asString)
    }

    @Test
    fun `der signature with sign padding and short coordinates converts to raw`() {
        val r = ByteArray(32) { 0x80.toByte() }
        val s = ByteArray(31) { 0x01 }
        val der = byteArrayOf(0x30, 68, 0x02, 33, 0x00) + r + byteArrayOf(0x02, 31) + s

        val raw = JwtSigner.derToRawSignature(der)

        assertArrayEquals(r, raw.copyOfRange(0, 32))
        assertArrayEquals(byteArrayOf(0x00) + s, raw.copyOfRange(32, 64))
    }

    private fun header(token: String) = decodeSegment(token.split(".")[0])

    private fun claims(token: String) = decodeSegment(token.split(".")[1])

    private fun decodeSegment(segment: String) =
        JsonParser.parseString(String(Base64.getUrlDecoder().decode(segment))).asJsonObject
}
