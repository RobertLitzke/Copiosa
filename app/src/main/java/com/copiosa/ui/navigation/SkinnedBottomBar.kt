package com.copiosa.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copiosa.ui.theme.skin.AppSkin
import com.copiosa.ui.theme.skin.LocalAppSkin
import com.copiosa.ui.theme.skin.MotifBand
import com.copiosa.ui.theme.skin.motifBackground

/**
 * The bottom navigation bar, per the theme mocks: small-caps labels with a motif-band indicator
 * under the active tab and a thin motif strip along the top. Used in every mode — unstyled just
 * gets a plain accent indicator and resource labels.
 */
@Composable
fun SkinnedBottomBar(
    currentRoute: String?,
    onNavigate: (TopDestination) -> Unit,
) {
    val skin = LocalAppSkin.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            // The flavour strip matches the band under the status bar (same height, full opacity) so the
            // two frame the tabs symmetrically; the tab row is tightened below to keep the bar compact.
            skin.motif?.let { MotifBand(it, Modifier.fillMaxWidth(), height = 10.dp) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(58.dp),
            ) {
                TopDestination.entries.forEach { dest ->
                    TabItem(
                        dest = dest,
                        skin = skin,
                        selected = currentRoute == dest.route,
                        onClick = { onNavigate(dest) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabItem(
    dest: TopDestination,
    skin: AppSkin,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier.selectable(selected = selected, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            dest.icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.padding(top = 4.dp).size(22.dp),
        )
        // Tab labels always follow the app UI language (string resources), not the theme country's
        // language — the persona/country only styles the bar. Uppercase + tracking gives the mocks'
        // small-caps look (persona TTFs lack real smcp).
        Text(
            text = stringResource(dest.labelRes).uppercase(),
            color = color,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            fontFamily = skin.personaSpec?.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp, bottom = 3.dp),
        )
        ActiveTabIndicator(skin = skin, visible = selected)
    }
}

/**
 * 38×5dp underline for the active tab: a softened echo of the country motif — drawn at reduced
 * opacity so it's country-specific (scallops for Spain, tricolor for Germany, …) but less bold than
 * the full-strength flavour bands. Falls back to a plain accent bar when no country is themed.
 */
@Composable
private fun ActiveTabIndicator(skin: AppSkin, visible: Boolean) {
    val base = Modifier
        .width(38.dp)
        .height(5.dp)
        .clip(RoundedCornerShape(2.5.dp))
    val decorated = when {
        !visible -> base
        skin.motif != null -> base.motifBackground(skin.motif!!, alpha = 0.6f)
        else -> base.background(MaterialTheme.colorScheme.primary)
    }
    Box(modifier = decorated)
}
