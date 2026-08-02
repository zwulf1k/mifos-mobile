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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalDensity
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

/**
 * Arci.Mobile#66 — max time a turn may sit answer-less (row not yet ANSWERED/FAILED) before the chat
 * gives up waiting and shows the visible error bubble instead of an endless typing spinner. Covers the
 * pathological case where the backend never terminates the turn at all (LLM step threw before the
 * write-back sink, so the row is stuck ASKED). 45s comfortably exceeds a normal grounded round-trip.
 */
private const val STALL_TIMEOUT_MS = 45_000L

/** One durable row of the assistant thread read-model (`entity.cstf.assistant_message`). */
private data class AssistantThreadRecord(
    val question: String,
    val answer: String?,
    val status: String,
)

/**
 * Static UI strings for the assistant chat, resolved for the ACTIVE app locale. The greeting +
 * composer placeholder + send label are ALSO served (authoritative) by the BFF presentation
 * (`ui.cstf.chat.*`, localized by the session-open `locale` param) — these literals are the offline
 * fallback used until that presentation resolves, and the source of the host-only suggestion chips
 * and error copy, which the presentation does not carry. Keyed off the active locale so an English
 * device renders English chrome, a Russian device Russian — matching the rest of the shell.
 */
private data class ChatI18n(
    val greeting: String,
    val placeholder: String,
    val sendLabel: String,
    val chips: List<String>,
    val answerFailed: String,
    val sendFailed: String,
    val connError: String,
    val retry: String,
)

private fun chatI18n(locale: String): ChatI18n = when {
    locale.startsWith("en") -> ChatI18n(
        greeting = "Hi! I'm the Uy assistant — ask me anything about our credit products.",
        placeholder = "Ask a question about our credit products",
        sendLabel = "Send",
        chips = listOf(
            "Mortgage rate",
            "What documents are needed",
            "Down payment",
            "Talk to an operator",
        ),
        answerFailed = "⚠ Couldn't get a reply, please try again.",
        sendFailed = "Couldn't send your question. Please try again.",
        connError = "Connection error.",
        retry = "Try again",
    )
    locale.startsWith("uz") -> ChatI18n(
        greeting = "Salom! Men Uy yordamchisiman — kredit mahsulotlari haqida so'rang.",
        placeholder = "Kredit mahsulotlari haqida savol bering",
        sendLabel = "Yuborish",
        chips = listOf(
            "Ipoteka stavkasi",
            "Qanday hujjatlar kerak",
            "Boshlang'ich to'lov",
            "Operatorni chaqirish",
        ),
        answerFailed = "⚠ Javob olinmadi, iltimos qayta urinib ko'ring.",
        sendFailed = "Savol yuborilmadi. Iltimos, qayta urinib ko'ring.",
        connError = "Server bilan aloqa xatosi.",
        retry = "Qayta urinish",
    )
    else -> ChatI18n(
        greeting = "Здравствуйте! Я ассистент Uy — спрошу что вас интересует по кредитам.",
        placeholder = "Задайте вопрос об кредитных продуктах",
        sendLabel = "Отправить",
        chips = listOf(
            "Ставка по ипотеке",
            "Какие документы нужны",
            "Первоначальный взнос",
            "Позвать оператора",
        ),
        answerFailed = "⚠ Не удалось получить ответ, попробуйте ещё раз.",
        sendFailed = "Не удалось отправить вопрос. Попробуйте ещё раз.",
        connError = "Ошибка соединения с сервером.",
        retry = "Попробовать снова",
    )
}

/** Authoritative localized chat chrome from the BFF presentation (`ui.cstf.chat.*`). */
private data class AssistantStrings(
    val greeting: String?,
    val placeholder: String?,
    val sendLabel: String?,
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
    locale: String,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val i18n = remember(locale) { chatI18n(locale) }

    val conversationRef = rememberSaveable { UUID.randomUUID().toString() }
    val httpClient = remember { OkHttpClient() }

    var records by remember { mutableStateOf<List<AssistantThreadRecord>>(emptyList()) }
    var pollError by remember { mutableStateOf<String?>(null) }
    // Arci.Mobile#66 — stall watchdog. A turn is normally terminated by the reply flow (ANSWERED, or
    // now FAILED on a failed/empty completion — CreditStorefront#11). But if a turn NEVER terminates
    // (row stuck ASKED because the LLM step threw before write-back, or the answer never lands), the
    // typing indicator would otherwise spin forever — a silent failure. `nowMs` ticks each poll and
    // `pendingSince` stamps when each question first went pending; a turn still answer-less past
    // STALL_TIMEOUT_MS is surfaced as the SAME visible error bubble, so the spinner can never be
    // infinite regardless of what the backend does.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val pendingSince = remember { mutableMapOf<String, Long>() }
    var input by rememberSaveable { mutableStateOf("") }
    // Optimistic bubble: shows the just-sent question instantly, until the read-model row appears.
    var pendingQuestion by remember { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    // Authoritative localized chrome from the BFF presentation (ui.cstf.chat.*), fetched with the
    // active locale; until it resolves (or if it fails) the locale-matched fallback copy is shown.
    var presentation by remember { mutableStateOf<AssistantStrings?>(null) }

    val baseUrl = remember { mutableStateOf<String?>(null) }
    val step = remember(process) { process.steps.first() }
    LaunchedEffect(process) {
        baseUrl.value = ArciSdk.configuration
            ?.endpointResolver
            ?.resolve(ArciLaunchRequest(target = ArciTarget(process.appId, step.interactionId)))
            ?.baseUrl
    }
    // Resolve the localized greeting / placeholder / send label from the BFF presentation using the
    // ACTIVE locale — so English device → English chat chrome. Non-blocking: fallback copy shows first.
    LaunchedEffect(process, locale) {
        presentation = runCatching {
            fetchAssistantStrings(process, step.interactionId, locale)
        }.getOrNull()
    }

    val greeting = presentation?.greeting ?: i18n.greeting
    val placeholder = presentation?.placeholder ?: i18n.placeholder
    val sendLabel = presentation?.sendLabel ?: i18n.sendLabel

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
                    val answer = if (r.isNull("answer")) {
                        null
                    } else {
                        r.optString("answer").takeIf { it.isNotBlank() && it != "null" }
                    }
                    next += AssistantThreadRecord(r.optString("question"), answer, r.optString("status"))
                }
                records = next
                pollError = null
                // Tick the clock and stamp/clear per-question pending timers for the stall watchdog.
                nowMs = System.currentTimeMillis()
                for (rec in next) {
                    val answerless = rec.answer == null && rec.status != "FAILED"
                    if (answerless) pendingSince.getOrPut(rec.question) { nowMs }
                    else pendingSince.remove(rec.question)
                }
                // Drop the optimistic bubble once its row is durable.
                if (pendingQuestion != null && next.any { it.question == pendingQuestion }) {
                    pendingQuestion = null
                }
            } catch (e: Exception) {
                pollError = e.message ?: i18n.connError
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
                submitAssistantTurn(process, step.interactionId, conversationRef, q, locale)
                // The poller surfaces the row + grounded answer; keep the optimistic bubble until then.
            } catch (e: Exception) {
                pendingQuestion = null
                sendError = e.message ?: i18n.sendFailed
            }
        }
    }

    // #64 bug 1: imePadding lifts the WHOLE chat (thread + composer) above the soft keyboard; the host
    // window is edge-to-edge so nothing resizes automatically.
    Column(Modifier.fillMaxSize().imePadding()) {
        GreenBar(title, onBack)

        val listState = rememberLazyListState()
        val hasThread = records.isNotEmpty() || pendingQuestion != null
        val pendingShown = pendingQuestion != null && records.none { it.question == pendingQuestion }
        // Deterministic item count: 2 bubbles per record (question + answer/typing/failed), 2 more for
        // an optimistic pending turn, and 1 trailing sentinel spacer that lets the newest bubble scroll
        // fully ABOVE the composer. Computed from data (not layoutInfo) so it is exact on the frame the
        // effect fires.
        val itemCount = records.size * 2 + (if (pendingShown) 2 else 0) + 1
        // Keyboard visibility — re-triggers the auto-scroll when the IME opens/closes so the latest
        // bubble stays visible in the resized area above the composer (fires on the transition, not
        // every animation frame).
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        // Auto-scroll to the TRUE bottom (the sentinel) on every thread change — new question, typing
        // indicator appearing, typing→answer swap (last record's answer/status change), the optimistic
        // bubble, and keyboard open. Landing on the sentinel guarantees the end of a long last answer
        // is reached, not just its start.
        LaunchedEffect(
            itemCount,
            records.lastOrNull()?.answer,
            records.lastOrNull()?.status,
            pendingQuestion,
            imeVisible,
        ) {
            if (hasThread) listState.animateScrollToItem(itemCount - 1)
        }

        if (!hasThread) {
            AssistantEmptyState(
                greeting = greeting,
                chips = i18n.chips,
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
                    // Error when the row is terminally FAILED, OR terminated with no answer, OR has
                    // stalled answer-less past the watchdog — any of these ends the typing spinner.
                    val stalled = rec.answer == null &&
                        (nowMs - (pendingSince[rec.question] ?: nowMs)) > STALL_TIMEOUT_MS
                    val errored = rec.status == "FAILED" || stalled
                    when {
                        rec.answer != null -> item { AssistantBubble(rec.answer) }
                        errored -> item {
                            AssistantErrorBubble(i18n.answerFailed, i18n.retry, onRetry = { send(rec.question) })
                        }
                        else -> item { TypingBubble() }
                    }
                }
                if (pendingShown) {
                    item { UserBubble(pendingQuestion!!) }
                    item { TypingBubble() }
                }
                // Trailing spacer: the reachable bottom of the scroll region, so the newest bubble can
                // sit clear of the composer and a long answer's tail is fully scrollable into view.
                item { Spacer(Modifier.height(12.dp)) }
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
            placeholder = placeholder,
            sendLabel = sendLabel,
            onValueChange = { input = it },
            onSend = { send(input) },
        )
    }
}

// ── Composer ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Composer(
    value: String,
    placeholder: String,
    sendLabel: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
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
                placeholder = { Text(placeholder) },
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
                        contentDescription = sendLabel,
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

/**
 * Arci.Mobile#66 — visible assistant-side error state shown INSTEAD of the endless typing indicator
 * when a turn fails (status=FAILED / terminal-null / stalled). Red-tinted bubble carrying the
 * localized "couldn't get a reply" copy plus an inline Retry affordance that re-sends the question.
 */
@Composable
private fun AssistantErrorBubble(text: String, retryLabel: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        AssistantAvatar()
        Spacer(Modifier.size(8.dp))
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
            color = Color(0xFFFDECEC),
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFC0392B),
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    onClick = onRetry,
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFC0392B),
                ) {
                    Text(
                        retryLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
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
private fun AssistantEmptyState(
    greeting: String,
    chips: List<String>,
    onChip: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            greeting,
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
            for (chip in chips) {
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
    locale: String,
) {
    val cfg = ArciSdk.configuration ?: error("ArciSdk not configured")
    val target = ArciTarget(process.appId, interactionId)
    val endpoint = cfg.endpointResolver.resolve(ArciLaunchRequest(target = target))
    val product = endpoint.toProductConfig(target, locale)
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

/**
 * Reads the authoritative, LOCALE-resolved chat chrome (greeting / composer placeholder / send label)
 * from the BFF presentation: opens a read-only session for [interactionId] with the ACTIVE [locale],
 * pulls the `ui.cstf.chat.*` strings the BFF localized (props `chatIntro` / `chatPrompt` / `done`),
 * then shuts the session down without submitting. So an English device gets English chat strings, a
 * Russian device Russian — the same locale the host passes on every turn.
 */
private suspend fun fetchAssistantStrings(
    process: ArciProcess,
    interactionId: String,
    locale: String,
): AssistantStrings {
    val cfg = ArciSdk.configuration ?: return AssistantStrings(null, null, null)
    val target = ArciTarget(process.appId, interactionId)
    val endpoint = cfg.endpointResolver.resolve(ArciLaunchRequest(target = target))
    val product = endpoint.toProductConfig(target, locale)
    val bff = OkHttpInteractionBffClient(product.baseUrl, cfg.httpClientProvider())
    val rt = InteractionRuntime(bff)
    try {
        rt.dispatch(InteractionCommand.OpenSession(product))
        val ready = rt.state.first {
            it.presentation != null &&
                (it.phase == InteractionSessionPhase.ready || it.phase == InteractionSessionPhase.validation_error)
        }
        val model = ready.presentation!!
        fun prop(key: String): String? =
            model.nodes.values.firstNotNullOfOrNull { it.props[key]?.takeIf(String::isNotBlank) }
        return AssistantStrings(
            greeting = prop("chatIntro"),
            placeholder = prop("chatPrompt"),
            sendLabel = prop("done"),
        )
    } finally {
        rt.shutdown()
    }
}
