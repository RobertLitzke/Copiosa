package com.copiosa.ui.cloze

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.domain.cloze.ClozeSessionItem
import com.copiosa.domain.cloze.ClozeTyped
import com.copiosa.domain.cloze.TypedVerdict
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.routines.activities.HeaderAction
import com.copiosa.ui.routines.activities.QuestionHeader
import com.copiosa.ui.theme.SourceSerif

/**
 * The cloze bank with the options taken away: the sentence, its blank, and a keyboard.
 *
 * Picking one word out of four is recognition. Typing it is production — nothing on screen to
 * recognise — and it is the half the multiple-choice sitting cannot reach. A sentence alone rarely
 * pins down one word, though, so the meaning is on screen from the start: the sentence's translation
 * when the bank has one, otherwise the answer's own gloss. That makes the task "say this in the
 * language", in context.
 *
 * Judged by [ClozeTyped]: a missing accent is right and shown corrected; anything else is a miss
 * the learner can overrule, because a keyboard slip or a fair synonym is theirs to call.
 */
@Composable
internal fun TypedClozeActivity(
    items: List<ClozeSessionItem>,
    onAnswer: (index: Int, correct: Boolean, latencyMs: Long) -> Unit,
    onComplete: () -> Unit,
    title: String,
    onExit: () -> Unit,
    afterReveal: (@Composable (index: Int) -> Unit)? = null,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }
    var at by remember(items) { mutableIntStateOf(0) }
    val outcomes = remember(items) { mutableStateListOf<Boolean?>().apply { repeat(items.size) { add(null) } } }
    val item = items[at.coerceIn(items.indices)]

    var typed by remember(at) { mutableStateOf("") }
    var verdict by remember(at) { mutableStateOf<TypedVerdict?>(null) }
    var overruled by remember(at) { mutableStateOf(false) }
    var hinted by remember(at) { mutableStateOf(false) }
    val shownAt = remember(at) { System.currentTimeMillis() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(at) { runCatching { focus.requestFocus() } }

    val checked = verdict
    val right = checked != null && (checked != TypedVerdict.WRONG || overruled)

    fun check() {
        if (verdict != null || typed.isBlank()) return
        verdict = ClozeTyped.judge(typed, item.answer)
    }

    // The answer is reported when the learner moves on, not when it is checked: until then a miss
    // can still be overruled, and what is recorded must be what they finally said it was.
    fun advance() {
        if (checked != null) {
            outcomes[at] = right
            onAnswer(at, right, System.currentTimeMillis() - shownAt)
        }
        if (at + 1 >= items.size) onComplete() else at++
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        QuestionHeader(
            title = title,
            subtitle = null,
            onExit = onExit,
            index = at,
            total = items.size,
            outcomes = outcomes,
            action = if (checked == null) HeaderAction(R.string.action_skip, filled = false) { advance() } else null,
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            SectionLabel(stringResource(R.string.cloze_type_prompt))
            Spacer(Modifier.height(10.dp))

            val accent = MaterialTheme.colorScheme.primary
            Text(
                buildAnnotatedString {
                    val split = item.sentence.indexOf(item.answer)
                    if (checked == null || split < 0) {
                        append(item.prompt)
                    } else {
                        append(item.sentence.substring(0, split))
                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(item.answer) }
                        append(item.sentence.substring(split + item.answer.length))
                    }
                },
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = SourceSerif),
            )

            // What the blank means. Without it a typed cloze is a guessing game: many words fit most
            // sentences, and only one of them is the one being asked for.
            val meaning = item.translation?.takeIf { it.isNotBlank() }
                ?: item.glosses.getOrNull(item.answerIndex)?.takeIf { it.isNotBlank() }
            if (meaning != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    meaning,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SourceSerif, fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { if (checked == null) typed = it },
                enabled = checked == null,
                singleLine = true,
                label = { Text(stringResource(R.string.cloze_type_label)) },
                supportingText = if (hinted) ({ Text(ClozeTyped.firstLetterHint(item.answer)) }) else null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { check() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )

            if (checked == null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::check, enabled = typed.isNotBlank()) {
                        Text(stringResource(R.string.cloze_type_check))
                    }
                    if (!hinted) {
                        TextButton(onClick = { hinted = true }) { Text(stringResource(R.string.cloze_type_hint)) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (checked != null) {
            val scheme = MaterialTheme.colorScheme
            Column(Modifier.fillMaxWidth().background(scheme.surface)) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (right) scheme.primary else scheme.tertiary))
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            when {
                                checked == TypedVerdict.ACCENTS -> R.string.cloze_type_accents
                                right -> R.string.question_right
                                else -> R.string.cloze_type_wrong
                            },
                            item.answer,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (checked == TypedVerdict.WRONG && !overruled) {
                        OutlinedButton(onClick = { overruled = true }) {
                            Text(stringResource(R.string.cloze_type_overrule))
                        }
                    }
                    afterReveal?.invoke(at)
                    Button(
                        onClick = ::advance,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(stringResource(R.string.routine_continue))
                    }
                }
            }
        }
    }
}
