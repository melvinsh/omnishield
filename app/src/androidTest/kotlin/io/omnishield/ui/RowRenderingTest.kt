package io.omnishield.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.omnishield.data.LogEntry
import io.omnishield.ui.theme.OmniShieldTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two rows whose previous versions each hid something from someone.
 *
 * The log row carried its verdict in a coloured dot and nothing else. The browser row stayed
 * live while the master interception switch was off, so tapping it wrote a setting and changed
 * nothing observable.
 */
@RunWith(AndroidJUnit4::class)
class RowRenderingTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entry(blocked: Boolean, rule: String = "") = LogEntry(
        seq = 1,
        ts = System.currentTimeMillis(),
        kind = "dns",
        name = "ads.example.com",
        uid = 10_001,
        app = "com.android.chrome",
        blocked = blocked,
        rule = rule,
    )

    @Test
    fun a_log_row_announces_its_verdict_in_text() {
        compose.setContent {
            OmniShieldTheme(dynamicColor = false) {
                LogRow(entry = entry(blocked = true), override = null, onClick = {})
            }
        }
        // Not colour alone: the verdict is in the row's own description, so it survives both
        // TalkBack and a colour deficit.
        compose.onNodeWithContentDescription("ads.example.com, Blocked").assertIsDisplayed()
    }

    @Test
    fun an_allowed_row_says_allowed() {
        compose.setContent {
            OmniShieldTheme(dynamicColor = false) {
                LogRow(entry = entry(blocked = false), override = null, onClick = {})
            }
        }
        compose.onNodeWithContentDescription("ads.example.com, Allowed").assertIsDisplayed()
    }

    @Test
    fun an_override_replaces_the_rule_text() {
        compose.setContent {
            OmniShieldTheme(dynamicColor = false) {
                LogRow(
                    entry = entry(blocked = true, rule = "easylist"),
                    override = false,
                    onClick = {},
                )
            }
        }
        compose.onNodeWithText("Always blocked", substring = true).assertIsDisplayed()
        compose.onNodeWithText("easylist", substring = true).assertDoesNotExist()
    }

    @Test
    fun a_browser_switch_is_dead_while_interception_is_off() {
        compose.setContent {
            OmniShieldTheme(dynamicColor = false) {
                BrowserRow(
                    app = InstalledApp(10_101, "com.android.chrome", "Chrome", null),
                    enabled = false,
                    intercepted = false,
                    pinned = false,
                    onToggle = {},
                    onUnpin = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Chrome").assertIsNotEnabled()
    }

    @Test
    fun a_browser_switch_is_live_once_interception_is_on() {
        compose.setContent {
            OmniShieldTheme(dynamicColor = false) {
                BrowserRow(
                    app = InstalledApp(10_101, "com.android.chrome", "Chrome", null),
                    enabled = true,
                    intercepted = false,
                    pinned = false,
                    onToggle = {},
                    onUnpin = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Chrome").assertIsEnabled()
    }

    @Test
    fun a_pinned_app_offers_a_way_back() {
        var unpinned = false
        compose.setContent {
            OmniShieldTheme(dynamicColor = false) {
                BrowserRow(
                    app = InstalledApp(10_101, "com.android.chrome", "Chrome", null),
                    enabled = true,
                    intercepted = false,
                    pinned = true,
                    onToggle = {},
                    onUnpin = { unpinned = true },
                )
            }
        }
        compose.onNodeWithText("Bypassed", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertTrue("a pinned app must be recoverable", unpinned)
    }
}
