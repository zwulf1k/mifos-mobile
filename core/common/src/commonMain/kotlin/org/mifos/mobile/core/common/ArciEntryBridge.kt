/*
 * Copyright 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * See https://github.com/openMF/mobile-mobile/blob/master/LICENSE.md
 */
package org.mifos.mobile.core.common

/**
 * Shared→platform bridge for launching the (Android-only) Arci "Кредиты/Ипотека" section from the
 * common Home UI. The Android host (MainActivity) assigns [open]; the "Loan Ipoteka" service tile
 * invokes it. Lives in core:common so both the shared feature code and the Android app can see it,
 * keeping the KMP-shared home free of any androidx.compose/Arci dependency.
 */
object ArciEntryBridge {
    var open: (() -> Unit)? = null
}
