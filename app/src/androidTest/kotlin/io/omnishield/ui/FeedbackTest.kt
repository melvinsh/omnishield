package io.omnishield.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.omnishield.ui.components.ConfirmDialog
import io.omnishield.ui.components.TextInputDialog
import io.omnishield.ui.theme.OmniShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two dialogs that stand between the user and an irreversible or unusable outcome.
 *
 * Both destructive actions in this app used to fire on a single tap with no confirmation and
 * no acknowledgement, and the resolver had no input path at all, so there was nothing to
 * validate. These assert the guard rails rather than the appearance.
 */
@RunWith(AndroidJUnit4::class)
class FeedbackTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun dismissing_the_confirmation_does_not_run_the_action() {
        var ran = false
        var open = true
        compose.setContent {
            OmniShieldTheme(dynamicColor = false) {
                if (open) {
                    ConfirmDialog(
                        title = "Clear request history?",
                        body = "This deletes all 12 stored entries.",
                        confirmLabel = "Clear",
                        onConfirm = { ran = true },
                        onDismiss = { open = false },
                    )
                }
            }
        }

        compose.onNodeWithText("This deletes all 12 stored entries.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        assertFalse("Cancel must not perform the action", ran)
    }

    @Test
    fun confirming_runs_the_action_once_and_closes() {
        var runs = 0
        var open by mutableStateOf(true)
        compose.setContent {
            OmniShieldTheme(dynamicColor = false) {
                if (open) {
                    ConfirmDialog(
                        title = "Clear request history?",
                        body = "This deletes all 12 stored entries.",
                        confirmLabel = "Clear",
                        onConfirm = { runs++ },
                        onDismiss = { open = false },
                    )
                }
            }
        }

        compose.onNodeWithText("Clear").performClick()

        assertEquals(1, runs)
        compose.onNodeWithText("Clear request history?").assertDoesNotExist()
    }

    @Test
    fun the_resolver_dialog_refuses_a_hostname_and_accepts_an_address() {
        compose.setContent {
            var value by remember { mutableStateOf("https://1.1.1.1/dns-query") }
            OmniShieldTheme(dynamicColor = false) {
                TextInputDialog(
                    title = "Change resolver",
                    label = "DoH endpoint (https://…)",
                    supporting = "Must be an IP address, not a hostname.",
                    value = value,
                    error = validateResolver(value, doh = true)?.let { "bad" },
                    onValueChange = { value = it },
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Save").assertIsEnabled()

        compose.onNodeWithText("https://1.1.1.1/dns-query").performTextClearance()
        compose.onNodeWithText("DoH endpoint (https://…)")
            .performTextInput("https://dns.google/dns-query")

        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun save_is_disabled_on_an_empty_field() {
        compose.setContent {
            var value by remember { mutableStateOf("") }
            OmniShieldTheme(dynamicColor = false) {
                TextInputDialog(
                    title = "Change resolver",
                    label = "Resolver IP address",
                    supporting = "Must be an IP address, not a hostname.",
                    value = value,
                    error = validateResolver(value, doh = false)?.let { "bad" },
                    onValueChange = { value = it },
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Resolver IP address").performTextInput("9.9.9.9")
        compose.onNodeWithText("Save").assertIsEnabled()
    }
}
