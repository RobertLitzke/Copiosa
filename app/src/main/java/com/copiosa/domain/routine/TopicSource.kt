package com.copiosa.domain.routine

/**
 * Where a [com.copiosa.data.local.TopicEntity] came from, so the picker can weigh and explain
 * suggestions. [USER_SEARCH]/[USER_TRANSLATION] are things the learner actively looked up;
 * [CONVERSATION_SUBJECT] is a chat they started; [CHAT_SUGGESTION] is a model-proposed next-topic
 * (cryptic on its own — its stored description carries the context).
 */
enum class TopicSource {
    USER_SEARCH,
    USER_TRANSLATION,
    CONVERSATION_SUBJECT,
    CHAT_SUGGESTION,
}
