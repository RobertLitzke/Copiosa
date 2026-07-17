package com.copiosa.data.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Outcome of an on-device translation attempt. */
sealed interface LocalTranslateResult {
    data class Success(val text: String) : LocalTranslateResult

    /** The source/target language isn't one ML Kit can translate on-device. */
    data object Unsupported : LocalTranslateResult

    /** The model wasn't present and couldn't be downloaded (offline), or translation failed. */
    data object Unavailable : LocalTranslateResult
}

/**
 * Translates a selected phrase entirely on-device via ML Kit. The first lookup for a language
 * downloads a ~30 MB model once; every lookup after that runs offline with no network or LLM call.
 */
@Singleton
class OnDeviceTranslator @Inject constructor() {

    /**
     * Translates [text] from [sourceTag] to [targetTag] (BCP-47 tags, e.g. "es"/"en"). Downloads
     * the language model on first use over any connection; returns [LocalTranslateResult.Unavailable]
     * if that's not possible (e.g. offline with no cached model).
     */
    suspend fun translate(text: String, sourceTag: String, targetTag: String): LocalTranslateResult {
        val src = TranslateLanguage.fromLanguageTag(sourceTag) ?: return LocalTranslateResult.Unsupported
        val tgt = TranslateLanguage.fromLanguageTag(targetTag) ?: return LocalTranslateResult.Unsupported
        if (src == tgt) return LocalTranslateResult.Success(text)

        val client = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build(),
        )
        return try {
            client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            LocalTranslateResult.Success(client.translate(text).await())
        } catch (e: Exception) {
            LocalTranslateResult.Unavailable
        } finally {
            client.close()
        }
    }
}

/** Bridges a Play Services [Task] to a coroutine without pulling in the play-services-coroutines dep. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
