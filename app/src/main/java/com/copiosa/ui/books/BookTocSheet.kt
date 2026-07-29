package com.copiosa.ui.books

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.domain.book.BookChapter
import com.copiosa.domain.book.ChapterProgress

/**
 * The reader's table of contents. Chapters come from grouping the paragraph rows by chapterIndex,
 * so this is the whole book's structure, not just the part that has been translated.
 *
 * A chapter is tappable when there is something to land on. For a forward book that means the
 * translation frontier has reached it — the locked tail is shown rather than hidden, because "how
 * far have I got" is half of what a reader opens this for. A reverse book is its own target-language
 * text, so every chapter is readable from import and the whole list is live and unmarked.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BookTocSheet(
    chapters: List<BookChapter>,
    currentChapter: Int,
    onJump: (ord: Int) -> Unit,
    onContinueTranslating: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()

    // Open on where the reader actually is: in a fifty-chapter book the top of the list is rarely
    // the useful end of it.
    LaunchedEffect(chapters, currentChapter) {
        val index = chapters.indexOfFirst { it.index == currentChapter }
        if (index > 0) listState.scrollToItem(index)
    }

    val locked = chapters.count { !it.jumpable }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(R.string.book_toc_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(chapters, key = { it.index }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    isCurrent = chapter.index == currentChapter,
                    onClick = { onJump(chapter.firstOrd) },
                )
            }
            if (locked > 0) {
                item(key = "lockedFooter") {
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp),
                    ) {
                        Text(
                            pluralStringResource(R.plurals.book_toc_locked_hint, locked, locked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onContinueTranslating(); onDismiss() }) {
                            Icon(
                                Icons.Filled.Translate,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                stringResource(R.string.book_continue),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: BookChapter, isCurrent: Boolean, onClick: () -> Unit) {
    val enabled = chapter.jumpable
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            // Not merely greyed: an unreachable chapter must not look like a tap that failed.
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier.alpha(0.45f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            "${chapter.index + 1}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Text(
            chapter.title ?: stringResource(R.string.book_chapter_n, chapter.index + 1),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // FULL carries no mark: on a finished book every row would wear one, which says nothing.
        // The marks exist to show where the frontier is, so only the two unfinished states get one.
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (chapter.progress) {
                ChapterProgress.PARTIAL -> Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = stringResource(R.string.book_toc_partial),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChapterProgress.NONE -> Icon(
                    Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.book_toc_not_translated),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChapterProgress.FULL -> Unit
            }
        }
    }
}
