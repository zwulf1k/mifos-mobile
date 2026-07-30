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

import io.arci.sdk.session.ArciClientProfile
import io.arci.sdk.session.ArciClientProfileSource
import io.arci.sdk.session.ArciHostSession
import io.arci.sdk.session.ArciHostSessionProvider
import io.arci.sdk.session.ArciHostSessionUnavailableException
import kotlinx.coroutines.flow.first
import org.mifos.mobile.core.common.DataState
import org.mifos.mobile.core.data.repository.HomeRepository
import org.mifos.mobile.core.datastore.UserPreferencesRepository

/**
 * DBO-to-Arci integration adapter.
 *
 * This reads authentication from the session store and client data from the repository layer. It
 * intentionally has no dependency on HomeViewModel, Compose state, or a particular product.
 */
class MifosArciHostSessionProvider(
    private val preferences: UserPreferencesRepository,
    private val homeRepository: HomeRepository,
) : ArciHostSessionProvider {

    override suspend fun currentSession(): ArciHostSession {
        val user = preferences.userInfo.value
        val clientId = preferences.clientId.value ?: user.clientId.takeIf { it > 0 }
        if (!user.isAuthenticated || clientId == null) {
            throw ArciHostSessionUnavailableException("Mifos client session is not authenticated")
        }

        val clientState = homeRepository.currentClient(clientId).first { it !is DataState.Loading }
        val client = when (clientState) {
            is DataState.Success -> clientState.data
            is DataState.Error -> throw ArciHostSessionUnavailableException(
                "Mifos client profile is unavailable",
                clientState.exception,
            )
            DataState.Loading -> error("Loading state was filtered")
        }

        return ArciHostSession(
            subjectId = user.userId.toString(),
            profile = ArciClientProfile(
                partyId = client.id.toString(),
                firstName = client.firstname.orEmpty(),
                middleName = client.middlename,
                lastName = client.lastname.orEmpty(),
                phone = client.mobileNo,
                pinfl = client.pinfl ?: MifosMyIdTestControl.profilePinfl(),
                birthDateIso = client.dobDate.toIsoDateOrNull(),
                source = ArciClientProfileSource.HOST_CORE_BANKING,
            ),
        )
    }
}

private fun List<Int>.toIsoDateOrNull(): String? =
    takeIf { it.size >= 3 }?.let { date -> "%04d-%02d-%02d".format(date[0], date[1], date[2]) }
