@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.omnishield.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.omnishield.R

private data class Page(val titleRes: Int, val bodyRes: Int, val icon: ImageVector)

/**
 * First-run explainer.
 *
 * Exists because the very next thing the app does is ask for permission to inspect all network
 * traffic. Presenting that dialog cold, with no statement of what the app is or why it needs
 * it, is how a privacy tool ends up looking like the thing it protects against.
 *
 * It still asks for nothing itself — it explains, then reports through [onFinished] whether the
 * user wants to connect, and the main scaffold runs the normal consent sequence. Ending on
 * "Connect now" rather than dropping the user on a dashboard matters: the previous flow
 * finished by silently revealing a screen whose primary action the user then had to find.
 */
@Composable
fun OnboardingScreen(onFinished: (connect: Boolean) -> Unit) {
    val pages = remember {
        listOf(
            Page(R.string.onboarding_1_title, R.string.onboarding_1_body, Icons.Filled.Block),
            Page(R.string.onboarding_2_title, R.string.onboarding_2_body, Icons.Filled.VpnKey),
            Page(
                R.string.onboarding_3_title,
                R.string.onboarding_3_body,
                Icons.Filled.BatteryFull,
            ),
        )
    }

    var index by remember { mutableIntStateOf(0) }
    val page = pages[index]
    val progress by animateFloatAsState(
        targetValue = index.toFloat(),
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "onboardingProgress",
    )
    val pageLabel = stringResource(R.string.cd_onboarding_page, index + 1, pages.size)

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .semantics { contentDescription = pageLabel },
                contentAlignment = Alignment.Center,
            ) {
                // Was a giant page number, which told the reader nothing they could not get
                // from the dots below it.
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(84.dp),
                )
            }

            Spacer(Modifier.weight(0.4f))

            Text(
                text = stringResource(page.titleRes),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(page.bodyRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pages.indices.forEach { i ->
                    val selected = (progress.toInt() == i)
                    Box(
                        Modifier
                            .size(if (selected) 10.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onFinished(false) }) {
                    Text(stringResource(R.string.onboarding_skip))
                }
                Button(onClick = {
                    if (index < pages.lastIndex) index++ else onFinished(true)
                }) {
                    Text(
                        if (index < pages.lastIndex) {
                            stringResource(R.string.onboarding_next)
                        } else {
                            stringResource(R.string.onboarding_done)
                        }
                    )
                }
            }
        }
    }
}
