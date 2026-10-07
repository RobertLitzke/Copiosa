package com.copiosa.ui.reference

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.ui.components.CircleAvatar
import com.copiosa.ui.components.EmptyState
import com.copiosa.ui.components.ScreenHeader

/**
 * The reference section's front door: everything the app can explain, one card per topic.
 *
 * It is a separate experience rather than a tab — you come here to look something up and then leave,
 * the same shape as stats. Which topics appear depends on the active profile, since most of what is
 * on offer is built out of that language's data (see [ReferenceTopic.needsConjugation]).
 */
@Composable
fun ReferenceHubScreen(
    onBack: () -> Unit,
    onOpenTopic: (ReferenceTopic) -> Unit,
    viewModel: ReferenceViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val topics = ui.topics

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.ref_title), onBack = onBack)

        if (ui.resolved && topics.isEmpty()) {
            EmptyState(stringResource(R.string.ref_empty))
            return@Column
        }

        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.ref_hub_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            topics.forEach { topic -> TopicCard(topic) { onOpenTopic(topic) } }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TopicCard(topic: ReferenceTopic, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleAvatar(icon = topic.icon, highlighted = true)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    stringResource(topic.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(topic.blurbRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
