package com.sboard.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ItemType {
    @SerialName("login") LOGIN,
    @SerialName("card") CARD,
    @SerialName("identity") IDENTITY,
    @SerialName("note") NOTE,
    @SerialName("totp") TOTP,
    @SerialName("passkey") PASSKEY,
}

@Serializable
data class LoginData(
    val username: String = "",
    val password: String = "",
    val urls: List<String> = emptyList(),
    /** Android package names linked to this login (from Autofill). */
    val packageNames: List<String> = emptyList(),
    /** Optional TOTP secret stored with the login. */
    val totp: TotpData? = null,
    val passwordChangedAt: Long = 0L,
)

@Serializable
data class CardData(
    val cardholder: String = "",
    val number: String = "",
    val expMonth: String = "",
    val expYear: String = "",
    val cvv: String = "",
    val pin: String = "",
    val brand: String = "",
)

@Serializable
data class IdentityData(
    val firstName: String = "",
    val middleName: String = "",
    val lastName: String = "",
    val email: String = "",
    val phone: String = "",
    val company: String = "",
    val address1: String = "",
    val address2: String = "",
    val city: String = "",
    val region: String = "",
    val postalCode: String = "",
    val country: String = "",
    val birthDate: String = "",
    val documentNumber: String = "",
) {
    val fullName: String get() = listOf(firstName, middleName, lastName).filter { it.isNotBlank() }.joinToString(" ")
    val fullAddress: String get() = listOf(address1, address2, city, region, postalCode, country)
        .filter { it.isNotBlank() }.joinToString(", ")
}

@Serializable
data class TotpData(
    val secret: String = "",
    val issuer: String = "",
    val account: String = "",
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
)

/** Portable WebAuthn credential. The private key lives only inside the encrypted payload. */
@Serializable
data class PasskeyData(
    val rpId: String,
    val rpName: String = "",
    val userName: String = "",
    val userDisplayName: String = "",
    val userHandle: String,          // base64url
    val credentialId: String,        // base64url
    val privateKeyPkcs8: String,     // base64url, ES256 PKCS#8
    val publicKeyCose: String,       // base64url
    val signCount: Long = 0,
    val lastUsedAt: Long = 0,
)

@Serializable
data class CustomField(val name: String, val value: String, val hidden: Boolean = false)

/** Everything inside this class is encrypted at rest and in the cloud backup. */
@Serializable
data class VaultItemPayload(
    val type: ItemType,
    val title: String,
    val favorite: Boolean = false,
    val notes: String = "",
    val login: LoginData? = null,
    val card: CardData? = null,
    val identity: IdentityData? = null,
    val totp: TotpData? = null,
    val passkey: PasskeyData? = null,
    val customFields: List<CustomField> = emptyList(),
) {
    fun normalized(): VaultItemPayload = copy(
        title = title.trim(),
        login = if (type == ItemType.LOGIN) login ?: LoginData() else login,
        card = if (type == ItemType.CARD) card ?: CardData() else card,
        identity = if (type == ItemType.IDENTITY) identity ?: IdentityData() else identity,
        totp = if (type == ItemType.TOTP) totp ?: TotpData() else totp,
    )

    /** Text used for local search; never leaves memory. */
    fun searchText(): String = buildString {
        append(title).append(' ')
        login?.let { append(it.username).append(' '); it.urls.forEach { u -> append(u).append(' ') } }
        identity?.let { append(it.fullName).append(' ').append(it.email).append(' ').append(it.phone).append(' ') }
        card?.let { append(it.cardholder).append(' ').append(it.brand).append(' ').append(it.number.takeLast(4)).append(' ') }
        totp?.let { append(it.issuer).append(' ').append(it.account).append(' ') }
        passkey?.let { append(it.rpId).append(' ').append(it.userName).append(' ') }
        append(notes)
    }.lowercase()

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
        fun decode(bytes: ByteArray): VaultItemPayload = json.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
        fun encode(payload: VaultItemPayload): ByteArray = json.encodeToString(serializer(), payload).toByteArray(Charsets.UTF_8)
    }
}

data class VaultItem(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val payload: VaultItemPayload,
) {
    val type: ItemType get() = payload.type
    val title: String get() = payload.title
    val favorite: Boolean get() = payload.favorite

    /** Human-readable secondary line for lists. */
    fun subtitle(): String = when (type) {
        ItemType.LOGIN -> payload.login?.username?.ifBlank { payload.login.urls.firstOrNull().orEmpty() }.orEmpty()
        ItemType.CARD -> payload.card?.let { c -> listOf(c.brand, c.number.takeLast(4).let { if (it.isBlank()) "" else "•••• $it" }).filter { it.isNotBlank() }.joinToString(" ") }.orEmpty()
        ItemType.IDENTITY -> payload.identity?.let { it.fullName.ifBlank { it.email } }.orEmpty()
        ItemType.NOTE -> payload.notes.lineSequence().firstOrNull().orEmpty().take(60)
        ItemType.TOTP -> payload.totp?.let { listOf(it.issuer, it.account).filter { s -> s.isNotBlank() }.joinToString(" · ") }.orEmpty()
        ItemType.PASSKEY -> payload.passkey?.let { "${it.userName.ifBlank { it.userDisplayName }} · ${it.rpId}" }.orEmpty()
    }

    /** Hosts associated with this item (for Autofill / keyboard matching). */
    fun hosts(): List<String> = payload.login?.urls.orEmpty().mapNotNull { DomainMatcher.hostOf(it) } +
        listOfNotNull(payload.passkey?.rpId)
}
