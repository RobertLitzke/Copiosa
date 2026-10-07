package com.copiosa.domain.reading

/**
 * Which language a bilingual reader row shows. The ordinal is the segment index in the reader's
 * bottom control, and the step order left-to-right that a swipe walks.
 *
 * Persisted per book and per imported text (`books.readMode`, `conversations.readMode`), so a reader
 * who works through a novel in Both mode is not put back into Target every time they reopen it.
 */
enum class ReadMode { TARGET, NATIVE, BOTH }

/** One step along the control, clamped: past either end a swipe simply does nothing. */
fun ReadMode.stepped(step: Int): ReadMode =
    ReadMode.entries[(ordinal + step).coerceIn(0, ReadMode.entries.lastIndex)]

/** Whether stepping by [step] would actually land somewhere else. */
fun ReadMode.canStep(step: Int): Boolean = stepped(step) != this
