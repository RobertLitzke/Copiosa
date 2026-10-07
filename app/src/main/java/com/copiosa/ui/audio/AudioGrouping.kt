package com.copiosa.ui.audio

import com.copiosa.data.local.AudioItemEntity
import com.copiosa.domain.model.AudioStatus

/**
 * Folding the flat playlist into folders.
 *
 * Grouping happens here rather than in SQL so the queue keeps one query and one ordering rule: the
 * DAO still returns `played ASC, sortOrder ASC` and this rearranges what it returned. A tag's items
 * are collected at the position of the first one that appears, which keeps a folder where the
 * learner last saw it instead of jumping around as its tracks are heard.
 */

/** A row in the Audio pane: a loose item, or a folder standing in for all of one tag's tracks. */
sealed interface AudioListEntry {
    data class Loose(val item: AudioItemEntity) : AudioListEntry

    data class Group(val tag: String, val tracks: List<AudioItemEntity>) : AudioListEntry {
        val unheard: Int get() = tracks.count { !it.played }
        val ready: Int get() = tracks.count { it.status == AudioStatus.READY }
        val pending: Int get() = tracks.count { it.status == AudioStatus.PENDING || it.status == AudioStatus.GENERATING }
        val failed: Int get() = tracks.count { it.status == AudioStatus.FAILED }
        val totalMs: Long get() = tracks.sumOf { it.durationMs ?: 0L }
    }
}

/**
 * Groups [items] by tag, preserving the incoming order for loose items and for the folders' places
 * in the list. Tracks within a folder are sorted by their own key, not by whether they have been
 * played — a book reads in chapter order however much of it has been heard.
 */
fun groupByTag(items: List<AudioItemEntity>): List<AudioListEntry> {
    val entries = ArrayList<AudioListEntry>(items.size)
    val groupAt = HashMap<String, Int>()

    for (item in items) {
        val tag = item.tag
        if (tag == null) {
            entries += AudioListEntry.Loose(item)
            continue
        }
        val index = groupAt[tag]
        if (index == null) {
            groupAt[tag] = entries.size
            entries += AudioListEntry.Group(tag, listOf(item))
        } else {
            val group = entries[index] as AudioListEntry.Group
            entries[index] = group.copy(tracks = group.tracks + item)
        }
    }

    return entries.map { entry ->
        if (entry is AudioListEntry.Group) entry.copy(tracks = entry.tracks.sortedBy { it.sortOrder }) else entry
    }
}
