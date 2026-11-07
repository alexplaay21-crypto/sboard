package com.sboard.app.feature.passkeys

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.MessageDigest
import java.util.Base64

/**
 * Standalone WebAuthn `create()` core for ES256 platform credentials.
 * The caller must verify the calling app/origin (including Digital Asset Links where
 * applicable), perform user verification, enforce exclusions, and persist the private
 * key only through PasskeyRepository. This class never stores key material.
 */
object WebAuthnRegistrationEngine {
    data class Registration(
        val credentialId: ByteArray,
        val userHandle: ByteArray,
        val privateKeyPkcs8: ByteArray,
        val publicKeyCose: ByteArray,
        val clientDataJson: ByteArray,
        val authenticatorData: ByteArray,
        val attestationObject: ByteArray,
        val signCount: Long = 0L,
        val rpId: String = "",
        val rpName: String = "",
        val userName: String = "",
        val displayName: String = "",
        val credProps: Boolean = false,
    ) {
        fun toCredentialResponseJson(): String = JSONObject().apply {
            put("id", credentialId.base64Url())
            put("rawId", credentialId.base64Url())
            put("type", "public-key")
            put("authenticatorAttachment", "platform")
            put("clientExtensionResults", JSONObject().apply {
                if (credProps) put("credProps", JSONObject().put("rk", true))
            })
            put("response", JSONObject().apply {
                put("clientDataJSON", clientDataJson.base64Url())
                put("attestationObject", attestationObject.base64Url())
                put("transports", org.json.JSONArray().put("internal"))
            })
        }.toString()
    }

    fun create(
        requestJson: String,
        expectedRpId: String,
        origin: String,
        userVerified: Boolean,
        random: SecureRandom = SecureRandom()
    ): Registration {
        require(userVerified) { "User verification is required before passkey creation" }
        val validated = WebAuthnRegistrationPolicy.validate(requestJson, expectedRpId, origin)
        val rpId = validated.rpId
        val userHandle = validated.userId
        val challenge = validated.challenge

        val keyPair = generateEs256KeyPair()
        val publicKey = keyPair.public as ECPublicKey
        val credentialId = ByteArray(32).also(random::nextBytes)
        val coseKey = encodeCoseEc2(publicKey)
        val clientData = JSONObject().apply {
            put("type", "webauthn.create")
            put("challenge", challenge)
            put("origin", origin)
            put("crossOrigin", false)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val rpIdHash = MessageDigest.getInstance("SHA-256")
            .digest(rpId.toByteArray(StandardCharsets.UTF_8))
        // UP=1, UV=1, BE=1, BS=1 (synced via encrypted Vault), AT=1. Synced passkeys keep signCount 0.
        val flags: Byte = 0x5D
        val aaguid = ByteArray(16)
        val authData = ByteArrayOutputStream().apply {
            write(rpIdHash)
            write(flags.toInt())
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0).array())
            write(aaguid)
            write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(credentialId.size.toShort()).array())
            write(credentialId)
            write(coseKey)
        }.toByteArray()
        val attestationObject = Cbor.encodeMap(
            listOf(
                Cbor.text("fmt") to Cbor.text("none"),
                Cbor.text("attStmt") to Cbor.encodeMap(emptyList()),
                Cbor.text("authData") to Cbor.bytes(authData)
            )
        )

        return Registration(
            credentialId.copyOf(), userHandle.copyOf(), keyPair.private.encoded.copyOf(),
            coseKey, clientData, authData, attestationObject,
            rpId = rpId, rpName = validated.rpName, userName = validated.userName,
            displayName = validated.displayName, credProps = validated.credPropsRequested,
        )
    }

    private fun generateEs256KeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun encodeCoseEc2(publicKey: ECPublicKey): ByteArray {
        val x = publicKey.w.affineX.toFixed32()
        val y = publicKey.w.affineY.toFixed32()
        return Cbor.encodeMap(
            listOf(
                Cbor.integer(1) to Cbor.integer(2),       // kty: EC2
                Cbor.integer(3) to Cbor.integer(-7),      // alg: ES256
                Cbor.integer(-1) to Cbor.integer(1),      // crv: P-256
                Cbor.integer(-2) to Cbor.bytes(x),        // x
                Cbor.integer(-3) to Cbor.bytes(y)         // y
            )
        )
    }

    private fun java.math.BigInteger.toFixed32(): ByteArray {
        val raw = toByteArray()
        val unsigned = if (raw.size == 33 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        require(unsigned.size <= 32) { "Invalid P-256 coordinate" }
        return ByteArray(32).also { unsigned.copyInto(it, 32 - unsigned.size) }
    }

    private fun String.decodeBase64Url(): ByteArray = Base64.getUrlDecoder().decode(this)
    private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    /** Minimal deterministic CBOR encoder for the WebAuthn values emitted here. */
    private object Cbor {
        fun integer(value: Int): ByteArray = if (value >= 0) head(0, value.toLong()) else head(1, (-1L - value))
        fun text(value: String): ByteArray {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            return head(3, bytes.size.toLong()) + bytes
        }
        fun bytes(value: ByteArray): ByteArray = head(2, value.size.toLong()) + value
        fun encodeMap(entries: List<Pair<ByteArray, ByteArray>>): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(head(5, entries.size.toLong()))
            entries.forEach { (key, value) -> out.write(key); out.write(value) }
            return out.toByteArray()
        }
        private fun head(major: Int, value: Long): ByteArray {
            require(value >= 0)
            return when {
                value < 24 -> byteArrayOf(((major shl 5) or value.toInt()).toByte())
                value <= 0xff -> byteArrayOf(((major shl 5) or 24).toByte(), value.toByte())
                value <= 0xffff -> byteArrayOf(((major shl 5) or 25).toByte(), (value ushr 8).toByte(), value.toByte())
                value <= 0xffff_ffffL -> byteArrayOf(((major shl 5) or 26).toByte(), (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
                else -> byteArrayOf(((major shl 5) or 27).toByte()) + ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
            }
        }
    }
}
