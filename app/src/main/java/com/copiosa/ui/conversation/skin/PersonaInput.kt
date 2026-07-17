package com.copiosa.ui.conversation.skin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.domain.model.Persona

/**
 * The themed input bar: same behavior as the stock InputBar (Enter sends, stop while sending,
 * "+" for a new chat), with the persona's field and send-button chrome and in-voice placeholder.
 */
@Composable
internal fun PersonaInputBar(
    chatSkin: ChatSkin,
    value: String,
    sending: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onNewChat: () -> Unit,
) {
    val persona = chatSkin.spec.persona
    val palette = chatSkin.skin.palette

    fun trySend() {
        if (!sending && value.isNotBlank()) onSend()
    }

    val underlineOnly = persona == Persona.PROFESSOR || persona == Persona.POET
    val dashed = persona == Persona.EXPLORER

    Surface(color = if (persona == Persona.POET) Color.Transparent else palette.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    // Hairline separating the bar from the thread.
                    drawLine(palette.line, Offset(0f, 0f), Offset(size.width, 0f), 1f)
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onNewChat) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.new_chat),
                    tint = palette.textMute,
                )
            }
            val fieldModifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.Enter && !event.isShiftPressed
                    ) {
                        trySend(); true
                    } else {
                        false
                    }
                }
                .let {
                    when {
                        underlineOnly -> it.drawBehind {
                            val y = size.height - 6.dp.toPx()
                            drawLine(palette.line, Offset(8.dp.toPx(), y), Offset(size.width - 8.dp.toPx(), y), 1.5f)
                        }
                        dashed -> it.drawBehind {
                            drawRoundRect(
                                color = palette.textMute,
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                                ),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                    chatSkin.spec.radius.toPx(),
                                ),
                            )
                        }
                        else -> it
                    }
                }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = fieldModifier,
                placeholder = {
                    Text(
                        chatSkin.strings.placeholder,
                        fontFamily = chatSkin.spec.uiFont,
                        fontStyle = if (underlineOnly) FontStyle.Italic else FontStyle.Normal,
                        color = palette.textMute,
                    )
                },
                textStyle = TextStyle(
                    fontFamily = chatSkin.spec.chatFont,
                    fontStyle = if (persona == Persona.PROFESSOR) FontStyle.Italic else FontStyle.Normal,
                ),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { trySend() }),
                shape = RoundedCornerShape(chatSkin.spec.radius),
                colors = if (underlineOnly || dashed) {
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    )
                } else {
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.line,
                    )
                },
            )
            PersonaSendButton(
                chatSkin = chatSkin,
                sending = sending,
                enabled = sending || value.isNotBlank(),
                onClick = { if (sending) onCancel() else trySend() },
            )
        }
    }
}

/** Send/stop button in the persona's hand: outlined ring, comanda circle, compass tilt, sticker. */
@Composable
private fun PersonaSendButton(
    chatSkin: ChatSkin,
    sending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val persona = chatSkin.spec.persona
    val palette = chatSkin.skin.palette
    val icon = if (sending) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send
    val label = stringResource(if (sending) R.string.action_stop else R.string.action_send)

    val shape: Shape = when (persona) {
        Persona.ARTIST, Persona.COMMUTER -> RoundedCornerShape(chatSkin.spec.radius / 2)
        else -> CircleShape
    }
    val outlined = persona == Persona.PROFESSOR || persona == Persona.POET || persona == Persona.EXPLORER
    val borderColor = if (persona == Persona.EXPLORER) palette.text else palette.accent
    val container = if (outlined) Color.Transparent else palette.accent
    val content = if (outlined) borderColor else palette.accentInk

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = container,
        contentColor = content,
        border = if (outlined) androidx.compose.foundation.BorderStroke(1.5.dp, borderColor) else null,
        modifier = Modifier
            // The Explorer's compass tilt and the Artist's askew stamp.
            .let {
                when (persona) {
                    Persona.EXPLORER -> it.graphicsLayer { rotationZ = -45f }
                    Persona.ARTIST -> it.graphicsLayer { rotationZ = 6f }
                    else -> it
                }
            },
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier
                .padding(10.dp)
                .let { if (persona == Persona.EXPLORER) it.graphicsLayer { rotationZ = 45f } else it },
        )
    }
}
