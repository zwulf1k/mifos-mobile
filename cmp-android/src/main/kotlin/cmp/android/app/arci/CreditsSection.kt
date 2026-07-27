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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.arci.sdk.flow.ArciFlow
import io.arci.sdk.launch.ArciFlowResult
import io.arci.sdk.launch.ArciLaunchRequest
import io.arci.sdk.launch.ArciTarget

private val PrimaryGreen = Color(0xFF2F9E3F)
private val PageBg = Color(0xFFF4F7F4)
private val Ink = Color(0xFF0F1A14)
private val Muted = Color(0xFF5C6B60)

// Credit application in its three ARCI presentation visuals. Each visual is a two-step chain on
// :9900 (appId=credit_lead_intake): a CONDITION CARD first, then — when the card CTA drives the
// flow to __completed__ (ArciFlowResult.Completed) — the matching lead FORM.
private enum class CreditVisual(
    val label: String,
    val conditionsInteractionId: String,
    val formInteractionId: String,
) {
    CLASSIC("Классика", "interaction.clin.credit_lead_conditions", "interaction.clin.credit_lead"),
    NARRATIVE("Нарратив", "interaction.clin.credit_lead_conditions_modern", "credit_lead_modern"),
    CHAT("Чат", "interaction.clin.credit_lead_conditions_chat", "credit_lead_chat"),
}

private sealed interface Route {
    data object Router : Route // 3a: credit vs mortgage (two apps / two BFFs)
    data object CreditVisuals : Route // credit → pick a visual (classic / narrative / chat)

    // Card→form chain: renders [conditionsInteractionId] first, then [formInteractionId] once the
    // card completes. Cross-interaction routing is a host concern until an ArciProcessRegistry lands.
    data class Flow(
        val appId: String,
        val conditionsInteractionId: String,
        val formInteractionId: String,
        val title: String,
    ) : Route
}

/**
 * "Loan Ipoteka" host entry. Level 1 (mockup 3a): current credits + credit vs mortgage — two
 * different ARCI apps on two BFFs (endpointResolver maps appId→port). Level 2 for credit: pick a
 * visual (Классика/Нарратив/Чат) = three ARCI presentations of the same lead. Cross-app AND
 * cross-interaction routing are host concerns until an ArciProcessRegistry lands in the SDK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsSectionRoot(onExit: () -> Unit) {
    var route by remember { mutableStateOf<Route>(Route.Router) }
    Surface(modifier = Modifier.fillMaxSize(), color = PageBg) {
        when (val r = route) {
            is Route.Router -> RouterScreen(
                onCredit = { route = Route.CreditVisuals },
                onMortgage = {
                    route = Route.Flow(
                        appId = "mortgage",
                        conditionsInteractionId = "interaction.mtg.mortgage_conditions",
                        formInteractionId = "interaction.mtg.mortgage_apply",
                        title = "Заявка на ипотеку",
                    )
                },
                onBack = onExit,
            )
            is Route.CreditVisuals -> CreditVisualsScreen(
                onPick = { v ->
                    route = Route.Flow(
                        appId = "credit_lead_intake",
                        conditionsInteractionId = v.conditionsInteractionId,
                        formInteractionId = v.formInteractionId,
                        title = "Оформление · ${v.label}",
                    )
                },
                onBack = { route = Route.Router },
            )
            is Route.Flow -> FlowScreen(r) { route = Route.Router }
        }
    }
}

@Composable
private fun RouterScreen(onCredit: () -> Unit, onMortgage: () -> Unit, onBack: () -> Unit) {
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
            FilledBtn("Заявка на любой кредит", onCredit)
            OutlineBtn("Заявка на ипотеку", onMortgage)
        }
    }
}

@Composable
private fun CreditVisualsScreen(onPick: (CreditVisual) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        GreenBar("Заявка на кредит", onBack)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Выберите вид заявки", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            CreditVisual.entries.forEachIndexed { i, v ->
                if (i == 0) FilledBtn(v.label) { onPick(v) } else OutlineBtn(v.label) { onPick(v) }
            }
        }
    }
}

// Two-stage chain within a single visual: CARD → FORM.
private enum class FlowStage { CARD, FORM }

@Composable
private fun FlowScreen(flow: Route.Flow, onBack: () -> Unit) {
    var stage by remember(flow) { mutableStateOf(FlowStage.CARD) }
    val interactionId = when (stage) {
        FlowStage.CARD -> flow.conditionsInteractionId
        FlowStage.FORM -> flow.formInteractionId
    }
    Column(Modifier.fillMaxSize()) {
        GreenBar(flow.title, onBack)
        ArciFlow(
            // Key the surface by stage so the card→form transition tears down the card session and
            // starts a fresh flow for the form interaction.
            request = ArciLaunchRequest(
                target = ArciTarget(appId = flow.appId, interactionId = interactionId),
                context = mapOf("fullName" to "Рахимова Дилноза Азизовна", "monthlyIncome" to "9400000"),
                // Host's current app language (set via Settings > Language, applied through
                // AppCompatDelegate.setApplicationLocales + Locale.setDefault in MainActivity) —
                // forward it so ARCI serves the flow's copy in the user's chosen locale instead
                // of falling back to the BFF/SDK default.
                locale = java.util.Locale.getDefault().language,
            ),
            modifier = Modifier.fillMaxSize(),
            onResult = { result ->
                when {
                    // Card CTA «Оформить заявку» completed → advance to the matching FORM.
                    stage == FlowStage.CARD && result is ArciFlowResult.Completed ->
                        stage = FlowStage.FORM
                    // Form finished, or any non-completion terminal (declined/failed/cancelled) →
                    // back to the router.
                    else -> onBack()
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
