package com.copiosa.data.remote

import retrofit2.HttpException
import java.io.IOException

/** Maps SDK/HTTP failures onto the classified [LlmError] surface. */
internal fun Throwable.toLlmException(): LlmException = when (this) {
    is LlmException -> this
    is HttpException -> when (code()) {
        401, 403 -> LlmException(LlmError.Auth)
        429 -> LlmException(LlmError.RateLimit)
        500, 502, 503, 504 -> LlmException(LlmError.ServiceBusy)
        else -> LlmException(LlmError.Unknown("HTTP ${code()}"))
    }
    is IOException -> LlmException(LlmError.Network)
    else -> LlmException(LlmError.Unknown(message ?: this::class.simpleName ?: "error"))
}
