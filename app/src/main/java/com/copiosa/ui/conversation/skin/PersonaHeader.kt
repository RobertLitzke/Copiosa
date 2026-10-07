package com.copiosa.ui.conversation.skin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copiosa.R
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.Persona
import com.copiosa.domain.model.ProviderId
import com.copiosa.ui.conversation.ChatConfig
import com.copiosa.ui.conversation.ChatOverflowMenu
import com.copiosa.ui.conversation.ModelMenu

/** Bottom border in the persona's hand: rule, perforation, hairline, platform band, or trail. */
internal fun Modifier.personaHeaderBorder(persona: Persona, palette: com.copiosa.ui.theme.skin.CountryPalette): Modifier =
    drawBehind {
        val w = size.width
        val h = size.height
        fun line(color: Color, stroke: Float, y: Float = h - stroke / 2, dash: PathEffect? = null) {
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = stroke,
                pathEffect = dash,
            )
        }
        when (persona) {
            Persona.PROFESSOR -> line(palette.accent, 2.dp.toPx())
            Persona.CHEF -> line(
                palette.accentSoftInk.copy(alpha = 0.35f),
                2.dp.toPx(),
                dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
            )
            Persona.POET -> line(palette.line, 1f)
            Persona.ARTIST -> {}
            Persona.COMMUTER -> {
                // Platform band along the top; hairline along the bottom.
                drawLine(palette.accent, Offset(0f, 1.5.dp.toPx()), Offset(w, 1.5.dp.toPx()), 3.dp.toPx())
                line(palette.line, 1f)
            }
            Persona.EXPLORER -> {
                // Double border: two rules with a gap.
                line(palette.text, 1.5.dp.toPx(), y = h - 4.5.dp.toPx())
                line(palette.text, 1.5.dp.toPx(), y = h - 1.dp.toPx())
            }
            Persona.UNSTYLED -> {}
        }
    }

/** Tutor avatar: a circle with the tutor's initial, shaped and outlined in the persona's hand. */
@Composable
internal fun PersonaAvatar(chatSkin: ChatSkin, size: Dp = 34.dp, onAccent: Boolean = false) {
    val palette = chatSkin.skin.palette
    val initial = chatSkin.tutorName.take(1).uppercase()
    val shape = when (chatSkin.spec.persona) {
        // The Artist's blobby sticker avatar.
        Persona.ARTIST -> RoundedCornerShape(50, 40, 55, 45)
        else -> CircleShape
    }
    val outlined = chatSkin.spec.persona in setOf(Persona.PROFESSOR, Persona.EXPLORER)
    val bg = when {
        outlined -> Color.Transparent
        onAccent -> palette.accentInk
        else -> palette.accent
    }
    val fg = when {
        outlined -> if (onAccent) palette.accentInk else palette.accent
        onAccent -> palette.accent
        else -> palette.accentInk
    }
    val borderColor = if (onAccent) palette.accentInk else palette.accent
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(bg)
            .let { if (outlined) it.border(1.5.dp, borderColor, shape) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            fontFamily = chatSkin.spec.titleFont,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.45f).sp,
            color = fg,
        )
    }
}

/**
 * Themed chat header: back, the chat title, the level pill (opens the model menu), and the overflow
 * menu — same actions as the stock ChatTopBar, in the persona's chrome.
 */
@Composable
internal fun PersonaChatHeader(
    chatSkin: ChatSkin,
    config: ChatConfig,
    title: String,
    showMenu: Boolean,
    starred: Boolean,
    highlightWords: Boolean,
    onBack: () -> Unit,
    onLevel: (Cefr) -> Unit,
    onProvider: (ProviderId) -> Unit,
    onOpenModelSettings: () -> Unit,
    onToggleStar: () -> Unit,
    onMarkUnread: () -> Unit,
    onToggleHighlightWords: () -> Unit,
    onDelete: () -> Unit,
) {
    val persona = chatSkin.spec.persona
    val palette = chatSkin.skin.palette
    var menuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }

    val headerColor = when (persona) {
        Persona.CHEF -> palette.accentSoft
        Persona.ARTIST -> palette.accent
        Persona.POET -> Color.Transparent
        else -> palette.surface
    }
    val contentColor = when (persona) {
        Persona.CHEF -> palette.accentSoftInk
        Persona.ARTIST -> palette.accentInk
        else -> palette.text
    }
    val headerHeight = if (persona == Persona.POET) 64.dp else 58.dp

    Surface(
        color = headerColor,
        contentColor = contentColor,
        modifier = Modifier
            .fillMaxWidth()
            // Compose has no skew; a slight rotation gives the Artist's off-kilter chrome.
            .let { if (persona == Persona.ARTIST) it.graphicsLayer { rotationZ = -0.6f } else it },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .personaHeaderBorder(persona, palette)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = chatSkin.titleStyle,
                fontStyle = if (persona == Persona.POET) FontStyle.Italic else chatSkin.titleStyle.fontStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Box {
                LevelPill(chatSkin, config, onClick = { menuOpen = true })
                ModelMenu(
                    expanded = menuOpen,
                    config = config,
                    onDismiss = { menuOpen = false },
                    onLevel = onLevel,
                    onProvider = onProvider,
                    onOpenModelSettings = onOpenModelSettings,
                )
            }
            if (showMenu) {
                Box {
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                    }
                    ChatOverflowMenu(
                        expanded = overflowOpen,
                        starred = starred,
                        highlightWords = highlightWords,
                        onDismiss = { overflowOpen = false },
                        onToggleStar = onToggleStar,
                        onMarkUnread = onMarkUnread,
                        onToggleHighlightWords = onToggleHighlightWords,
                        onDelete = onDelete,
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

/** The mocks' level badge ("B2" on the secondary accent); tapping opens the full model menu. */
@Composable
private fun LevelPill(chatSkin: ChatSkin, config: ChatConfig, onClick: () -> Unit) {
    val palette = chatSkin.skin.palette
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(chatSkin.spec.radius / 2),
        color = palette.accent2,
        contentColor = palette.accent2Ink,
    ) {
        Text(
            config.level.name,
            fontFamily = chatSkin.spec.uiFont,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
