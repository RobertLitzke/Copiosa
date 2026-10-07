package com.copiosa.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.copiosa.R
import com.copiosa.data.remote.LlmError
import com.copiosa.data.translate.DictionaryDownload

@Composable
fun llmErrorText(error: LlmError): String = when (error) {
    LlmError.NoKey -> stringResource(R.string.error_no_key)
    LlmError.Auth -> stringResource(R.string.error_auth)
    LlmError.Network -> stringResource(R.string.error_network)
    LlmError.RateLimit -> stringResource(R.string.error_rate_limit)
    LlmError.ServiceBusy -> stringResource(R.string.error_service_busy)
    LlmError.Refused -> stringResource(R.string.error_refused)
    LlmError.Extraction -> stringResource(R.string.error_extraction)
    is LlmError.Unknown -> stringResource(R.string.error_unknown, error.message)
}

/**
 * Why a dictionary pack didn't arrive. Split by what the reader can do about it: wait and retry, or
 * nothing at all — a pack missing from the server is not a connection to keep re-testing.
 */
@Composable
fun dictionaryErrorText(reason: DictionaryDownload): String = when (reason) {
    DictionaryDownload.Offline -> stringResource(R.string.dict_error_offline)
    is DictionaryDownload.Unavailable -> stringResource(R.string.dict_error_unavailable, reason.code)
    DictionaryDownload.Corrupt -> stringResource(R.string.dict_error_corrupt)
    DictionaryDownload.NotConfigured -> stringResource(R.string.dict_error_not_configured)
    // Never rendered: the card only reaches here on a failure. Kept total so a new outcome has to
    // be given words rather than silently reading as success.
    DictionaryDownload.Ready -> ""
}
