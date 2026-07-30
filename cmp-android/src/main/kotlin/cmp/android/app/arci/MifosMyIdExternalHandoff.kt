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

import android.content.SharedPreferences
import android.util.Base64
import cmp.android.app.BuildConfig
import io.arci.sdk.handoff.ArciExternalHandoffAdapter
import io.arci.sdk.handoff.ArciExternalHandoffRequest
import io.arci.sdk.handoff.ArciExternalHandoffResult
import io.arci.sdk.handoff.ArciExternalHandoffResultStore
import io.arci.sdk.handoff.ArciExternalHandoffScope
import io.arci.sdk.handoff.ArciExternalHandoffStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Debug-only control used by emulator/QATools to exercise every official MyID callback outcome.
 *
 * Production deliberately defaults to [ArciExternalHandoffStatus.ERROR] until the official MyID
 * widget adapter is supplied. It must never manufacture a successful regulatory identification.
 */
object MifosMyIdTestControl {
    @Volatile
    private var requestedOutcome: String? = null

    @Volatile
    private var profilePinflFixture: String? = null

    fun configure(outcome: String?, profilePinfl: String?) {
        requestedOutcome = outcome?.trim()?.lowercase()
        profilePinflFixture = profilePinfl?.trim().takeIf { BuildConfig.DEBUG }
        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                "MifosMyId",
                "configuredOutcome=${requestedOutcome ?: "error"} " +
                    "profilePinflFixture=${profilePinflFixture != null}",
            )
        }
    }

    internal fun profilePinfl(): String? =
        profilePinflFixture.takeIf { BuildConfig.DEBUG }

    internal fun status(): ArciExternalHandoffStatus {
        if (!BuildConfig.DEBUG) return ArciExternalHandoffStatus.ERROR
        return when (requestedOutcome) {
            "success" -> ArciExternalHandoffStatus.SUCCESS
            "cancelled" -> ArciExternalHandoffStatus.CANCELLED
            "expired" -> ArciExternalHandoffStatus.EXPIRED
            else -> ArciExternalHandoffStatus.ERROR
        }
    }
}

class MifosMyIdExternalHandoffAdapter : ArciExternalHandoffAdapter {
    override suspend fun execute(
        request: ArciExternalHandoffRequest,
    ): ArciExternalHandoffResult {
        val pinfl = request.identity.pinfl
        if (pinfl == null) {
            return ArciExternalHandoffResult(
                requestId = request.requestId,
                scope = request.scope,
                status = ArciExternalHandoffStatus.ERROR,
                errorCode = "MYID_PINFL_MISSING_OR_INVALID",
            )
        }

        // Production integration passes this trusted value as MyID passportData (old flow), or
        // uses it when creating the backend MyID session (new flow). Never log the value.
        val status = MifosMyIdTestControl.status()
        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                "MifosMyId",
                "execute provider=${request.scope.provider} status=$status",
            )
        }
        return ArciExternalHandoffResult(
            requestId = request.requestId,
            scope = request.scope,
            status = status,
            errorCode = when (status) {
                ArciExternalHandoffStatus.ERROR -> if (BuildConfig.DEBUG) {
                    "MYID_TEST_ERROR"
                } else {
                    "MYID_WIDGET_NOT_CONFIGURED"
                }
                else -> null
            },
        )
    }
}

/**
 * Host-owned durable handoff store. Attempt keys and latest pointers include the complete
 * app/interaction/session/step/provider/bind scope, preventing results leaking across clients or
 * resumed applications. No profile or document data is stored here.
 */
class SharedPreferencesExternalHandoffResultStore(
    private val preferences: SharedPreferences,
) : ArciExternalHandoffResultStore {
    private val lock = Any()

    override suspend fun resultForAttempt(
        scope: ArciExternalHandoffScope,
        requestId: String,
    ): ArciExternalHandoffResult? =
        preferences.getString(attemptKey(scope, requestId), null)?.let(::decodeResult)

    override suspend fun latest(
        scope: ArciExternalHandoffScope,
    ): ArciExternalHandoffResult? =
        preferences.getString(latestKey(scope), null)?.let(::decodeResult)

    override suspend fun saveIfAbsent(
        result: ArciExternalHandoffResult,
    ): ArciExternalHandoffResult = synchronized(lock) {
        val key = attemptKey(result.scope, result.requestId)
        preferences.getString(key, null)?.let(::decodeResult) ?: result.also {
            val encoded = encodeResult(it)
            check(
                preferences.edit()
                    .putString(key, encoded)
                    .putString(latestKey(it.scope), encoded)
                    .commit(),
            ) {
                "Unable to persist authoritative external handoff result"
            }
        }
    }

    private fun attemptKey(scope: ArciExternalHandoffScope, requestId: String): String =
        "attempt.${scopeHash(scope)}.${sha256(requestId)}"

    private fun latestKey(scope: ArciExternalHandoffScope): String =
        "latest.${scopeHash(scope)}"

    private fun scopeHash(scope: ArciExternalHandoffScope): String =
        sha256(
            listOf(
                scope.appId,
                scope.interactionId,
                scope.sessionKey,
                scope.stepId,
                scope.provider,
                scope.resultBind,
            ).joinToString(separator = "\u001f"),
        )

    private fun encodeResult(result: ArciExternalHandoffResult): String =
        listOf(
            result.requestId,
            result.scope.appId,
            result.scope.interactionId,
            result.scope.sessionKey,
            result.scope.stepId,
            result.scope.provider,
            result.scope.resultBind,
            result.status.name,
            result.errorCode.orEmpty(),
        ).joinToString(".") { encode(it) }

    private fun decodeResult(encoded: String): ArciExternalHandoffResult {
        val values = encoded.split('.').map(::decode)
        require(values.size == RESULT_PART_COUNT) { "Invalid external handoff result record" }
        return ArciExternalHandoffResult(
            requestId = values[0],
            scope = ArciExternalHandoffScope(
                appId = values[1],
                interactionId = values[2],
                sessionKey = values[3],
                stepId = values[4],
                provider = values[5],
                resultBind = values[6],
            ),
            status = ArciExternalHandoffStatus.valueOf(values[7]),
            errorCode = values[8].ifBlank { null },
        )
    }

    private fun encode(value: String): String =
        Base64.encodeToString(
            value.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private fun decode(value: String): String =
        String(
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            StandardCharsets.UTF_8,
        )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val RESULT_PART_COUNT = 9
    }
}
