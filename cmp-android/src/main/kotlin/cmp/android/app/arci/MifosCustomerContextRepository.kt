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

import io.arci.sdk.session.ArciHostSessionUnavailableException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.mifos.mobile.core.common.DataState
import org.mifos.mobile.core.data.mapper.client.toCustomerData
import org.mifos.mobile.core.data.repository.HomeRepository
import org.mifos.mobile.core.datastore.UserPreferencesRepository
import org.mifos.mobile.core.datastore.model.CustomerData

/**
 * Single customer-data boundary for embedded product SDKs.
 *
 * A persisted, client-isolated snapshot is authoritative for launch. The core-banking API is used
 * only as a bounded cache fill when a customer has no snapshot yet; it can never leave product UI
 * spinning indefinitely.
 */
class MifosCustomerContextRepository(
    private val preferences: UserPreferencesRepository,
    private val homeRepository: HomeRepository,
    private val initialRefreshTimeoutMs: Long = 5_000,
) {
    suspend fun current(clientId: Long): CustomerData {
        val cached = preferences.customerInfo.value.takeIf {
            it.clientId == clientId && it.hasVerifiedIdentity
        }
        if (cached != null) return cached

        val refreshed = withTimeoutOrNull(initialRefreshTimeoutMs) {
            homeRepository.currentClient(clientId).first { it !is DataState.Loading }
        }
        val customer = when (refreshed) {
            is DataState.Success -> refreshed.data.toCustomerData()
            is DataState.Error -> throw ArciHostSessionUnavailableException(
                "Mifos customer context is unavailable",
                refreshed.exception,
            )
            DataState.Loading, null -> throw ArciHostSessionUnavailableException(
                "Mifos customer context refresh timed out",
            )
        }
        if (!customer.hasVerifiedIdentity) {
            throw ArciHostSessionUnavailableException(
                "Mifos customer context has no verified identity",
            )
        }
        preferences.updateCustomer(customer)
        return customer
    }
}
