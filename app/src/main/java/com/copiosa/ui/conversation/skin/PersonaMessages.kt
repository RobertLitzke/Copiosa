package com.copiosa.ui.conversation.skin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copiosa.data.local.MessageEntity
import com.copiosa.domain.model.Persona
import com.copiosa.domain.model.Role
import com.copiosa.ui.components.SelectableMessage
import com.copiosa.ui.components.WordColorizer
import com.copiosa.ui.conversation.AssistantActionRow
import com.copiosa.ui.theme.skin.ChatLayout

/**
 * One message in the persona's chat layout: paper (ruled text blocks), bubbles (avatar cards), or
 * transcript (left-bar entries). Selection/translation and the assistant action row work exactly
 * as in the stock layout — only the dressing changes.
 */
@Composable
internal fun PersonaMessageItem(
    message: MessageEntity,
    chatSkin: ChatSkin,
    index: Int,
    colorizer: WordColorizer,
    isActiveSelection: Boolean,
    onSelected: (Long, String, Float) -> Unit,
    onCleared: () -> Unit,
    onSendToAudio: () -> Unit,
    /** BCP-47 tag of the language the tutor writes in: read aloud in it, and hyphenated by it on paper. */
    language: String,
) {
    when (chatSkin.spec.layout) {
        ChatLayout.PAPER -> PaperMessage(
            message, chatSkin, colorizer, isActiveSelection, onSelected, onCleared, onSendToAudio, language,
        )
        ChatLayout.BUBBLES -> BubbleMessage(
            message, chatSkin, index, colorizer, isActiveSelection, onSelected, onCleared, onSendToAudio,
            language,
        )
        ChatLayout.TRANSCRIPT -> TranscriptMessage(
            message, chatSkin, colorizer, isActiveSelection, onSelected, onCleared, onSendToAudio, language,
        )
    }
}

/** Assistant body: selectable, translated, colorized — shared by all three layouts. */
@Composable
private fun AssistantBody(
    message: MessageEntity,
    chatSkin: ChatSkin,
    colorizer: WordColorizer,
    isActiveSelection: Boolean,
    onSelected: (Long, String, Float) -> Unit,
    onCleared: () -> Unit,
    // Non-null sets the text as a justified block, hyphenated in this language.
    justifyIn: String? = null,
) {
    SelectableMessage(
        content = message.content,
        colorizer = colorizer,
        isActive = isActiveSelection,
        onSelected = { text, anchorY -> onSelected(message.id, text, anchorY) },
        onCleared = onCleared,
        styleOverride = if (justifyIn != null) {
            // Justifying without hyphenation can only close a line by stretching its spaces, which
            // on a phone-width column leaves rivers. Breaking words, and choosing breaks over the
            // whole paragraph rather than line by line, is what makes the block even. The locale is
            // the text's, not the app's: an English UI would otherwise hyphenate Spanish by English
            // rules.
            chatSkin.chatTextStyle.copy(
                textAlign = TextAlign.Justify,
                hyphens = Hyphens.Auto,
                lineBreak = LineBreak.Paragraph,
                localeList = LocaleList(justifyIn),
            )
        } else {
            chatSkin.chatTextStyle
        },
    )
}

// ---------------------------------------------------------------------------
// Paper (Professor, Poet): no bubbles, justified text blocks.
// ---------------------------------------------------------------------------

@Composable
private fun PaperMessage(
    message: MessageEntity,
    chatSkin: ChatSkin,
    colorizer: WordColorizer,
    isActiveSelection: Boolean,
    onSelected: (Long, String, Float) -> Unit,
    onCleared: () -> Unit,
    onSendToAudio: () -> Unit,
    language: String,
) {
    val isTutor = message.role != Role.USER
    // With no name labels, the learner's turn is set apart by an indent and a dimmed ink.
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = if (isTutor) 0.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (isTutor) {
            AssistantBody(
                message, chatSkin, colorizer, isActiveSelection, onSelected, onCleared,
                justifyIn = language,
            )
            AssistantActionRow(message = message, language = language, onSendToAudio = onSendToAudio)
        } else {
            Text(
                message.content,
                style = chatSkin.chatTextStyle,
                color = chatSkin.skin.palette.textDim,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Bubbles (Chef, Artist): avatars + cards; the Artist's stickers sit slightly tilted.
// ---------------------------------------------------------------------------

/** Dashed border in the bubble's own shape (the Chef's recipe card). */
private fun Modifier.dashedBubbleBorder(color: Color, radius: Dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(
            width = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
    )
}

/** Hard offset shadow behind the bubble (the Artist's comic ink). */
private fun Modifier.offsetShadow(color: Color, radius: Dp): Modifier = drawBehind {
    translate(3.dp.toPx(), 3.dp.toPx()) {
        drawRoundRect(
            color = color,
            size = Size(size.width, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
        )
    }
}

@Composable
private fun BubbleMessage(
    message: MessageEntity,
    chatSkin: ChatSkin,
    index: Int,
    colorizer: WordColorizer,
    isActiveSelection: Boolean,
    onSelected: (Long, String, Float) -> Unit,
    onCleared: () -> Unit,
    onSendToAudio: () -> Unit,
    language: String,
) {
    val palette = chatSkin.skin.palette
    val isTutor = message.role != Role.USER
    val artist = chatSkin.spec.persona == Persona.ARTIST
    val r = chatSkin.spec.bubbleRadius
    val shape = if (isTutor) {
        RoundedCornerShape(r, r, r, 6.dp)
    } else {
        RoundedCornerShape(r, r, 6.dp, r)
    }

    var bubble = Modifier
        .fillMaxWidth(0.86f)
        // The Artist's stickers alternate a slight tilt; everyone else sits straight.
        .let { if (artist) it.graphicsLayer { rotationZ = if (index % 2 == 0) 0.8f else -0.8f } else it }
    bubble = when {
        artist -> bubble.offsetShadow(palette.text.copy(alpha = 0.22f), r)
        isTutor -> bubble.dashedBubbleBorder(palette.accentSoftInk.copy(alpha = 0.5f), r)
        else -> bubble
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        if (isTutor) {
            PersonaAvatar(chatSkin, size = 30.dp)
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Column(
            horizontalAlignment = if (isTutor) Alignment.Start else Alignment.End,
        ) {
            Surface(
                shape = shape,
                color = if (isTutor) palette.surface else palette.accentSoft,
                contentColor = if (isTutor) palette.text else palette.accentSoftInk,
                border = if (artist) androidx.compose.foundation.BorderStroke(1.5.dp, palette.text) else null,
                modifier = bubble,
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (isTutor) {
                        AssistantBody(
                            message, chatSkin, colorizer, isActiveSelection, onSelected, onCleared,
                        )
                    } else {
                        Text(message.content, style = chatSkin.chatTextStyle, color = palette.accentSoftInk)
                    }
                }
            }
            if (isTutor) {
                AssistantActionRow(message = message, language = language, onSendToAudio = onSendToAudio)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Transcript (Commuter, Explorer): dense entries, speaker carried by the left bar's colour.
// ---------------------------------------------------------------------------

@Composable
private fun TranscriptMessage(
    message: MessageEntity,
    chatSkin: ChatSkin,
    colorizer: WordColorizer,
    isActiveSelection: Boolean,
    onSelected: (Long, String, Float) -> Unit,
    onCleared: () -> Unit,
    onSendToAudio: () -> Unit,
    language: String,
) {
    val palette = chatSkin.skin.palette
    val isTutor = message.role != Role.USER
    val explorer = chatSkin.spec.persona == Persona.EXPLORER
    val barWidth = if (explorer) 2.dp else 3.dp

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .fillMaxHeight()
                    .drawBehind {
                        drawRect(if (isTutor) palette.accent else palette.line)
                    },
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (isTutor) {
                    AssistantBody(
                        message, chatSkin, colorizer, isActiveSelection, onSelected, onCleared,
                    )
                    AssistantActionRow(message = message, language = language, onSendToAudio = onSendToAudio)
                } else {
                    Text(
                        message.content,
                        style = chatSkin.chatTextStyle,
                        color = palette.textDim,
                    )
                }
            }
        }
        if (explorer) {
            // Dotted trail divider between logbook entries.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(2.dp)
                    .drawBehind {
                        drawLine(
                            palette.line,
                            Offset(0f, size.height / 2),
                            Offset(size.width, size.height / 2),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx())),
                        )
                    },
            )
        }
    }
}
