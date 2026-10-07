package com.copiosa.ui.vocab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.repository.ImageCandidate
import com.copiosa.domain.model.CardKind
import com.copiosa.ui.components.LabeledDropdown
import com.copiosa.ui.components.stripInlineMarkdown

// Half the vertical space a TextButton normally takes, so the delete/cancel/save row eats less of a
// dialog that is mostly form fields. Horizontal padding stays at Material's default.
private val DialogButtonHeight = 32.dp
private val DialogButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

/**
 * The user's edits to a note's text, alternate forms, part of speech, card type, gender, country,
 * usage note, and marked flag. Everything here belongs to the word, so it is seen from both of its
 * directions; which directions are studied is not an edit to the word and is set on the detail screen.
 */
data class CardEdits(
    val front: String,
    val back: String,
    val altForms: String?,
    val contextSentence: String?,
    val partOfSpeech: String?,
    val cardKind: CardKind,
    val gender: String?,
    val country: String?,
    val usageNote: String?,
    val marked: Boolean,
)

/**
 * Card editor: the word + translation, its part of speech and type, and an image you can
 * search for and attach. Text/POS/type persist on Save via [onSave]; the image is attached or
 * removed immediately (it's stored straight away), so it shows even if the user cancels the rest.
 */
@Composable
fun EditCardDialog(
    card: FlashcardEntity,
    onSave: (CardEdits) -> Unit,
    onSearchImages: suspend (String) -> List<ImageCandidate>,
    onAttachImage: suspend (ImageCandidate) -> String,
    onRemoveImage: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    // The headword and translation must stay plain — strip any stored inline Markdown so the fields
    // show (and re-save) clean text. Only the usage note / example sentence keep formatting.
    var front by remember { mutableStateOf(stripInlineMarkdown(card.front)) }
    var back by remember { mutableStateOf(stripInlineMarkdown(card.back)) }
    var altForms by remember { mutableStateOf(card.altForms.orEmpty()) }
    var context by remember { mutableStateOf(card.contextSentence.orEmpty()) }
    var usageNote by remember { mutableStateOf(card.usageNote.orEmpty()) }
    var pos by remember { mutableStateOf(card.partOfSpeech?.lowercase()?.ifBlank { null } ?: "unknown") }
    var kind by remember { mutableStateOf(card.cardKind) }
    var gender by remember { mutableStateOf(card.gender?.uppercase()?.ifBlank { null }) }
    // Countries are stored whitespace-free (underscore-joined) for Anki round-tripping; edit them as
    // readable spaced text and re-slug on save.
    var country by remember { mutableStateOf(card.country?.let { countryLabel(it) }.orEmpty()) }
    var marked by remember { mutableStateOf(card.marked) }
    var imageFile by remember { mutableStateOf(card.imageFile) }
    var showPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // The prose fields fold away unless the card already has something in one of them.
    var showExtras by remember {
        mutableStateOf(
            !card.altForms.isNullOrBlank() ||
                !card.contextSentence.isNullOrBlank() ||
                !card.usageNote.isNullOrBlank(),
        )
    }

    val genderChoices = remember(card.targetLanguage) { genderOptions(card.targetLanguage) }
    // Keep an unrecognized stored code selectable so editing never silently drops it.
    val genderChoicesWithCurrent = (genderChoices + gender).distinct()
    val genderNone = stringResource(R.string.gender_none)
    val genderNames = mapOf(
        "M" to stringResource(R.string.gender_masculine),
        "F" to stringResource(R.string.gender_feminine),
        "N" to stringResource(R.string.gender_neuter),
        "MF" to stringResource(R.string.gender_common),
        "MPL" to stringResource(R.string.gender_masculine_plural),
        "FPL" to stringResource(R.string.gender_feminine_plural),
    )
    val genderLabel: (String?) -> String = { code ->
        if (code == null) {
            genderNone
        } else {
            val name = genderNames[code] ?: code
            genderArticle(code, card.targetLanguage)?.let { "$it — $name" } ?: name
        }
    }

    val posLabels = mapOf(
        "" to stringResource(R.string.pos_none),
        "unknown" to stringResource(R.string.pos_unknown),
        "noun" to stringResource(R.string.pos_noun),
        "verb" to stringResource(R.string.pos_verb),
        "adjective" to stringResource(R.string.pos_adjective),
        "adverb" to stringResource(R.string.pos_adverb),
        "phrase" to stringResource(R.string.pos_phrase),
        "expression" to stringResource(R.string.pos_expression),
        "grammar" to stringResource(R.string.pos_grammar),
        "other" to stringResource(R.string.pos_other),
    )
    // Keep any unrecognized stored POS selectable, too.
    val posOptions = (posLabels.keys + pos).distinct().toList()

    val kindLabels = mapOf(
        CardKind.VOCAB to stringResource(R.string.card_kind_vocab),
        CardKind.PHRASE to stringResource(R.string.card_kind_phrase),
        CardKind.SENTENCE to stringResource(R.string.card_kind_sentence),
        CardKind.CONJUGATION to stringResource(R.string.card_kind_conjugation),
        CardKind.GRAMMAR to stringResource(R.string.card_kind_grammar),
        CardKind.CLOZE to stringResource(R.string.card_kind_cloze),
    )

    val rawGloss = if (card.frontIsTarget) card.back else card.front
    val defaultQuery = imageSearchQuery(rawGloss).ifBlank { rawGloss.trim() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_card_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it },
                    label = { Text(stringResource(R.string.edit_card_front)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = back,
                    onValueChange = { back = it },
                    label = { Text(stringResource(R.string.edit_card_back)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledDropdown(
                    label = stringResource(R.string.edit_card_type),
                    options = CardKind.entries,
                    selected = kind,
                    optionLabel = { kindLabels[it] ?: it.name },
                    onSelect = { kind = it },
                )
                LabeledDropdown(
                    label = stringResource(R.string.edit_card_pos),
                    options = posOptions,
                    selected = pos,
                    optionLabel = { posLabels[it] ?: it },
                    onSelect = { pos = it },
                )
                // Grammatical gender only applies to nouns; hide the picker otherwise (it defaults to None).
                if (pos == "noun") {
                    LabeledDropdown(
                        label = stringResource(R.string.edit_card_gender),
                        options = genderChoicesWithCurrent,
                        selected = gender,
                        optionLabel = genderLabel,
                        onSelect = { gender = it },
                    )
                }
                OutlinedTextField(
                    value = country,
                    onValueChange = { country = it },
                    label = { Text(stringResource(R.string.edit_card_country)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { marked = !marked },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (marked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.edit_card_marked),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    Switch(checked = marked, onCheckedChange = { marked = it })
                }

                if (showPicker) {
                    ImagePickerContent(
                        initialQuery = defaultQuery,
                        onSearch = onSearchImages,
                        onAttach = { imageFile = onAttachImage(it) },
                        onAttached = { showPicker = false },
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (imageFile != null) {
                            CardImage(imageFile, modifier = Modifier.weight(1f), maxHeight = 96.dp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { showPicker = true }) {
                                Text(
                                    stringResource(
                                        if (imageFile == null) R.string.image_search_add
                                        else R.string.image_search_change,
                                    ),
                                )
                            }
                            if (imageFile != null) {
                                TextButton(onClick = { onRemoveImage(); imageFile = null }) {
                                    Text(stringResource(R.string.action_remove_image))
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showExtras = !showExtras },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.edit_card_more_fields),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (showExtras) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showExtras) {
                    OutlinedTextField(
                        value = altForms,
                        onValueChange = { altForms = it },
                        label = { Text(stringResource(R.string.edit_card_alt_forms)) },
                        supportingText = { Text(stringResource(R.string.edit_card_alt_forms_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = context,
                        onValueChange = { context = it },
                        label = { Text(stringResource(R.string.edit_card_context)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    OutlinedTextField(
                        value = usageNote,
                        onValueChange = { usageNote = it },
                        label = { Text(stringResource(R.string.edit_card_usage_note)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.heightIn(min = DialogButtonHeight),
                contentPadding = DialogButtonPadding,
                onClick = {
                    onSave(
                        CardEdits(
                            front,
                            back,
                            altForms.trim().ifBlank { null },
                            context.trim().ifBlank { null },
                            pos.ifBlank { null },
                            kind,
                            // Only nouns carry a gender; a non-noun clears any stale value.
                            if (pos == "noun") gender else null,
                            countrySlug(country),
                            usageNote.trim().ifBlank { null },
                            marked,
                        ),
                    )
                    onDismiss()
                },
                enabled = front.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.heightIn(min = DialogButtonHeight),
                        contentPadding = DialogButtonPadding,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.action_delete)) }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = DialogButtonHeight),
                    contentPadding = DialogButtonPadding,
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.vocab_delete)) },
            text = { Text(stringResource(R.string.vocab_delete_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(); onDismiss() }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
