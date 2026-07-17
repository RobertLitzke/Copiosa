package com.copiosa.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.copiosa.R
import com.copiosa.data.remote.LlmError

@Composable
fun llmErrorText(error: LlmError): String = when (error) {
    LlmError.NoKey -> stringResource(R.string.error_no_key)
    LlmError.Auth -> stringResource(R.string.error_auth)
    LlmError.Network -> stringResource(R.string.error_network)
    LlmError.RateLimit -> stringResource(R.string.error_rate_limit)
    LlmError.ServiceBusy -> stringResource(R.string.error_service_busy)
    LlmError.Extraction -> stringResource(R.string.error_extraction)
    is LlmError.Unknown -> stringResource(R.string.error_unknown, error.message)
}
