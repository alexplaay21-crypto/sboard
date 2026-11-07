package org.sboard.keyboard

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** One insertable value of a Vault item, e.g. "Password" of "GitHub". Values are read lazily. */
data class KeyboardVaultField(
    val key: String,
    val label: String,
    val sensitive: Boolean,
)

data class KeyboardVaultEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val suggested: Boolean,
    val fields: List<KeyboardVaultField>,
)

/** Implemented by the sBoard app; the keyboard never touches keys or the database directly. */
interface VaultKeyboardProvider {
    val unlocked: StateFlow<Boolean>
    val enabled: Boolean
    suspend fun entries(targetPackage: String?, query: String): List<KeyboardVaultEntry>
    /** Returns the value for a non-sensitive field, or a sensitive one after confirmation. */
    suspend fun value(entryId: String, fieldKey: String): String?
    /** Whether a sensitive value must be confirmed with biometrics / screen lock. */
    fun requiresStrongConfirmation(): Boolean
    /** Opens the unlock screen (biometrics or master password). */
    fun requestUnlock(context: Context)
    /** Opens biometric confirmation; on success the value is queued with [SBoardVaultBridge.queue]. */
    fun requestConfirmedInsert(context: Context, entryId: String, fieldKey: String, targetPackage: String?)
    fun lock()
    fun text(key: String): String
    /** Offers to save a password typed with the keyboard in [packageName] (Samsung-Pass-style prompt). */
    fun offerPasswordSave(context: Context, packageName: String, username: String, password: String) {}
}

/** Online translator supplied by the sBoard app (network use only on explicit tap). */
interface KeyboardTranslator {
    /** (code, display name) pairs. */
    val languages: List<Pair<String, String>>
    suspend fun translate(text: String, from: String, to: String): String
    fun text(key: String): String
    fun loadPair(): Pair<String, String>
    fun savePair(from: String, to: String)
    /** Recently chosen target languages, most recent first. */
    fun recent(): List<String> = emptyList()
    fun rememberRecent(code: String) {}
}

object SBoardVaultBridge {
    @Volatile var provider: VaultKeyboardProvider? = null
    @Volatile var translator: KeyboardTranslator? = null
    /** Live translation bar above the keys (Samsung Keyboard style). */
    val translateBarVisible = kotlinx.coroutines.flow.MutableStateFlow(false)

    private data class Pending(val text: String, val targetPackage: String?, val createdAt: Long)
    @Volatile private var pending: Pending? = null

    /** Queues text to type once the original text field regains focus (max. 60 seconds). */
    fun queue(text: String, targetPackage: String?) {
        pending = Pending(text, targetPackage, System.currentTimeMillis())
    }

    /** Releases the queued text only into the same app the user confirmed it for. */
    fun consumePending(packageName: String?): String? {
        val p = pending ?: return null
        if (System.currentTimeMillis() - p.createdAt > 60_000) { pending = null; return null }
        if (p.targetPackage != null && p.targetPackage != packageName) return null
        pending = null
        return p.text
    }
}
