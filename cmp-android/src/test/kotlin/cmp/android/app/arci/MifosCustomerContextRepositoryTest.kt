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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mifos.mobile.core.common.DataState
import org.mifos.mobile.core.data.repository.HomeRepository
import org.mifos.mobile.core.datastore.UserPreferencesRepository
import org.mifos.mobile.core.datastore.model.CustomerData
import org.mifos.mobile.core.model.entity.client.Client
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class MifosCustomerContextRepositoryTest {

    private val preferences = mock(UserPreferencesRepository::class.java)
    private val homeRepository = mock(HomeRepository::class.java)

    @Test
    fun `cached customer is returned without a product-launch network request`() = runBlocking {
        val cached = customer(clientId = 5)
        `when`(preferences.customerInfo).thenReturn(MutableStateFlow(cached))

        val actual = repository().current(5)

        assertEquals(cached, actual)
        verifyNoInteractions(homeRepository)
        verify(preferences, never()).updateCustomer(cached)
        Unit
    }

    @Test
    fun `missing cache is filled from the bounded core-banking response`() = runBlocking {
        val client = Client(id = 5, firstname = "Maria", lastname = "Santos", pinfl = "30101990123456")
        `when`(preferences.customerInfo).thenReturn(MutableStateFlow(CustomerData.EMPTY))
        `when`(homeRepository.currentClient(5)).thenReturn(
            flowOf(DataState.Loading, DataState.Success(client)),
        )

        val actual = repository().current(5)

        assertEquals("Maria", actual.firstName)
        assertEquals("30101990123456", actual.pinfl)
        verify(preferences).updateCustomer(actual)
        Unit
    }

    @Test
    fun `a different client snapshot is never reused`() = runBlocking {
        `when`(preferences.customerInfo).thenReturn(MutableStateFlow(customer(clientId = 7)))
        `when`(homeRepository.currentClient(5)).thenReturn(
            flowOf(DataState.Success(Client(id = 5, firstname = "Maria", lastname = "Santos"))),
        )

        assertEquals(5, repository().current(5).clientId)
        verify(homeRepository).currentClient(5)
        Unit
    }

    @Test
    fun `loading-only refresh fails instead of spinning indefinitely`() {
        `when`(preferences.customerInfo).thenReturn(MutableStateFlow(CustomerData.EMPTY))
        `when`(homeRepository.currentClient(5)).thenReturn(
            flow {
                emit(DataState.Loading)
                awaitCancellation()
            },
        )

        assertThrows(ArciHostSessionUnavailableException::class.java) {
            runBlocking { repository(timeoutMs = 25).current(5) }
        }
    }

    private fun repository(timeoutMs: Long = 5_000) =
        MifosCustomerContextRepository(
            preferences = preferences,
            homeRepository = homeRepository,
            initialRefreshTimeoutMs = timeoutMs,
        )

    private fun customer(clientId: Long) =
        CustomerData(
            clientId = clientId,
            firstName = "Maria",
            lastName = "Santos",
        )
}
