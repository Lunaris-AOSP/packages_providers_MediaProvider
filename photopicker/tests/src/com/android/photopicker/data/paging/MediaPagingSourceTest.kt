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

package com.android.photopicker.features.data.paging

import android.content.ContentResolver
import android.content.Intent
import android.provider.MediaStore
import androidx.paging.PagingSource.LoadParams
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.TestMediaProvider
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.data.paging.MediaPagingSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MediaPagingSourceTest {
    private val testSessionId = generatePickerSessionId()
    private val testContentProvider: TestMediaProvider = TestMediaProvider()
    private val contentResolver: ContentResolver = ContentResolver.wrap(testContentProvider)
    private val availableProviders: List<Provider> =
        listOf(
            Provider(
                authority = "local_authority",
                mediaSource = MediaSource.LOCAL,
                uid = 1,
                displayName = "Local Provider",
            )
        )
    private val testPhotopickerConfiguration: PhotopickerConfiguration =
        PhotopickerConfiguration(
            action = MediaStore.ACTION_PICK_IMAGES,
            intent = Intent(MediaStore.ACTION_PICK_IMAGES),
            sessionId = testSessionId,
        )

    @Mock private lateinit var mockMediaProviderClient: MediaProviderClient

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testLoad() = runTest {
        val featureManager =
            FeatureManager(
                provideTestConfigurationFlow(this.backgroundScope, testPhotopickerConfiguration),
                this.backgroundScope,
                TestPrefetchDataService(),
                emptySet<FeatureRegistration>(),
            )
        val events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope, testPhotopickerConfiguration),
                featureManager,
            )

        val mediaPagingSource =
            MediaPagingSource(
                contentResolver = contentResolver,
                availableProviders = availableProviders,
                mediaProviderClient = mockMediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                testPhotopickerConfiguration,
                events,
            )

        val pageKey: MediaPageKey = MediaPageKey()
        val pageSize: Int = 10
        val params =
            LoadParams.Append<MediaPageKey>(
                key = pageKey,
                loadSize = pageSize,
                placeholdersEnabled = false,
            )

        backgroundScope.launch { mediaPagingSource.load(params) }
        advanceTimeBy(100)

        verify(mockMediaProviderClient, times(1))
            .fetchMedia(
                pageKey,
                pageSize,
                contentResolver,
                availableProviders,
                testPhotopickerConfiguration,
            )
    }
}
