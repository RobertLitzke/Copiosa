package com.copiosa.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A local image decoded off the main thread.
 *
 * The sibling `FileImage` (flashcards) decodes synchronously during composition, which is fine for
 * one small cover on a screen that shows a single card. In a book reader the images arrive inside a
 * scrolling `LazyColumn`, where a synchronous `BitmapFactory.decodeFile` is a dropped frame every
 * time a plate scrolls into view — so this one decodes in [produceState] on [Dispatchers.IO] and
 * composes an empty box until the bitmap lands.
 *
 * A missing or undecodable file renders as that empty box rather than as nothing at all. In the
 * book reader the emptiness is load-bearing: the list index *is* the paragraph ord, so an image row
 * that failed to load must still occupy exactly one item.
 */
@Composable
fun AsyncFileImage(
    file: File?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.FillWidth,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file?.absolutePath) {
        value = file?.let {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }.getOrNull()
            }
        }
    }

    val image = bitmap
    if (image == null) {
        Box(modifier.fillMaxWidth())
    } else {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier.fillMaxWidth(),
        )
    }
}
