package com.copiosa.ui.theme.skin

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copiosa.domain.model.Persona
import com.copiosa.ui.theme.SourceSerif

/** How a persona lays out the chat thread. */
enum class ChatLayout {
    /** No bubbles: ruled/justified text blocks with kicker labels (Professor, Poet). */
    PAPER,
    /** Chat bubbles with avatars (Chef, Artist). */
    BUBBLES,
    /** Left-bar entries with uppercase kicker labels (Commuter, Explorer). */
    TRANSCRIPT,
}

/** Background texture drawn behind themed screens, tinted by country tokens. */
enum class PersonaTexture {
    /** Professor: reading-lamp radial vignette. */
    VIGNETTE,
    /** Chef: gingham weave of diagonal accent stripes. */
    GINGHAM,
    /** Poet: faint radial accent wash from the top. */
    RADIAL_WASH,
    /** Artist: multi-point paint washes. */
    PAINT_WASH,
    /** Commuter: faint horizontal timetable rules. */
    RULED,
    /** Explorer: contour-line rings. */
    CONTOURS,
}

/** Message-entry animation signature; progress drives fade/translate/scale/rotate/blur together. */
@Immutable
data class MotionSpec(
    val durationMs: Int,
    val staggerMs: Int,
    val translateY: Dp = 0.dp,
    val scaleFrom: Float = 1f,
    val rotateFrom: Float = 0f,
    val blurFrom: Dp = 0.dp,
    val easing: Easing,
)

/** Localized in-character copy a persona supplies per theme language. */
@Immutable
data class PersonaStrings(
    /** The in-character name the tutor calls the learner, e.g. "Der Pendler". */
    val you: String,
    /** The Talk/Chats nav-tab label in the persona's voice, e.g. "Strecken". */
    val talkTab: String,
    /** Chat input placeholder, e.g. "Schreib unterwegs…". */
    val placeholder: String,
    /** Signature-band text (Chef/Commuter/Explorer only), e.g. "Gleis 2 · Abfahrt 9:32". */
    val band: String? = null,
)

/** Everything the persona axis contributes: layout, type, spacing, texture, motion, and copy. */
@Immutable
data class PersonaSpec(
    val persona: Persona,
    val uiFont: FontFamily,
    val chatFont: FontFamily,
    val titleFont: FontFamily,
    val chatSize: TextUnit,
    val chatLineHeight: TextUnit,
    /** Corner radius for chrome (input field, cards). */
    val radius: Dp,
    /** Corner radius for message bubbles. */
    val bubbleRadius: Dp,
    /** Chat thread padding. */
    val padV: Dp,
    val padH: Dp,
    /** Vertical gap between messages. */
    val msgGap: Dp,
    val layout: ChatLayout,
    val texture: PersonaTexture,
    val motion: MotionSpec,
    /** Copy keyed by theme language tag: "es", "pt", "de". */
    val i18n: Map<String, PersonaStrings>,
    /** English display name for the settings picker. */
    val displayName: String,
    /** One-line English blurb for the settings picker. */
    val blurb: String,
)

/** Resolves a [Persona] to its spec, or null for [Persona.UNSTYLED] (stock chat UI). */
fun personaSpec(persona: Persona): PersonaSpec? = when (persona) {
    Persona.UNSTYLED -> null
    Persona.PROFESSOR -> Professor
    Persona.CHEF -> Chef
    Persona.POET -> Poet
    Persona.ARTIST -> Artist
    Persona.COMMUTER -> Commuter
    Persona.EXPLORER -> Explorer
}

// CSS `ease`, used by the Professor's lamp-lit fade.
private val EaseCss = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

private val Professor = PersonaSpec(
    persona = Persona.PROFESSOR,
    uiFont = IbmPlexSans,
    chatFont = Newsreader,
    titleFont = Newsreader,
    chatSize = 16.5.sp,
    chatLineHeight = (16.5 * 1.72).sp,
    radius = 10.dp,
    bubbleRadius = 13.dp,
    padV = 20.dp,
    padH = 22.dp,
    msgGap = 17.dp,
    layout = ChatLayout.PAPER,
    texture = PersonaTexture.VIGNETTE,
    motion = MotionSpec(durationMs = 520, staggerMs = 70, translateY = 8.dp, easing = EaseCss),
    i18n = mapOf(
        "es" to PersonaStrings("El Catedrático", "Seminarios", "Redacta tu argumento…"),
        "pt" to PersonaStrings("O Catedrático", "Seminários", "Redige o teu argumento…"),
        "de" to PersonaStrings("Der Professor", "Seminare", "Formuliere dein Argument…"),
    ),
    displayName = "The Professor",
    blurb = "A bound book: small-caps plates, a ruled index, justified pages under a reading lamp.",
)

private val Chef = PersonaSpec(
    persona = Persona.CHEF,
    uiFont = DmSans,
    chatFont = Lora,
    titleFont = Caveat,
    chatSize = 15.5.sp,
    chatLineHeight = (15.5 * 1.5).sp,
    radius = 20.dp,
    bubbleRadius = 18.dp,
    padV = 16.dp,
    padH = 16.dp,
    msgGap = 12.dp,
    layout = ChatLayout.BUBBLES,
    texture = PersonaTexture.GINGHAM,
    motion = MotionSpec(
        durationMs = 520, staggerMs = 95, translateY = 22.dp, scaleFrom = 0.9f,
        easing = CubicBezierEasing(0.2f, 1.7f, 0.35f, 1f),
    ),
    i18n = mapOf(
        "es" to PersonaStrings("El Chef", "Recetas", "Apunta la receta…", "Comanda · Mesa 4"),
        "pt" to PersonaStrings("O Chef", "Receitas", "Anota a receita…", "Comanda · Mesa 4"),
        "de" to PersonaStrings("Der Koch", "Rezepte", "Notiere das Rezept…", "Bestellung · Tisch 4"),
    ),
    displayName = "The Chef",
    blurb = "A working kitchen: comanda tickets, recipe-card bubbles, gingham weave.",
)

private val Poet = PersonaSpec(
    persona = Persona.POET,
    uiFont = DmSans,
    chatFont = Spectral,
    titleFont = InstrumentSerif,
    chatSize = 17.sp,
    chatLineHeight = (17 * 1.75).sp,
    radius = 12.dp,
    bubbleRadius = 16.dp,
    padV = 20.dp,
    padH = 22.dp,
    msgGap = 18.dp,
    layout = ChatLayout.PAPER,
    texture = PersonaTexture.RADIAL_WASH,
    motion = MotionSpec(
        durationMs = 920, staggerMs = 165, translateY = 18.dp, blurFrom = 6.dp,
        easing = CubicBezierEasing(0.2f, 0.6f, 0.2f, 1f),
    ),
    i18n = mapOf(
        "es" to PersonaStrings("El Poeta", "Versos", "Escribe un verso…"),
        "pt" to PersonaStrings("O Poeta", "Versos", "Escreve um verso…"),
        "de" to PersonaStrings("Der Dichter", "Verse", "Schreib einen Vers…"),
    ),
    displayName = "The Poet",
    blurb = "Bare paper: no chrome, verse on open paper, nothing but the words arriving slowly.",
)

private val Artist = PersonaSpec(
    persona = Persona.ARTIST,
    uiFont = Syne,
    chatFont = Fraunces,
    titleFont = Syne,
    chatSize = 16.sp,
    chatLineHeight = (16 * 1.55).sp,
    radius = 16.dp,
    bubbleRadius = 18.dp,
    padV = 16.dp,
    padH = 16.dp,
    msgGap = 13.dp,
    layout = ChatLayout.BUBBLES,
    texture = PersonaTexture.PAINT_WASH,
    motion = MotionSpec(
        durationMs = 500, staggerMs = 90, translateY = 10.dp, scaleFrom = 0.82f, rotateFrom = -6f,
        easing = CubicBezierEasing(0.2f, 1.6f, 0.35f, 1f),
    ),
    i18n = mapOf(
        "es" to PersonaStrings("El Artista", "Bocetos", "Garabatea algo…"),
        "pt" to PersonaStrings("O Artista", "Esboços", "Rabisca algo…"),
        "de" to PersonaStrings("Der Künstler", "Skizzen", "Kritzle etwas…"),
    ),
    displayName = "The Artist",
    blurb = "A studio collage: comic-inked bubbles, tilted sticker cards, paint-wash light.",
)

private val Commuter = PersonaSpec(
    persona = Persona.COMMUTER,
    uiFont = IbmPlexSans,
    chatFont = IbmPlexSans,
    titleFont = IbmPlexSans,
    chatSize = 14.sp,
    chatLineHeight = (14 * 1.55).sp,
    radius = 9.dp,
    bubbleRadius = 10.dp,
    padV = 10.dp,
    padH = 0.dp,
    msgGap = 10.dp,
    layout = ChatLayout.TRANSCRIPT,
    texture = PersonaTexture.RULED,
    motion = MotionSpec(
        durationMs = 240, staggerMs = 40, translateY = 10.dp,
        easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f),
    ),
    i18n = mapOf(
        "es" to PersonaStrings("El Pasajero", "Trayectos", "Escribe en el trayecto…", "Andén 2 · Salida 9:32"),
        "pt" to PersonaStrings("O Passageiro", "Trajetos", "Escreve no trajeto…", "Cais 2 · Partida 9:32"),
        "de" to PersonaStrings("Der Pendler", "Strecken", "Schreib unterwegs…", "Gleis 2 · Abfahrt 9:32"),
    ),
    displayName = "The Commuter",
    blurb = "A daily transit line: departure-board chrome, dense timetable rows, built for twenty minutes a day.",
)

private val Explorer = PersonaSpec(
    persona = Persona.EXPLORER,
    uiFont = Archivo,
    chatFont = SourceSerif,
    titleFont = Archivo,
    chatSize = 14.5.sp,
    chatLineHeight = (14.5 * 1.6).sp,
    radius = 9.dp,
    bubbleRadius = 10.dp,
    padV = 13.dp,
    padH = 18.dp,
    msgGap = 13.dp,
    layout = ChatLayout.TRANSCRIPT,
    texture = PersonaTexture.CONTOURS,
    motion = MotionSpec(
        durationMs = 460, staggerMs = 80, translateY = 18.dp, rotateFrom = -1f,
        easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f),
    ),
    i18n = mapOf(
        "es" to PersonaStrings("El Explorador", "Expediciones", "Registra el día…", "Día 12"),
        "pt" to PersonaStrings("O Explorador", "Expedições", "Regista o dia…", "Dia 12"),
        "de" to PersonaStrings("Der Entdecker", "Expeditionen", "Halte den Tag fest…", "Tag 12"),
    ),
    displayName = "The Explorer",
    blurb = "An expedition logbook: contour paper, passport stamps, dotted trails, a compass-tilted send.",
)
