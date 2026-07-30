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

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import cmp.android.app.R
import io.arci.sdk.ArciSdk
import io.arci.sdk.launch.ArciFlowResult
import io.arci.sdk.launch.ArciProcess
import io.arci.sdk.launch.ArciProcessLauncher
import io.arci.sdk.launch.ArciProcessResult
import io.arci.sdk.launch.ArciStringProvider

/** i18n key prefix for the credit-visual picker family — splits registry.list() by product. */
private const val CREDIT_LABEL_PREFIX = "ui.launch.credit."
private const val CREDIT_STOREFRONT_PROCESS_KEY = "credit_storefront"

/**
 * [ArciStringProvider] over Android string resources: resolves a `ui.launch.*` labelKey to the
 * matching `ui_launch_*` resource (dots→underscores; Android resource ids can't contain dots). Locale
 * is ignored — resource resolution already follows the app's configuration/locale.
 */
fun mifosLaunchStringProvider(context: Context): ArciStringProvider =
    ArciStringProvider { labelKey, _ ->
        val resName = labelKey.replace('.', '_')
        val id = context.resources.getIdentifier(resName, "string", context.packageName)
        if (id == 0) null else context.getString(id)
    }

private sealed interface Route {
    data object Router : Route // credit vs mortgage entry point
    data object CreditVisuals : Route // credit → pick a visual (classic / narrative / chat)
    data class Launch(val processKey: String, val title: String) : Route
}

/**
 * "Loan Ipoteka" host entry (Arci.Mobile#43): a THIN GENERIC launcher over the SDK's
 * [io.arci.sdk.launch.ArciProcessRegistry]. The host holds NO interactionId or card/form routing —
 * every entry (credit visual, mortgage) and its step chain come from the registry
 * ([ArciSdk.configuration]`.processRegistry`, config/server-driven), opened by stable `processKey`
 * via [ArciProcessLauncher] — the host never holds an [ArciProcess] instance, only the key (spec §8).
 * Native "Кредиты"/"Текущие заявки" chrome stays minimal (becomes the ARCI vitrina later, epic #29).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsSectionRoot(
    initialProcessKey: String? = null,
    onExit: () -> Unit,
) {
    val registry = ArciSdk.configuration?.processRegistry
    val strings = ArciSdk.configuration?.stringProvider
    var route by remember {
        mutableStateOf<Route>(
            Route.Launch(
                initialProcessKey ?: CREDIT_STOREFRONT_PROCESS_KEY,
                initialProcessKey ?: "ui.launch.storefront",
            ),
        )
    }
    var routeHistory by remember { mutableStateOf<List<Route>>(emptyList()) }
    var processes by remember { mutableStateOf<List<ArciProcess>>(emptyList()) }

    // ArciProcessRegistry.list() is suspend (spec §8 — a real registry may hit network/DB); load
    // once per registry instance and cache locally for the picker screens.
    LaunchedEffect(registry) { processes = registry?.list().orEmpty() }

    fun label(process: ArciProcess): String =
        strings?.string(process.labelKey, locale = null) ?: process.labelKey

    fun navigate(target: Route) {
        routeHistory = routeHistory + route
        route = target
    }

    fun navigateBack() {
        val previous = routeHistory.lastOrNull()
        if (previous == null) {
            onExit()
            return
        }
        routeHistory = routeHistory.dropLast(1)
        route = previous
    }

    // Do not install an ARCI theme here. Embedded flows deliberately consume the host
    // MaterialTheme, so Mifos light/dark mode, colors, typography and shapes propagate unchanged.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val creditVisuals = processes.filter { it.labelKey.startsWith(CREDIT_LABEL_PREFIX) }
        when (val current = route) {
            Route.CreditVisuals -> CreditVisualsScreen(
                processes = creditVisuals,
                labelOf = ::label,
                onPick = { process ->
                    navigate(Route.Launch(process.key, label(process)))
                },
                onBack = ::navigateBack,
            )
            Route.Router -> Unit
            is Route.Launch -> LaunchScreen(
                processKey = current.processKey,
                title = processes.firstOrNull { it.key == current.processKey }
                    ?.let(::label)
                    ?: strings?.string(current.title, locale = null)
                    ?: current.title,
                onBack = ::navigateBack,
                onResult = { result ->
                    if (result !is ArciProcessResult.Completed) {
                        navigateBack()
                        return@LaunchScreen
                    }
                    if (current.processKey != CREDIT_STOREFRONT_PROCESS_KEY) {
                        navigateBack()
                        return@LaunchScreen
                    }
                    val selectedProcess =
                        result.last.terminalActionId
                            ?.let { selectedKey -> processes.firstOrNull { it.key == selectedKey } }
                    val routeTarget =
                        selectedProcess?.key
                            ?: result.last.returnTo?.targetRef
                            ?: result.last.fieldValues["field:routeTarget"]
                    if (selectedProcess != null) {
                        navigate(Route.Launch(selectedProcess.key, label(selectedProcess)))
                    } else if (routeTarget == "credit_lead_intake") {
                        // Compatibility for an older storefront response without a selected
                        // process action. New storefront versions route directly above.
                        navigate(Route.CreditVisuals)
                    } else {
                        val target = processes.firstOrNull {
                            it.key == routeTarget || it.appId == routeTarget
                        }
                        if (target == null) {
                            onExit()
                        } else {
                            navigate(Route.Launch(target.key, label(target)))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun RouterScreen(
    hasCredit: Boolean,
    mortgageLabel: String?,
    onCredit: () -> Unit,
    onMortgage: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        GreenBar("Кредиты", onBack)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Текущие заявки", style = MaterialTheme.typography.titleMedium)
            Text(
                "Пока нет активных заявок",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Оформить новую",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasCredit) FilledBtn("Заявка на любой кредит", onCredit)
            if (mortgageLabel != null) OutlineBtn(mortgageLabel, onMortgage)
        }
    }
}

@Composable
private fun CreditVisualsScreen(
    processes: List<ArciProcess>,
    labelOf: (ArciProcess) -> String,
    onPick: (ArciProcess) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        GreenBar("Заявка на кредит", onBack)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Выберите вид заявки", style = MaterialTheme.typography.titleMedium)
            processes.forEachIndexed { i, p ->
                val text = labelOf(p)
                if (i == 0) FilledBtn(text) { onPick(p) } else OutlineBtn(text) { onPick(p) }
            }
        }
    }
}

/**
 * Opens the process for [processKey] via [ArciProcessLauncher] — the SDK resolves the registry entry
 * and drives the whole steps chain (card→form or a single self-contained step) with no host-side
 * stage machine. The host holds only the stable key, never an interactionId or `ArciProcess` value.
 */
@Composable
private fun LaunchScreen(
    processKey: String,
    title: String,
    onBack: () -> Unit,
    onResult: (ArciProcessResult) -> Unit,
) {
    var closed by remember(processKey) { mutableStateOf(false) }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val closeOnce = {
        if (!closed) {
            closed = true
            onBack()
        }
    }
    BackHandler(onBack = closeOnce)

    Column(Modifier.fillMaxSize()) {
        // Route the host arrow through Android's dispatcher. The active SDK flow gets first chance
        // to submit its authored back transition; this host handler closes only at the process root.
        GreenBar(title) { backDispatcher?.onBackPressed() ?: closeOnce() }
        ArciProcessLauncher(
            processKey = processKey,
            modifier = Modifier.fillMaxSize(),
            // Host's PROFILE language (Settings > Language → LanguageConfig, persisted per-app via
            // AppCompatDelegate). Read AppCompatDelegate directly so a fresh cold start after a
            // locale change still serves the right ARCI language (see prior FlowScreen note).
            locale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
                .takeIf { !it.isEmpty }
                ?.get(0)
                ?.language
                ?: java.util.Locale.getDefault().language,
            onMissing = closeOnce,
            onResult = { result ->
                val retryableFailure =
                    (result as? ArciProcessResult.Stopped)?.result is ArciFlowResult.Failed
                if (retryableFailure) {
                    // ArciFlow owns its themed error and retry state. Keep it mounted; closing the
                    // process here would replace an actionable failure with the host Home screen.
                    return@ArciProcessLauncher
                }
                if (!closed) {
                    closed = true
                    onResult(result)
                }
            },
        )
    }
}

@Composable
private fun FilledBtn(label: String, onClick: () -> Unit) = Button(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().height(56.dp),
    shape = MaterialTheme.shapes.extraLarge,
) { Text(label, style = MaterialTheme.typography.labelLarge) }

@Composable
private fun OutlineBtn(label: String, onClick: () -> Unit) = OutlinedButton(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().height(56.dp),
    shape = MaterialTheme.shapes.extraLarge,
) { Text(label, style = MaterialTheme.typography.labelLarge) }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun GreenBar(title: String, onBack: () -> Unit) = TopAppBar(
    title = { Text(title, style = MaterialTheme.typography.titleLarge) },
    navigationIcon = {
        IconButton(
            onClick = onBack,
            modifier =
            Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("arci.host.back"),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.arci_back),
            )
        }
    },
    colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
    ),
)
