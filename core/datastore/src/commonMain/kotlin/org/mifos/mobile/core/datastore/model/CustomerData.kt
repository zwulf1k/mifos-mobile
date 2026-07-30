/*
 * Copyright 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * See https://github.com/openMF/mobile-mobile/blob/master/LICENSE.md
 */
package org.mifos.mobile.core.datastore.model

import kotlinx.serialization.Serializable

/**
 * Persisted authenticated-customer snapshot.
 *
 * Authentication credentials remain in [UserData]. This model is the separate customer-data
 * boundary consumed by product hosts, so opening a product does not depend on a fresh Home API
 * request. [clientId] keeps snapshots isolated when a different customer signs in.
 */
@Serializable
data class CustomerData(
    val clientId: Long,
    val firstName: String,
    val middleName: String? = null,
    val lastName: String,
    val phone: String? = null,
    val pinfl: String? = null,
    val birthDateIso: String? = null,
) {
    val hasVerifiedIdentity: Boolean
        get() = clientId > 0 && firstName.isNotBlank() && lastName.isNotBlank()

    companion object {
        val EMPTY = CustomerData(
            clientId = -1,
            firstName = "",
            lastName = "",
        )
    }
}
