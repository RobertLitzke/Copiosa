package com.copiosa.ui.theme.skin

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.copiosa.R

// Persona fonts, bundled as OFL variable TTFs where available (static weights otherwise),
// following the same registration pattern as Type.kt: one file registered at the weights the
// personas actually use; the wght axis resolves on API 26+.

val IbmPlexSans = FontFamily(
    Font(R.font.ibm_plex_sans, FontWeight.Normal),
    Font(R.font.ibm_plex_sans, FontWeight.Medium),
    Font(R.font.ibm_plex_sans, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans, FontWeight.Bold),
)

val Newsreader = FontFamily(
    Font(R.font.newsreader, FontWeight.Normal),
    Font(R.font.newsreader, FontWeight.Medium),
    Font(R.font.newsreader, FontWeight.SemiBold),
    Font(R.font.newsreader_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.newsreader_italic, FontWeight.SemiBold, FontStyle.Italic),
)

val DmSans = FontFamily(
    Font(R.font.dm_sans, FontWeight.Normal),
    Font(R.font.dm_sans, FontWeight.Medium),
    Font(R.font.dm_sans, FontWeight.SemiBold),
    Font(R.font.dm_sans, FontWeight.Bold),
)

val Lora = FontFamily(
    Font(R.font.lora, FontWeight.Normal),
    Font(R.font.lora, FontWeight.Medium),
    Font(R.font.lora, FontWeight.SemiBold),
    Font(R.font.lora_italic, FontWeight.Normal, FontStyle.Italic),
)

val Caveat = FontFamily(
    Font(R.font.caveat, FontWeight.Medium),
    Font(R.font.caveat, FontWeight.SemiBold),
    Font(R.font.caveat, FontWeight.Bold),
)

val Spectral = FontFamily(
    Font(R.font.spectral, FontWeight.Normal),
    Font(R.font.spectral_medium, FontWeight.Medium),
    Font(R.font.spectral_semibold, FontWeight.SemiBold),
    Font(R.font.spectral_italic, FontWeight.Normal, FontStyle.Italic),
)

val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

val Syne = FontFamily(
    Font(R.font.syne, FontWeight.Medium),
    Font(R.font.syne, FontWeight.SemiBold),
    Font(R.font.syne, FontWeight.Bold),
    Font(R.font.syne, FontWeight.ExtraBold),
)

val Fraunces = FontFamily(
    Font(R.font.fraunces, FontWeight.Normal),
    Font(R.font.fraunces, FontWeight.Medium),
    Font(R.font.fraunces, FontWeight.SemiBold),
    Font(R.font.fraunces_italic, FontWeight.Normal, FontStyle.Italic),
)

val Archivo = FontFamily(
    Font(R.font.archivo, FontWeight.Normal),
    Font(R.font.archivo, FontWeight.Medium),
    Font(R.font.archivo, FontWeight.SemiBold),
    Font(R.font.archivo, FontWeight.Bold),
)
