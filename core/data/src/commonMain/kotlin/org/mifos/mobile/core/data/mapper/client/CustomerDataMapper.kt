/*
 * Copyright 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * See https://github.com/openMF/mobile-mobile/blob/master/LICENSE.md
 */
package org.mifos.mobile.core.data.mapper.client

import org.mifos.mobile.core.datastore.model.CustomerData
import org.mifos.mobile.core.model.entity.client.Client

fun Client.toCustomerData(): CustomerData =
    CustomerData(
        clientId = id.toLong(),
        firstName = firstname.orEmpty(),
        middleName = middlename,
        lastName = lastname.orEmpty(),
        phone = mobileNo,
        pinfl = pinfl,
        birthDateIso = dobDate.takeIf { it.size >= 3 }?.let { date ->
            "%04d-%02d-%02d".format(date[0], date[1], date[2])
        },
    )
