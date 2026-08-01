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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.arci.mobile.ir.InteractionCommand
import io.arci.mobile.ir.InteractionRuntime
import io.arci.mobile.ir.InteractionSessionPhase
import io.arci.mobile.ir.OkHttpInteractionBffClient
import io.arci.mobile.ir.UiActionSemantic
import io.arci.sdk.ArciSdk
import io.arci.sdk.launch.ArciLaunchRequest
import io.arci.sdk.launch.ArciProcess
import io.arci.sdk.launch.ArciTarget
import io.arci.sdk.toProductConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

/** One durable row of the assistant thread read-model (`entity.cstf.assistant_message`). */
private data class AssistantThreadRecord(
    val question: String,
    val answer: String?,
    val status: String,
)

private val SUGGESTION_CHIPS = listOf(
    "Ставка по ипотеке",
    "Какие документы нужны",
    "Первоначальный взнос",
    "Позвать оператора",
)

/**
 * Arci.Mobile#64 bug 2 + assistant redesign — host-owned continuous, modern conversational assistant.
 *
 * Renders the DURABLE conversation thread from the read-model (polled by conversationRef) as a
 * top-anchored scrolling list (avatar-prefixed assistant bubbles on the left, accent user bubbles on
 * the right, an animated typing indicator while the LLM answer is pending) with a sticky composer
 * (rounded input + inline send icon) that lifts above the keyboard. On send the question turn is
 * submitted HEADLESSLY through the SDK [InteractionRuntime] (OpenSession → SubmitStep) — carrying the
 * chat's single [conversationRef] so every turn threads together — WITHOUT leaving the screen; the
 * poller then surfaces the grounded answer inline. Empty state offers tappable suggestion chips.
 */
@Composable
internal fun AssistantChatScreen(
    process: ArciProcess,
    title: String,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()

    val conversationRef = rememberSaveable { UUID.randomUUID().toString() }
    val httpClient = remember { OkHttpClient() }

    var records by remember { mutableStateOf<List<AssistantThreadRecord>>(emptyList()) }
    var pollError by remember { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    // Optimistic bubble: shows the just-sent question instantly, until the read-model row appears.
    var pendingQuestion by remember { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }

    val baseUrl = remember { mutableStateOf<String?>(null) }
    val step = remember(process) { process.steps.first() }
    LaunchedEffect(process) {
        baseUrl.value = ArciSdk.configuration
            ?.endpointResolver
            ?.resolve(ArciLaunchRequest(target = ArciTarget(process.appId, step.interactionId)))
            ?.baseUrl
    }

    // Poll the durable thread ~1s while mounted so a freshly answered row shows inline.
    LaunchedEffect(baseUrl.value, conversationRef) {
        val base = baseUrl.value ?: return@LaunchedEffect
        while (true) {
            try {
                val url = "${base.trimEnd('/')}/api/read/records" +
                    "?entityId=entity.cstf.assistant_message&appId=credit_storefront" +
                    "&projection=detail&eq.conversationRef=${URLEncoder.encode(conversationRef, "UTF-8")}" +
                    "&sort=createdAt&order=asc"
                val body = withContext(Dispatchers.IO) {
                    httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        r.body?.string().orEmpty()
                    }
                }
                val json = JSONObject(body)
                val arr = json.optJSONArray("records")
                val next = ArrayList<AssistantThreadRecord>(arr?.length() ?: 0)
                for (i in 0 until (arr?.length() ?: 0)) {
                    val r = arr?.optJSONObject(i) ?: continue
                    // org.json quirk: optString on a JSON null returns the literal "null" — guard it
                    // so a still-pending answer stays null (→ typing indicator), never a "null" bubble.
                    val answer = if (r.isNull("answer")) null
                    else r.optString("answer").takeIf { it.isNotBlank() && it != "null" }
                    next += AssistantThreadRecord(r.optString("question"), answer, r.optString("status"))
                }
                records = next
                pollError = null
                // Drop the optimistic bubble once its row is durable.
                if (pendingQuestion != null && next.any { it.question == pendingQuestion }) {
                    pendingQuestion = null
                }
            } catch (e: Exception) {
                pollError = e.message ?: "Ошибка соединения с сервером."
            }
            delay(1000)
        }
    }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        input = ""
        sendError = null
        pendingQuestion = q
        scope.launch {
            try {
                submitAssistantTurn(process, step.interactionId, conversationRef, q)
                // The poller surfaces the row + grounded answer; keep the optimistic bubble until then.
            } catch (e: Exception) {
                pendingQuestion = null
                sendError = e.message ?: "Не удалось отправить вопрос. Попробуйте ещё раз."
            }
        }
    }

    // #64 bug 1: imePadding lifts the WHOLE chat (thread + composer) above the soft keyboard; the host
    // window is edge-to-edge so nothing resizes automatically.
    Column(Modifier.fillMaxSize().imePadding()) {
        GreenBar(title, onBack)

        val listState = rememberLazyListState()
        val hasThread = records.isNotEmpty() || pendingQuestion != null
        // Auto-scroll to the newest content.
        LaunchedEffect(records.size, pendingQuestion) {
            val count = records.size + (if (pendingQuestion != null) 1 else 0)
            if (count > 0) listState.animateScrollToItem(count - 1)
        }

        if (!hasThread) {
            AssistantEmptyState(
                onChip = { send(it) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (rec in records) {
                    item { UserBubble(rec.question) }
                    when {
                        rec.answer != null -> item { AssistantBubble(rec.answer) }
                        rec.status == "FAILED" -> item {
                            AssistantBubble("Не удалось получить ответ. Попробуйте задать вопрос ещё раз.", error = true)
                        }
                        else -> item { TypingBubble() }
                    }
                }
                pendingQuestion?.let { pq ->
                    if (records.none { it.question == pq }) {
                        item { UserBubble(pq) }
                        item { TypingBubble() }
                    }
                }
            }
        }

        sendError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        pollError?.takeIf { records.isEmpty() }?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            onSend = { send(input) },
        )
    }
}

// ── Composer ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Composer(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Задайте вопрос об кредитных продуктах") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            val enabled = value.isNotBlank()
            Surface(
                onClick = onSend,
                enabled = enabled,
                shape = CircleShape,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Отправить",
                        tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

// ── Bubbles ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun AssistantBubble(text: String, error: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        AssistantAvatar()
        Spacer(Modifier.size(8.dp))
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
            color = if (error) Color(0xFFFDECEC) else MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (error) Color(0xFFC0392B) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistantAvatar()
        Spacer(Modifier.size(8.dp))
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 1.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val transition = rememberInfiniteTransition(label = "typing")
                for (i in 0..2) {
                    val a by transition.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = i * 180),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot$i",
                    )
                    Box(
                        Modifier.size(8.dp).alpha(a),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onSurfaceVariant) {
                            Box(Modifier.size(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantAvatar() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "U",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantEmptyState(onChip: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "U",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Здравствуйте! Я ассистент Uy — спрошу что вас интересует по кредитам.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(20.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (chip in SUGGESTION_CHIPS) {
                SuggestionChip(onClick = { onChip(chip) }, label = { Text(chip) })
            }
        }
    }
}

// ── Headless turn submit (OpenSession → SubmitStep) ─────────────────────────────────────────────

/**
 * Submits ONE assistant question turn without any visible interaction UI: opens a fresh session for
 * [interactionId], waits until the step is ready, then submits the seeded fields + the question +
 * this chat's [conversationRef] under the step's submit action. The interaction completes SERVER-SIDE
 * (persists the row, fires the grounded-answer flow); the caller's read-model poller surfaces it.
 */
private suspend fun submitAssistantTurn(
    process: ArciProcess,
    interactionId: String,
    conversationRef: String,
    question: String,
) {
    val cfg = ArciSdk.configuration ?: error("ArciSdk not configured")
    val target = ArciTarget(process.appId, interactionId)
    val endpoint = cfg.endpointResolver.resolve(ArciLaunchRequest(target = target))
    val product = endpoint.toProductConfig(target, "ru")
    val bff = OkHttpInteractionBffClient(product.baseUrl, cfg.httpClientProvider())
    val rt = InteractionRuntime(bff)
    try {
        rt.dispatch(InteractionCommand.OpenSession(product))
        // Wait until the step is interactive.
        val ready = rt.state.first {
            it.presentation != null &&
                (it.phase == InteractionSessionPhase.ready || it.phase == InteractionSessionPhase.validation_error)
        }
        val model = ready.presentation!!
        val actionId = model.actions.firstOrNull { it.semantic == UiActionSemantic.submit }?.id
            ?: model.actions.firstOrNull { it.semantic != UiActionSemantic.cancel }?.id
            ?: "action:cstf_ask_send"
        val fields = ready.fieldValues.toMutableMap()
        fields["field:cstf_question"] = question
        fields["field:cstf_conversation_ref"] = conversationRef
        rt.dispatch(
            InteractionCommand.SubmitStep(
                actionId = actionId,
                fieldValues = fields,
                idempotencyKey = UUID.randomUUID().toString(),
            ),
        )
        // Wait for the turn to terminate (persisted) or surface a fatal error.
        rt.state.first {
            it.phase == InteractionSessionPhase.terminal_success ||
                it.phase == InteractionSessionPhase.terminal_business_stop ||
                it.phase == InteractionSessionPhase.fatal_error
        }
    } finally {
        rt.shutdown()
    }
}
