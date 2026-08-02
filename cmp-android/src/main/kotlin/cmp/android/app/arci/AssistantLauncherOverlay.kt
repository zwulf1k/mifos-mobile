/*
 * Copyright 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * See https://github.com/openMF/mobile-mobile/blob/master/LICENSE.md
 */
package cmp.android.app.arci

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.arci.sdk.ArciSdk
import io.arci.sdk.launch.ArciProcess

/** Bundled launch-registry key for the host-owned LLM assistant (see assets/launch-registry.yaml). */
private const val ASSISTANT_LAUNCH_KEY = "assistant"

/**
 * Arci.Mobile#77 — global, always-available central assistant button (Sber-style).
 *
 * A persistent floating button pinned bottom-center of the authenticated app that opens the
 * host-owned continuous [AssistantChatScreen] as a full-screen overlay — WITHOUT going through the
 * credit vitrina. The `assistant` [ArciProcess] is resolved from the config-/server-driven
 * [io.arci.sdk.launch.ArciProcessRegistry] (host holds only the stable key, never an interactionId).
 *
 * [visible] is driven by the host from [org.mifos.mobile.core.common.ArciEntryBridge], so the button
 * appears only inside the authenticated app (not splash/login/passcode) and hides while the embedded
 * credit section — which has its own in-flow assistant entry — is open.
 */
@Composable
internal fun AssistantLauncherOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val registry = ArciSdk.configuration?.processRegistry
    val strings = ArciSdk.configuration?.stringProvider
    var open by rememberSaveable { mutableStateOf(false) }
    var assistantProcess by remember { mutableStateOf<ArciProcess?>(null) }

    // ArciProcessRegistry.list() is suspend (a real registry may hit network/DB); resolve the
    // assistant process once per registry instance.
    LaunchedEffect(registry) {
        assistantProcess = registry?.list().orEmpty().firstOrNull { it.key == ASSISTANT_LAUNCH_KEY }
    }

    if (open) {
        // Full-screen chat overlay. Consumes the host MaterialTheme unchanged (no ARCI theme here),
        // matching CreditsSection's embedded-flow stance.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val process = assistantProcess
            if (process != null) {
                AssistantChatScreen(
                    process = process,
                    title = strings?.string(process.labelKey, locale = null) ?: process.labelKey,
                    locale = assistantLocale(),
                    onBack = { open = false },
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        return
    }

    // Central Sber-style launcher, lifted above the bottom navigation bar.
    Box(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(bottom = 76.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        ExtendedFloatingActionButton(
            onClick = { open = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.testTag(tag = "AssistantLauncherButton"),
            icon = { Icon(imageVector = Icons.Filled.Face, contentDescription = null) },
            text = { Text(text = assistantButtonLabel()) },
        )
    }
}

/** Active app language for the assistant (mirrors CreditsSection.currentArciLocale). */
private fun assistantLocale(): String =
    androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        .takeIf { !it.isEmpty }
        ?.get(0)
        ?.language
        ?: java.util.Locale.getDefault().language

/** Locale-matched button caption (host fallback copy; the SDK carries no i18n bundle). */
private fun assistantButtonLabel(): String = when (assistantLocale()) {
    "en" -> "Assistant"
    "uz" -> "Yordamchi"
    else -> "Ассистент"
}
