package com.sboard.app.feature.tools

import com.sboard.app.data.ItemType
import com.sboard.app.data.VaultItem

/** Offline password audit: weak, reused and old passwords. */
object PasswordHealth {
    data class Report(val total: Int, val weak: List<VaultItem>, val reused: List<VaultItem>, val old: List<VaultItem>) {
        val score: Int get() = if (total == 0) 100 else (100 - ((weak.size + reused.size) * 100 / (total * 2).coerceAtLeast(1))).coerceIn(0, 100)
    }

    fun analyze(items: List<VaultItem>, now: Long = System.currentTimeMillis()): Report {
        val logins = items.filter { it.type == ItemType.LOGIN && !it.payload.login?.password.isNullOrEmpty() }
        val weak = logins.filter { PasswordStrength.evaluate(it.payload.login!!.password).score <= 2 }
        val groups = logins.groupBy { it.payload.login!!.password }
        val reused = groups.values.filter { it.size > 1 }.flatten()
        val yearMs = 365L * 24 * 60 * 60 * 1000
        val old = logins.filter { val changed = it.payload.login!!.passwordChangedAt.takeIf { t -> t > 0 } ?: it.createdAt; now - changed > yearMs }
        return Report(logins.size, weak, reused, old)
    }
}
