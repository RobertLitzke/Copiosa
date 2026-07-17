package com.copiosa.ui.flashcards

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.copiosa.data.anki.AnkiMedia
import java.io.File

/**
 * Renders a card's attached image (a media-store filename) if the file exists; renders nothing
 * otherwise. Shared by review, detail, and the editor so they all decode the same way.
 */
@Composable
fun CardImage(imageFile: String?, modifier: Modifier = Modifier, maxHeight: Dp = 429.dp) {
    val context = LocalContext.current
    val file = imageFile?.let { AnkiMedia.file(context, it) }
    FileImage(
        file = file,
        modifier = modifier.fillMaxWidth().heightIn(max = maxHeight),
        contentScale = ContentScale.Fit,
    )
}

/** Renders any local image [file] (decoded with BitmapFactory) if present; nothing otherwise. */
@Composable
fun FileImage(file: File?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    if (file == null) return
    val bitmap = remember(file.absolutePath, file.lastModified()) {
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
    } ?: return
    Image(bitmap = bitmap, contentDescription = null, contentScale = contentScale, modifier = modifier)
}
