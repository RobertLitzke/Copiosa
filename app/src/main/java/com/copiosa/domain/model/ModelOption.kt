package com.copiosa.domain.model

/** One model a call can be sent to: which provider it goes through, and its model string there. */
data class ModelOption(val provider: ProviderId, val model: String)
