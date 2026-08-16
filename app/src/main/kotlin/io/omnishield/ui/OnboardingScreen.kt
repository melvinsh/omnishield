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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.omnishield.R

private data class Page(val title: String, val body: String)

/**
 * First-run explainer.
 *
 * Exists because the very next thing the app does is ask for permission to inspect all network
 * traffic. Presenting that dialog cold, with no statement of what the app is or why it needs
 * it, is how a privacy tool ends up looking like the thing it protects against.
 *
 * Deliberately does not ask for anything itself — it explains, then hands over to the normal
 * connect flow, which sequences the notification permission and VPN consent properly.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pages = listOf(
        Page(
            title = "Blocks ads and trackers",
            body = "OmniShield filters DNS for every app on the device, so trackers are " +
                "refused before a connection is ever made. No root required.",
        ),
        Page(
            title = "Why it asks for VPN permission",
            body = "Android only lets an app see network traffic through a VPN interface. " +
                "OmniShield runs that tunnel locally on your device — nothing is sent to a " +
                "remote server, and there is no account.",
        ),
        Page(
            title = "Keep it running",
            body = "Android may suspend background services to save power. If filtering " +
                "stops unexpectedly, exempt OmniShield from battery optimisation in Settings.",
        ),
    )

    var index by remember { mutableIntStateOf(0) }
    val page = pages[index]
    val progress by animateFloatAsState(
        targetValue = index.toFloat(),
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "onboardingProgress",
    )

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
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Spacer(Modifier.weight(0.4f))

            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = page.body,
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
                TextButton(onClick = onFinished) {
                    Text(stringResource(R.string.onboarding_skip))
                }
                Button(onClick = {
                    if (index < pages.lastIndex) index++ else onFinished()
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
