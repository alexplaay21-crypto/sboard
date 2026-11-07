package com.sboard.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.sboard.app.data.ItemType

enum class Tab { HOME, KEYBOARD, VAULT, TOOLS, PROFILE }

sealed interface Route {
    data class ItemDetail(val id: String) : Route
    data class ItemEdit(val id: String?, val type: ItemType, val prefillTotpUri: String? = null) : Route
    data class ItemHistory(val id: String) : Route
    data object Trash : Route
    data object Generator : Route
    data object Strength : Route
    data object Translator : Route
    data object Clipboard : Route
    data object Security : Route
    data object Sync : Route
    data object Devices : Route
    data object Appearance : Route
    data object About : Route
    data object PasswordHealth : Route
    data object KeyboardTheme : Route
}

/** Small state-based navigator: a selected tab plus a back stack of detail routes. */
@Stable
class Navigator {
    var tab by mutableStateOf(Tab.HOME)
    val stack = mutableStateListOf<Route>()
    var vaultFilter by mutableStateOf("all")
    var vaultQuery by mutableStateOf("")

    val current: Route? get() = stack.lastOrNull()

    fun push(route: Route) { stack.add(route) }
    fun pop(): Boolean = if (stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); true } else false
    fun replace(route: Route) { if (stack.isNotEmpty()) stack[stack.lastIndex] = route else stack.add(route) }
    fun select(tab: Tab) { stack.clear(); this.tab = tab }
    fun openVault(filter: String = "all", query: String = "") { vaultFilter = filter; vaultQuery = query; select(Tab.VAULT) }
}
