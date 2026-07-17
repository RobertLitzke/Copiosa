package com.copiosa.ui.conversation.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.copiosa.ui.theme.skin.AppSkin
import com.copiosa.ui.theme.skin.LocalAppSkin
import com.copiosa.ui.theme.skin.PersonaSpec
import com.copiosa.ui.theme.skin.PersonaStrings

/**
 * Everything the themed chat composables need, resolved once per skin: the persona spec, its
 * localized copy, the tutor identity, and the ready-made text styles. Null spec/strings never
 * reach these composables — [rememberChatSkin] returns null instead and the caller falls back to
 * the stock chat UI.
 */
@Immutable
data class ChatSkin(
    val skin: AppSkin,
    val spec: PersonaSpec,
    val strings: PersonaStrings,
    /** Country tutor name, or the persona's generic voice when no country is themed. */
    val tutorName: String,
    /** Persona chat body style (chat font, size, line height). */
    val chatTextStyle: TextStyle,
    /** Small uppercase-style label for message kickers ("JONAS", "DER PENDLER"). */
    val kickerStyle: TextStyle,
    /** Header title style in the persona's title font. */
    val titleStyle: TextStyle,
)

/** Resolves the active [ChatSkin], or null when the persona is unstyled (stock chat UI). */
@Composable
fun rememberChatSkin(): ChatSkin? {
    val skin = LocalAppSkin.current
    return remember(skin) {
        val spec = skin.personaSpec ?: return@remember null
        val strings = skin.personaStrings ?: return@remember null
        ChatSkin(
            skin = skin,
            spec = spec,
            strings = strings,
            tutorName = skin.countryTheme?.tutorName ?: strings.you,
            chatTextStyle = TextStyle(
                fontFamily = spec.chatFont,
                fontSize = spec.chatSize,
                lineHeight = spec.chatLineHeight,
                color = skin.palette.text,
            ),
            kickerStyle = TextStyle(
                fontFamily = spec.uiFont,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            titleStyle = TextStyle(
                fontFamily = spec.titleFont,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}
