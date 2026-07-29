package com.copiosa.ui.flashcards

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.copiosa.R
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.model.Cloze
import com.copiosa.domain.model.ReviewDirection
import com.copiosa.ui.components.CefrBadge
import com.copiosa.ui.components.MarkdownText
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.components.llmErrorText
import com.copiosa.ui.components.readingStyle
import com.copiosa.ui.components.stripInlineMarkdown
import com.copiosa.ui.theme.SerifBody
import com.copiosa.ui.theme.SerifTitle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Detail for a single vocabulary word: translation, an on-demand colloquial Markdown explanation
 * with POS/CEFR chips, an example sentence, and a link back to the chat it was first seen in.
 */
@Composable
fun VocabularyDetailScreen(
    onBack: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    viewModel: VocabularyDetailViewModel = hiltViewModel(),
) {
    val card by viewModel.card.collectAsState()
    val source by viewModel.sourceConversation.collectAsState()
    val ui by viewModel.ui.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                stringResource(R.string.flashcards_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            card?.let { c ->
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                }
                IconButton(onClick = viewModel::toggleMarked) {
                    Icon(
                        if (c.marked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(
                            if (c.marked) R.string.action_unmark else R.string.action_mark,
                        ),
                        tint = if (c.marked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::toggleSuspended) {
                    Icon(
                        Icons.Filled.AcUnit,
                        contentDescription = stringResource(
                            if (c.suspended) R.string.action_unfreeze else R.string.action_freeze,
                        ),
                        tint = if (c.suspended) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.vocab_delete))
                }
            }
        }

        val c = card
        if (c == null) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            DetailContent(
                card = c,
                sourceTitle = source?.title,
                explaining = ui.explaining,
                errorText = ui.error?.let { llmErrorText(it) },
                onExplain = viewModel::explain,
                onOpenConversation = { source?.let { onOpenConversation(it.id) } },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.vocab_delete)) },
            text = { Text(stringResource(R.string.vocab_delete_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (editing) {
        card?.let { c ->
            EditCardDialog(
                card = c,
                onSave = viewModel::updateCard,
                onSearchImages = viewModel::searchImages,
                onAttachImage = viewModel::attachImage,
                onRemoveImage = viewModel::removeImage,
                onDismiss = { editing = false },
            )
        }
    }
}

@Composable
private fun DetailContent(
    card: FlashcardEntity,
    sourceTitle: String?,
    explaining: Boolean,
    errorText: String?,
    onExplain: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val clozeText = Cloze.source(card.front, card.back)
        if (clozeText != null) {
            // Cloze card: show the fully-filled sentence with its answer(s) called out.
            Text(Cloze.answer(clozeText), style = SerifTitle.copy(fontSize = 22.sp, lineHeight = 30.sp))
            val answers = Cloze.activeAnswers(clozeText, card.ankiOrd?.plus(1))
            if (answers.isNotEmpty()) {
                Text(
                    stringResource(R.string.cloze_answer_label, answers.joinToString(", ")),
                    style = SerifBody.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Text(stripInlineMarkdown(card.front), style = SerifTitle.copy(fontSize = 28.sp, lineHeight = 34.sp))
            // The country a word is specific to (if the user set one) sits just under the headword.
            card.country?.takeIf { it.isNotBlank() }?.let {
                Text(
                    countryLabel(it),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val article = genderArticle(card.gender, card.targetLanguage)
            if (article != null ||
                !card.partOfSpeech.isNullOrBlank() || !card.cefrLevel.isNullOrBlank()
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GenderChip(card.gender, card.targetLanguage)
                    card.partOfSpeech?.takeIf { it.isNotBlank() }?.let { PosChip(it) }
                    card.cefrLevel?.takeIf { it.isNotBlank() }?.let { CefrBadge(it) }
                }
            }

            card.altForms?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "= $it",
                    style = SerifBody.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (card.back.isNotBlank()) {
                // The translation is a plain gloss — never Markdown (only the usage note / explanation are).
                Text(
                    stripInlineMarkdown(card.back),
                    style = readingStyle(MaterialTheme.typography.bodyLarge),
                )
            }

            card.usageNote?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.size(4.dp))
                SectionLabel(stringResource(R.string.vocab_usage_note_label))
                MarkdownText(note)
            }
        }

        CardImage(card.imageFile)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val explanation = card.explanation
        if (!explanation.isNullOrBlank()) {
            MarkdownText(explanation)
        } else {
            Button(onClick = onExplain, enabled = !explaining) {
                if (explaining) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.action_generate_explanation))
            }
        }
        errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        card.contextSentence?.takeIf { it.isNotBlank() }?.let { sentence ->
            Spacer(Modifier.size(4.dp))
            SectionLabel(stringResource(R.string.vocab_example_label))
            Text(
                sentence,
                style = SerifBody.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (sourceTitle != null) {
            ChatReferenceCard(title = sourceTitle, onClick = onOpenConversation)
        }

        // Secondary spaced-repetition metadata, de-emphasised at the bottom in a faint inset box.
        CardMetaSection(card)
    }
}

/**
 * The card's spaced-repetition state, derived entirely from its existing schedule fields (no new
 * tracking): status, age, review count, next due, current interval, and last-reviewed time. Rendered
 * as a faint, muted inset box at the bottom — it's secondary to the word's actual content.
 */
@Composable
private fun CardMetaSection(card: FlashcardEntity) {
    val now = System.currentTimeMillis()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionLabel(stringResource(R.string.card_details_label))
            MetaRow(stringResource(R.string.card_meta_status), statusOf(card).label())
            MetaRow(stringResource(R.string.card_meta_direction), directionLabel(card))
            MetaRow(stringResource(R.string.card_meta_added), relativeTime(card.createdAt))
            MetaRow(stringResource(R.string.card_meta_reviews), card.repetitions.toString())
            MetaRow(
                stringResource(R.string.card_meta_due),
                if (card.dueAt <= now) stringResource(R.string.card_meta_due_now) else relativeTime(card.dueAt),
            )
            if (card.intervalDays >= 1.0) {
                val days = card.intervalDays.roundToInt()
                MetaRow(
                    stringResource(R.string.card_meta_interval),
                    if (days <= 1) stringResource(R.string.card_meta_interval_day)
                    else stringResource(R.string.card_meta_interval_days, days),
                )
            }
            MetaRow(
                stringResource(R.string.card_meta_last_reviewed),
                card.lastReviewedAt?.let { relativeTime(it) } ?: stringResource(R.string.card_meta_never),
            )
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A readable review-direction label, e.g. "Both directions" or "Spanish first". */
@Composable
private fun directionLabel(card: FlashcardEntity): String = when (card.reviewDirection) {
    ReviewDirection.BOTH -> stringResource(R.string.review_direction_both)
    ReviewDirection.TARGET_FIRST ->
        stringResource(R.string.review_direction_first, langDisplayName(card.targetLanguage))
    ReviewDirection.NATIVE_FIRST ->
        stringResource(R.string.review_direction_first, langDisplayName(card.nativeLanguage))
}

private fun langDisplayName(tag: String): String =
    Locale.forLanguageTag(tag).displayLanguage.replaceFirstChar { it.uppercase() }.ifBlank { tag }

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

@Composable
private fun ChatReferenceCard(title: String, onClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.vocab_seen_in_chat, title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.vocab_return_to_context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
