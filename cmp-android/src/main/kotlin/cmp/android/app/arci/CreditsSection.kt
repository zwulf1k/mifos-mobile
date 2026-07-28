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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.arci.sdk.ArciSdk
import io.arci.sdk.launch.ArciProcess
import io.arci.sdk.launch.ArciProcessLauncher
import io.arci.sdk.launch.ArciProcessResult
import io.arci.sdk.launch.ArciStringProvider

private val PrimaryGreen = Color(0xFF2F9E3F)
private val PageBg = Color(0xFFF4F7F4)
private val Ink = Color(0xFF0F1A14)
private val Muted = Color(0xFF5C6B60)

/** i18n key prefix for the credit-visual picker family — splits registry.processes() by product. */
private const val CREDIT_LABEL_PREFIX = "ui.launch.credit."

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
    data class Launch(val process: ArciProcess, val title: String) : Route
}

/**
 * "Loan Ipoteka" host entry (Arci.Mobile#43): a THIN GENERIC launcher over the SDK's
 * [io.arci.sdk.launch.ArciProcessRegistry]. The host holds NO interactionId or card/form routing —
 * every entry (credit visual, mortgage) and its step chain come from the registry
 * ([ArciSdk.configuration]`.processRegistry`, config/server-driven), opened via [ArciProcessLauncher].
 * Native "Кредиты"/"Текущие заявки" chrome stays minimal (becomes the ARCI vitrina later, epic #29).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsSectionRoot(onExit: () -> Unit) {
    val registry = ArciSdk.configuration?.processRegistry
    val strings = ArciSdk.configuration?.stringProvider
    val context = LocalContext.current
    var route by remember { mutableStateOf<Route>(Route.Router) }

    fun label(process: ArciProcess): String =
        strings?.string(process.labelKey, locale = null) ?: process.labelKey

    Surface(modifier = Modifier.fillMaxSize(), color = PageBg) {
        if (registry == null) {
            // No registry wired — nothing to render. Keeps the host generic: it never falls back
            // to hardcoded entries.
            return@Surface
        }
        val processes = registry.processes()
        val creditVisuals = processes.filter { it.labelKey.startsWith(CREDIT_LABEL_PREFIX) }
        val mortgage = processes.firstOrNull { !it.labelKey.startsWith(CREDIT_LABEL_PREFIX) }

        when (val r = route) {
            is Route.Router -> RouterScreen(
                hasCredit = creditVisuals.isNotEmpty(),
                mortgageLabel = mortgage?.let(::label),
                onCredit = { route = Route.CreditVisuals },
                onMortgage = {
                    mortgage?.let { route = Route.Launch(it, label(it)) }
                },
                onBack = onExit,
            )
            is Route.CreditVisuals -> CreditVisualsScreen(
                processes = creditVisuals,
                labelOf = ::label,
                onPick = { p -> route = Route.Launch(p, label(p)) },
                onBack = { route = Route.Router },
            )
            is Route.Launch -> LaunchScreen(
                process = r.process,
                title = r.title,
                onBack = { route = Route.Router },
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
            Text("Текущие заявки", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Пока нет активных заявок", color = Muted, fontSize = 14.sp)
            Text("Оформить новую", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            Text("Выберите вид заявки", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            processes.forEachIndexed { i, p ->
                val text = labelOf(p)
                if (i == 0) FilledBtn(text) { onPick(p) } else OutlineBtn(text) { onPick(p) }
            }
        }
    }
}

/**
 * Opens [process] via [ArciProcessLauncher] — the SDK drives the whole steps chain (card→form or a
 * single self-contained step) with no host-side stage machine.
 */
@Composable
private fun LaunchScreen(process: ArciProcess, title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        GreenBar(title, onBack)
        ArciProcessLauncher(
            process = process,
            modifier = Modifier.fillMaxSize(),
            context = mapOf("fullName" to "Рахимова Дилноза Азизовна", "monthlyIncome" to "9400000"),
            // Host's PROFILE language (Settings > Language → LanguageConfig, persisted per-app via
            // AppCompatDelegate). Read AppCompatDelegate directly so a fresh cold start after a
            // locale change still serves the right ARCI language (see prior FlowScreen note).
            locale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
                .takeIf { !it.isEmpty }
                ?.get(0)
                ?.language
                ?: java.util.Locale.getDefault().language,
            onResult = { result ->
                when (result) {
                    is ArciProcessResult.Completed -> onBack()
                    is ArciProcessResult.Stopped -> onBack()
                }
            },
        )
    }
}

@Composable
private fun FilledBtn(label: String, onClick: () -> Unit) = Button(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().height(56.dp),
    shape = RoundedCornerShape(28.dp),
    colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen),
) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

@Composable
private fun OutlineBtn(label: String, onClick: () -> Unit) = OutlinedButton(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().height(56.dp),
    shape = RoundedCornerShape(28.dp),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryGreen),
) { Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GreenBar(title: String, onBack: () -> Unit) = TopAppBar(
    title = { Text(title, fontWeight = FontWeight.Bold, color = Ink) },
    navigationIcon = {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Ink)
        }
    },
    colors = TopAppBarDefaults.topAppBarColors(containerColor = PageBg),
)
