package com.sboard.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.sboard.app.core.i18n.tr
import com.sboard.app.ui.Tab
import com.sboard.app.ui.theme.palette

/** Compact floating bottom navigation in the One UI / iOS spirit. */
@Composable
fun FloatingNavBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val items = listOf(
        Triple(Tab.HOME, Icons.Rounded.Home, tr("Главная")),
        Triple(Tab.KEYBOARD, Icons.Rounded.Keyboard, tr("Клавиатура")),
        Triple(Tab.VAULT, Icons.Rounded.Lock, "sPass"),
        Triple(Tab.TOOLS, Icons.Rounded.GridView, tr("Инструменты")),
        Triple(Tab.PROFILE, Icons.Rounded.Person, tr("Профиль")),
    )
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = palette.navBar.copy(alpha = 0.97f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(0.8.dp, palette.outline),
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
                .shadow(if (palette.isDark) 18.dp else 12.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f)),
        ) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                items.forEach { (tab, icon, label) ->
                    val active = tab == selected
                    val bg by animateColorAsState(if (active) palette.accentSoft else Color.Transparent, tween(220), label = "navbg")
                    val fg by animateColorAsState(if (active) palette.accent else palette.textMuted, tween(220), label = "navfg")
                    val iconOffset by animateDpAsState(if (active) (-1).dp else 0.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "navoff")
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).background(bg)
                            .clickable(remember { MutableInteractionSource() }, null) {
                                if (!active) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(tab)
                            }
                            .padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(icon, label, tint = fg, modifier = Modifier.size(22.dp).offset(y = iconOffset))
                        Spacer(Modifier.height(2.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
                    }
                }
            }
        }
    }
}
