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

package com.android.photopicker.inject

import android.content.Context
import android.os.UserHandle
import com.android.photopicker.core.Background
import com.android.photopicker.core.Main
import com.android.photopicker.core.banners.BannerManager
import com.android.photopicker.core.banners.BannerManagerImpl
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.DeviceConfigProxy
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.core.database.DatabaseManager
import com.android.photopicker.core.database.DatabaseManagerTestImpl
import com.android.photopicker.core.embedded.EmbeddedLifecycle
import com.android.photopicker.core.embedded.EmbeddedViewModelFactory
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.selection.GrantsAwareSelectionImpl
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.selection.SelectionImpl
import com.android.photopicker.core.selection.SelectionStrategy
import com.android.photopicker.core.selection.SelectionStrategy.Companion.determineSelectionStrategy
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.PrefetchDataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.categorygrid.data.CategoryDataService
import com.android.photopicker.features.search.data.SearchDataService
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.migration.DisableInstallInCheck
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito.mock

/**
 * A basic Hilt test module that resolves common injected dependencies. Tests can install extend and
 * install this module to customize dependencies on a per test suite basis and avoid a lot of DI
 * setup of classes that may not be used in the test cases.
 *
 * For the purposes of the test case, all of these values are provided as [@Singleton] to ensure
 * that each injector receives the same value.
 *
 * This module does not provide overrides for CoroutineScope bindings, as those should be provided
 * by the test class directly via [@BindValue] and [TestScope]
 *
 * Note: This module is not actually Installed into the Hilt graph. @DisableInstallInCheck prevents
 * this module from being installed, each test will extend & install this module independently.
 */
@Module
@DisableInstallInCheck
abstract class PhotopickerTestModule(val options: TestOptions = TestOptions.Builder().build()) {

    @Singleton
    @Provides
    fun provideTestMockContext(): Context {
        // Always provide a Mocked Context object to injected dependencies, to ensure that the
        // device state never leaks into the test environment. Feature tests can obtain this mock
        // by injecting a context object.
        return mock(Context::class.java)
    }

    @Singleton
    @Provides
    fun provideEmbeddedLifecycle(
        viewModelFactory: EmbeddedViewModelFactory,
        @Main dispatcher: CoroutineDispatcher,
    ): EmbeddedLifecycle {
        // Force Lifecycle to be created on the MainDispatcher
        val embeddedLifecycle = runBlocking(dispatcher) { EmbeddedLifecycle(viewModelFactory) }
        return embeddedLifecycle
    }

    @Singleton
    @Provides
    fun provideViewModelFactory(
        @Background backgroundDispatcher: CoroutineDispatcher,
        featureManager: Lazy<FeatureManager>,
        configurationManager: Lazy<ConfigurationManager>,
        bannerManager: Lazy<BannerManager>,
        selection: Lazy<Selection<Media>>,
        userMonitor: Lazy<UserMonitor>,
        dataService: Lazy<DataService>,
        searchDataService: Lazy<SearchDataService>,
        categoryDataService: Lazy<CategoryDataService>,
        events: Lazy<Events>,
    ): EmbeddedViewModelFactory {
        val embeddedViewModelFactory =
            EmbeddedViewModelFactory(
                backgroundDispatcher,
                configurationManager,
                bannerManager,
                dataService,
                searchDataService,
                categoryDataService,
                events,
                featureManager,
                selection,
                userMonitor,
            )
        return embeddedViewModelFactory
    }

    @Singleton
    @Provides
    fun provideBannerManager(
        @Background backgroundScope: CoroutineScope,
        @Background backgroundDispatcher: CoroutineDispatcher,
        configurationManager: ConfigurationManager,
        databaseManager: DatabaseManager,
        featureManager: FeatureManager,
        dataService: DataService,
        userMonitor: UserMonitor,
        processOwnerHandle: UserHandle,
    ): BannerManager {
        return BannerManagerImpl(
            backgroundScope,
            backgroundDispatcher,
            configurationManager,
            databaseManager,
            featureManager,
            dataService,
            userMonitor,
            processOwnerHandle,
        )
    }

    @Singleton
    @Provides
    fun createConfigurationManager(
        @Background scope: CoroutineScope,
        @Background dispatcher: CoroutineDispatcher,
        deviceConfigProxy: DeviceConfigProxy,
    ): ConfigurationManager {

        return ConfigurationManager(
            options.runtimeEnv,
            scope,
            dispatcher,
            deviceConfigProxy,
            generatePickerSessionId(),
        )
    }

    @Singleton
    @Provides
    fun provideDatabaseManager(): DatabaseManager {
        return DatabaseManagerTestImpl()
    }

    /** Use a test DeviceConfigProxy to isolate device state */
    @Singleton
    @Provides
    fun createDeviceConfigProxy(): DeviceConfigProxy {
        return TestDeviceConfigProxyImpl()
    }

    @Singleton
    @Provides
    fun createUserHandle(): UserHandle {
        return options.processOwnerHandle
    }

    @Singleton
    @Provides
    fun createUserMonitor(
        context: Context,
        configurationManager: ConfigurationManager,
        @Background scope: CoroutineScope,
        @Background dispatcher: CoroutineDispatcher,
        userHandle: UserHandle,
    ): UserMonitor {
        return UserMonitor(
            context,
            configurationManager.configuration,
            scope,
            dispatcher,
            userHandle,
        )
    }

    @Singleton
    @Provides
    fun createDataService(): DataService {
        return TestDataServiceImpl()
    }

    @Singleton
    @Provides
    fun createEvents(
        @Background scope: CoroutineScope,
        configurationManager: ConfigurationManager,
        featureManager: FeatureManager,
    ): Events {
        return Events(scope = scope, configurationManager.configuration, featureManager)
    }

    @Singleton
    @Provides
    fun createFeatureManager(
        @Background scope: CoroutineScope,
        configurationManager: ConfigurationManager,
        prefetchDataService: PrefetchDataService,
    ): FeatureManager {
        return FeatureManager(
            configuration = configurationManager.configuration,
            scope = scope,
            prefetchDataService = prefetchDataService,
            registeredFeatures = options.registeredFeatures,
            dispatcher = Dispatchers.Main.immediate,
        )
    }

    @Singleton
    @Provides
    fun createMediaProviderClient(): MediaProviderClient {
        return MediaProviderClient()
    }

    @Singleton
    @Provides
    fun createPrefetchDataService(): PrefetchDataService {
        return TestPrefetchDataService()
    }

    @Singleton
    @Provides
    fun createSelection(
        @Background scope: CoroutineScope,
        configurationManager: ConfigurationManager,
    ): Selection<Media> {
        return when (determineSelectionStrategy(configurationManager.configuration.value)) {
            SelectionStrategy.GRANTS_AWARE_SELECTION ->
                GrantsAwareSelectionImpl(
                    scope = scope,
                    configuration = configurationManager.configuration,
                    preGrantedItemsCount = TestDataServiceImpl().preGrantedMediaCount,
                )
            SelectionStrategy.DEFAULT ->
                SelectionImpl(
                    scope = scope,
                    configuration = configurationManager.configuration,
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )
        }
    }
}
