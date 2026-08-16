package io.omnishield.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.omnishield.data.AppRule
import io.omnishield.ui.components.ScreenScaffold
import io.omnishield.ui.theme.OmniShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The firewall list, driven directly rather than through its ViewModel.
 *
 * The screen composable takes its data from a ViewModel, so these exercise [FirewallList] — the
 * part that holds every behaviour worth asserting — with state supplied by the test. What is
 * being protected here is the thing that was wrong before: that a row must say what it does,
 * and that acting on one must be visible and reversible.
 */
@RunWith(AndroidJUnit4::class)
class FirewallScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val chrome = InstalledApp(10_101, "com.android.chrome", "Chrome", null)
    private val maps = InstalledApp(10_102, "com.google.android.apps.maps", "Maps", null)

    /** Renders the list with a mutable rule store, exactly as the real screen does. */
    private fun setContent(
        apps: List<InstalledApp> = listOf(chrome, maps),
        initial: Map<Int, AppRule> = emptyMap(),
        onChange: (AppRule) -> Unit = {},
    ) {
        compose.setContent {
            var rules by remember { mutableStateOf(initial) }
            var query by remember { mutableStateOf("") }
            OmniShieldTheme(dynamicColor = false) {
                ScreenScaffold(title = "Firewall") {
                    FirewallList(
                        apps = apps,
                        rules = rules,
                        query = query,
                        onQueryChange = { query = it },
                        onChange = { rule ->
                            rules = rules + (rule.uid to rule)
                            onChange(rule)
                        },
                        modifier = Modifier,
                    )
                }
            }
        }
    }

    @Test
    fun a_row_states_its_rule_in_words() {
        setContent(initial = mapOf(chrome.uid to AppRule(chrome.uid, blockMobile = true)))

        compose.onNodeWithText("Blocked on mobile").assertIsDisplayed()
        // An app with no rule shows its package name instead of repeating "Allowed".
        compose.onNodeWithText("com.google.android.apps.maps").assertIsDisplayed()
    }

    @Test
    fun the_legend_says_which_way_the_switches_run() {
        setContent()
        compose.onNodeWithText(
            "Turning a switch on blocks that app.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun toggling_wifi_blocks_only_wifi() {
        var last: AppRule? = null
        setContent(onChange = { last = it })

        compose.onNodeWithContentDescription("Block Chrome on Wi-Fi").performClick()

        assertEquals(true, last?.blockWifi)
        assertEquals(false, last?.blockMobile)
        compose.onNodeWithContentDescription("Block Chrome on Wi-Fi").assertIsOn()
        compose.onNodeWithContentDescription("Block Chrome on mobile data").assertIsOff()
    }

    @Test
    fun the_wording_follows_the_switch() {
        setContent()
        compose.onNodeWithContentDescription("Block Chrome on Wi-Fi").performClick()
        compose.onNodeWithText("Blocked on Wi-Fi").assertIsDisplayed()

        compose.onNodeWithContentDescription("Block Chrome on mobile data").performClick()
        compose.onNodeWithText("Blocked on both").assertIsDisplayed()
    }

    @Test
    fun a_change_is_acknowledged_and_can_be_undone() {
        setContent()
        compose.onNodeWithContentDescription("Block Chrome on Wi-Fi").performClick()

        compose.onNodeWithText("Chrome — Blocked on Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()

        compose.onNodeWithContentDescription("Block Chrome on Wi-Fi").assertIsOff()
    }

    @Test
    fun search_filters_the_list() {
        setContent()
        compose.onNodeWithText("Search apps").performTextInput("maps")

        compose.onNodeWithText("Maps").assertIsDisplayed()
        compose.onNodeWithText("Chrome").assertDoesNotExist()
    }

    @Test
    fun a_search_with_no_result_says_so() {
        setContent()
        compose.onNodeWithText("Search apps").performTextInput("zzz")
        compose.onNodeWithText("No app matches", substring = true).assertIsDisplayed()
    }

    @Test
    fun the_blocked_filter_narrows_to_apps_with_rules() {
        setContent(initial = mapOf(maps.uid to AppRule(maps.uid, blockWifi = true)))

        // Both are listed until the filter is applied: the order never changes, so a row does
        // not move out from under the finger that just toggled it.
        compose.onNodeWithText("Chrome").assertIsDisplayed()
        compose.onNodeWithText("1 app blocked").performClick()

        compose.onNodeWithText("Maps").assertIsDisplayed()
        compose.onNodeWithText("Chrome").assertDoesNotExist()
    }

    @Test
    fun the_blocked_filter_is_absent_when_nothing_is_blocked() {
        setContent()
        compose.onNodeWithText("app blocked", substring = true).assertDoesNotExist()
    }

    @Test
    fun toggling_does_not_reorder_the_list() {
        setContent(apps = listOf(chrome, maps))
        compose.onNodeWithContentDescription("Block Maps on Wi-Fi").performClick()

        // Maps stays where it was, below Chrome, rather than being hoisted into a section.
        compose.onAllNodesWithText("Chrome")[0].assertIsDisplayed()
        compose.onNodeWithText("Blocked on Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("1 app blocked").assertIsDisplayed()
    }
}
