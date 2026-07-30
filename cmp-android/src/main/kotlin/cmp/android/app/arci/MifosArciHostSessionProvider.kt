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
import org.mifos.mobile.core.datastore.UserPreferencesRepository

/**
 * DBO-to-Arci integration adapter.
 *
 * This reads authentication from the session store and client data from the repository layer. It
 * intentionally has no dependency on HomeViewModel, Compose state, or a particular product.
 */
class MifosArciHostSessionProvider(
    private val preferences: UserPreferencesRepository,
    private val customerContexts: MifosCustomerContextRepository,
) : ArciHostSessionProvider {

    override suspend fun currentSession(): ArciHostSession {
        val user = preferences.userInfo.value
        val clientId = preferences.clientId.value ?: user.clientId.takeIf { it > 0 }
        if (!user.isAuthenticated || clientId == null) {
            throw ArciHostSessionUnavailableException("Mifos client session is not authenticated")
        }

        val customer = customerContexts.current(clientId)

        return ArciHostSession(
            subjectId = user.userId.toString(),
            profile = ArciClientProfile(
                partyId = customer.clientId.toString(),
                firstName = customer.firstName,
                middleName = customer.middleName,
                lastName = customer.lastName,
                phone = customer.phone,
                pinfl = customer.pinfl ?: MifosMyIdTestControl.profilePinfl(),
                birthDateIso = customer.birthDateIso,
                source = ArciClientProfileSource.HOST_CORE_BANKING,
            ),
        )
    }
}
