/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.photopicker.features.categorygrid.inject

import android.util.Log
import com.android.photopicker.core.Background
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.events.Events
import com.android.photopicker.data.DataService
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.NotificationService
import com.android.photopicker.features.categorygrid.data.CategoryDataService
import com.android.photopicker.features.categorygrid.data.CategoryDataServiceImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Injection Module for category feature specific dependencies, that provides access to objects
 * bound to the ActivityRetainedScope.
 *
 * These can be injected by requesting the type with the [@ActivityRetainedScoped] qualifier.
 *
 * The module outlives the individual activities (and survives configuration changes), but is bound
 * to a single Photopicker session.
 *
 * Note: Jobs that are launched in the [CoroutineScope] provided by this module will be
 * automatically cancelled when the ActivityRetainedScope is ended.
 */
@Module
@InstallIn(ActivityRetainedComponent::class)
class CategoryActivityRetainedModule {
    companion object {
        val TAG: String = "CategoryActivityModule"
    }

    // Avoid initialization until it's actually needed.
    private lateinit var categoryDataService: CategoryDataService

    /** Provider for an implementation of [CategoryDataService]. */
    @Provides
    @ActivityRetainedScoped
    fun provideCategoryDataService(
        dataService: DataService,
        configurationManager: ConfigurationManager,
        @Background scope: CoroutineScope,
        @Background dispatcher: CoroutineDispatcher,
        mediaProviderClient: MediaProviderClient,
        notificationService: NotificationService,
        events: Events,
    ): CategoryDataService {
        if (::categoryDataService.isInitialized) {
            return categoryDataService
        } else {
            Log.d(
                CategoryDataService.TAG,
                "CategoryDataService requested but not yet initialized." +
                    " Initializing CategoryDataService.",
            )

            categoryDataService =
                CategoryDataServiceImpl(
                    dataService,
                    configurationManager.configuration,
                    scope,
                    dispatcher,
                    notificationService,
                    mediaProviderClient,
                    events,
                )
            return categoryDataService
        }
    }
}
