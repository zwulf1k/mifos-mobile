/*
 * Copyright 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * See https://github.com/openMF/mobile-mobile/blob/master/LICENSE.md
 */
package cmp.android.app

import android.app.Application
import cmp.android.app.arci.mifosLaunchStringProvider
import cmp.shared.utils.initKoin
import io.arci.sdk.ArciHostConfiguration
import io.arci.sdk.ArciSdk
import io.arci.sdk.endpoint.ArciEndpoint
import io.arci.sdk.endpoint.ArciEndpointResolver
import io.arci.sdk.launch.ArciLaunchRequest
import io.arci.sdk.launch.YamlArciProcessRegistry
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.logger.Level

/**
 * Android application class.
 * This class is used to initialize Koin modules for dependency injection in the Android application.
 * It sets up the Koin framework, providing the necessary dependencies for the app.
 *
 * @constructor Create empty Android app
 * @see Application
 */
class AndroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@AndroidApp) // Provides the Android app context
            androidLogger(Level.DEBUG) // Enables Koin's logging for debugging
        }

        // Configure the Arci SDK once. 10.0.2.2:9900 is the ARCI BFF reachable
        // from the Android emulator (cleartext; see usesCleartextTraffic in the manifest).
        ArciSdk.configure(
            ArciHostConfiguration(
                endpointResolver = object : ArciEndpointResolver {
                    // Multi-BFF routing: each ARCI product app has its own backend/port.
                    // credit_lead_intake → :9900, mortgage → :9901. (Forerunner of a real
                    // ArciProcessRegistry / dynamic product resolution.)
                    override suspend fun resolve(request: ArciLaunchRequest): ArciEndpoint {
                        val port = when (request.target.appId) {
                            "mortgage" -> 9901
                            else -> 9900
                        }
                        return ArciEndpoint(
                            baseUrl = "http://10.0.2.2:$port",
                            callerId = "mifos-android",
                            renderChannel = "android",
                        )
                    }
                },
                httpClientProvider = { OkHttpClient() },
                themeProvider = null,
                // Config/server-driven launch registry (Arci.Mobile#43): the host holds NO
                // interactionId/card-form routing — CreditsSectionRoot renders entries and opens
                // them purely from this registry. Bundled launch-registry.yaml is the release
                // seed (bootstraps first paint); wiring a DB-backed CompositeArciProcessRegistry
                // server source is a follow-up once the СУП read-model endpoint exists.
                processRegistry = YamlArciProcessRegistry.fromAsset(this@AndroidApp),
                stringProvider = mifosLaunchStringProvider(this@AndroidApp),
            ),
        )
    }
}
