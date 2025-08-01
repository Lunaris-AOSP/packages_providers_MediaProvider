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
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.paging.PagingSource.LoadResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.data.DEFAULT_PROVIDERS
import com.android.photopicker.data.DEFAULT_SEARCH_REQUEST_ID
import com.android.photopicker.data.DEFAULT_SEARCH_SUGGESTIONS
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.TestMediaProvider
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.GroupPageKey
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.search.model.SearchRequest
import com.android.photopicker.features.search.model.SearchSuggestion
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaProviderClientTest {
    private val testContentProvider: TestMediaProvider = TestMediaProvider()
    private val testContentResolver: ContentResolver = ContentResolver.wrap(testContentProvider)
    private val sessionId = generatePickerSessionId()

    @Test
    fun testFetchAvailableProviders() = runTest {
        val mediaProviderClient = MediaProviderClient()

        val availableProviders: List<Provider> =
            mediaProviderClient.fetchAvailableProviders(contentResolver = testContentResolver)

        assertThat(availableProviders.count()).isEqualTo(testContentProvider.providers.count())
        for (index in availableProviders.indices) {
            assertThat(availableProviders[index]).isEqualTo(testContentProvider.providers[index])
        }
    }

    @Test
    fun testFetchMediaPage() = runTest {
        val mediaProviderClient = MediaProviderClient()

        val mediaLoadResult: LoadResult<MediaPageKey, Media> =
            mediaProviderClient.fetchMedia(
                pageKey = MediaPageKey(),
                pageSize = 5,
                contentResolver = testContentResolver,
                availableProviders = listOf(Provider("provider", MediaSource.LOCAL, 0, "")),
                config =
                    PhotopickerConfiguration(
                        action = MediaStore.ACTION_PICK_IMAGES,
                        sessionId = sessionId,
                    ),
            )

        assertThat(mediaLoadResult is LoadResult.Page).isTrue()

        val media: List<Media> = (mediaLoadResult as LoadResult.Page).data

        assertThat(media.count()).isEqualTo(testContentProvider.media.count())
        for (index in media.indices) {
            assertThat(media[index]).isEqualTo(testContentProvider.media[index])
        }
    }

    @Test
    fun testFetchFilteredMediaPage() = runTest {
        val mediaProviderClient = MediaProviderClient()

        val mediaLoadResult: List<Media> =
            mediaProviderClient.fetchFilteredMedia(
                pageKey = MediaPageKey(),
                pageSize = 5,
                contentResolver = testContentResolver,
                availableProviders = listOf(Provider("provider", MediaSource.LOCAL, 0, "")),
                config =
                    PhotopickerConfiguration(
                        action = MediaStore.ACTION_PICK_IMAGES,
                        sessionId = sessionId,
                    ),
                // add a uri to preSelection
                arrayListOf(
                    Uri.parse(
                        "content://media/picker/0/com.android.providers.media.photopicker/media/" +
                            testContentProvider.media[1].mediaId
                    )
                ),
            )

        assertThat(mediaLoadResult).isNotNull()
        assertThat(mediaLoadResult.count()).isEqualTo(1)
        assertThat(mediaLoadResult.get(0).mediaId).isEqualTo(testContentProvider.media[1].mediaId)
    }

    @Test
    fun testRefreshCloudMedia() = runTest {
        testContentProvider.lastRefreshMediaRequest = null
        val mediaProviderClient = MediaProviderClient()
        val providers: List<Provider> =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                ),
                Provider(
                    authority = "cloud_authority",
                    mediaSource = MediaSource.REMOTE,
                    uid = 1,
                    displayName = "",
                ),
                Provider(
                    authority = "hypothetical_local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 2,
                    displayName = "",
                ),
            )
        val mimeTypes = arrayListOf("image/gif", "video/*")
        val config =
            PhotopickerConfiguration(
                action = MediaStore.ACTION_PICK_IMAGES,
                mimeTypes = mimeTypes,
                sessionId = sessionId,
            )

        mediaProviderClient.refreshMedia(
            providers = providers,
            resolver = testContentResolver,
            config = config,
        )

        assertThat(testContentProvider.lastRefreshMediaRequest).isNotNull()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getBoolean("is_local_only", true))
            .isFalse()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getStringArrayList("mime_types"))
            .isEqualTo(mimeTypes)
        assertThat(testContentProvider.lastRefreshMediaRequest?.getString("intent_action"))
            .isEqualTo(MediaStore.ACTION_PICK_IMAGES)
    }

    @Test
    fun testRefreshMediaForUserSelectAction() = runTest {
        testContentProvider.lastRefreshMediaRequest = null
        val mediaProviderClient = MediaProviderClient()
        val providers: List<Provider> =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "abc",
                ),
                Provider(
                    authority = "hypothetical_local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 2,
                    displayName = "xyz",
                ),
            )
        mediaProviderClient.refreshMedia(
            providers = providers,
            resolver = testContentResolver,
            config =
                TestPhotopickerConfiguration.build {
                    action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                    intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                    callingPackage("com.example.test")
                    callingPackageUid(1234)
                    callingPackageLabel("test_app")
                },
        )

        assertThat(testContentProvider.lastRefreshMediaRequest).isNotNull()
        // TODO(b/340246010): Currently, we trigger sync for all available providers. This is
        //  because UI is responsible for triggering syncs which is sometimes required to enable
        //  providers. This should be changed to triggering syncs for specific providers once the
        //  backend takes responsibility for the sync triggers.
        assertThat(testContentProvider.lastRefreshMediaRequest?.getBoolean("is_local_only", true))
            .isFalse()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getStringArrayList("mime_types"))
            .isEqualTo(
                TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                        intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                        callingPackage("com.example.test")
                        callingPackageUid(1234)
                        callingPackageLabel("test_app")
                    }
                    .mimeTypes
            )
        assertThat(testContentProvider.lastRefreshMediaRequest?.getString("intent_action"))
            .isEqualTo(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
        assertThat(testContentProvider.lastRefreshMediaRequest?.getInt(Intent.EXTRA_UID))
            .isEqualTo(
                TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                        intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                        callingPackage("com.example.test")
                        callingPackageUid(1234)
                        callingPackageLabel("test_app")
                    }
                    .callingPackageUid
            )
    }

    @Test
    fun testFetchMediaGrantsCount() = runTest {
        testContentProvider.lastRefreshMediaRequest = null
        val mediaProviderClient = MediaProviderClient()

        val countOfGrants =
            mediaProviderClient.fetchMediaGrantsCount(
                contentResolver = testContentResolver,
                callingPackageUid =
                    TestPhotopickerConfiguration.build {
                            action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                            intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                            callingPackage("com.example.test")
                            callingPackageUid(1234)
                            callingPackageLabel("test_app")
                        }
                        .callingPackageUid ?: -1,
            )

        assertThat(countOfGrants).isEqualTo(testContentProvider.TEST_GRANTS_COUNT)
    }

    @Test
    fun testRefreshLocalOnlyMedia() = runTest {
        testContentProvider.lastRefreshMediaRequest = null
        val mediaProviderClient = MediaProviderClient()
        val providers: List<Provider> =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                ),
                Provider(
                    authority = "hypothetical_local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 1,
                    displayName = "",
                ),
            )
        val mimeTypes = arrayListOf("image/gif", "video/*")
        val config =
            PhotopickerConfiguration(
                action = MediaStore.ACTION_PICK_IMAGES,
                mimeTypes = mimeTypes,
                sessionId = sessionId,
            )

        mediaProviderClient.refreshMedia(
            providers = providers,
            resolver = testContentResolver,
            config = config,
        )

        assertThat(testContentProvider.lastRefreshMediaRequest).isNotNull()

        // TODO(b/340246010): Currently, we trigger sync for all available providers. This is
        //  because UI is responsible for triggering syncs which is sometimes required to enable
        //  providers. This should be changed to triggering syncs for specific providers once the
        //  backend takes responsibility for the sync triggers.
        assertThat(testContentProvider.lastRefreshMediaRequest?.getBoolean("is_local_only", true))
            .isFalse()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getStringArrayList("mime_types"))
            .isEqualTo(mimeTypes)
        assertThat(testContentProvider.lastRefreshMediaRequest?.getString("intent_action"))
            .isEqualTo(MediaStore.ACTION_PICK_IMAGES)
    }

    @Test
    fun testRefreshAlbumMedia() = runTest {
        testContentProvider.lastRefreshMediaRequest = null
        val mediaProviderClient = MediaProviderClient()
        val albumId = "album_id"
        val albumAuthority = "album_authority"
        val providers: List<Provider> =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                ),
                Provider(
                    authority = "hypothetical_local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 1,
                    displayName = "",
                ),
            )
        val mimeTypes = arrayListOf("image/gif", "video/*")
        val config =
            PhotopickerConfiguration(
                action = MediaStore.ACTION_PICK_IMAGES,
                mimeTypes = mimeTypes,
                sessionId = sessionId,
            )

        mediaProviderClient.refreshAlbumMedia(
            albumId = albumId,
            albumAuthority = albumAuthority,
            providers = providers,
            resolver = testContentResolver,
            config = config,
        )

        assertThat(testContentProvider.lastRefreshMediaRequest).isNotNull()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getBoolean("is_local_only", false))
            .isTrue()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getString("album_id"))
            .isEqualTo(albumId)
        assertThat(testContentProvider.lastRefreshMediaRequest?.getString("album_authority"))
            .isEqualTo(albumAuthority)
        assertThat(testContentProvider.lastRefreshMediaRequest?.getStringArrayList("mime_types"))
            .isEqualTo(mimeTypes)
        assertThat(testContentProvider.lastRefreshMediaRequest?.getString("intent_action"))
            .isEqualTo(MediaStore.ACTION_PICK_IMAGES)
    }

    @Test
    fun testFetchAlbumPage() = runTest {
        val mediaProviderClient = MediaProviderClient()

        val albumLoadResult: LoadResult<MediaPageKey, Group.Album> =
            mediaProviderClient.fetchAlbums(
                pageKey = MediaPageKey(),
                pageSize = 5,
                contentResolver = testContentResolver,
                availableProviders = listOf(Provider("provider", MediaSource.LOCAL, 0, "")),
                config =
                    PhotopickerConfiguration(
                        action = MediaStore.ACTION_PICK_IMAGES,
                        sessionId = sessionId,
                    ),
            )

        assertThat(albumLoadResult is LoadResult.Page).isTrue()

        val albums: List<Group.Album> = (albumLoadResult as LoadResult.Page).data

        assertThat(albums.count()).isEqualTo(testContentProvider.albums.count())
        for (index in albums.indices) {
            assertThat(albums[index]).isEqualTo(testContentProvider.albums[index])
        }
    }

    @Test
    fun testFetchAlbumMediaPage() = runTest {
        val mediaProviderClient = MediaProviderClient()
        val albumId = testContentProvider.albumMedia.keys.elementAt(0)
        val albumAuthority = "authority"

        val mediaLoadResult: LoadResult<MediaPageKey, Media> =
            mediaProviderClient.fetchAlbumMedia(
                albumId = albumId,
                albumAuthority = albumAuthority,
                pageKey = MediaPageKey(),
                pageSize = 5,
                contentResolver = testContentResolver,
                availableProviders = listOf(Provider(albumAuthority, MediaSource.LOCAL, 0, "")),
                config =
                    PhotopickerConfiguration(
                        action = MediaStore.ACTION_PICK_IMAGES,
                        sessionId = sessionId,
                    ),
            )

        assertThat(mediaLoadResult is LoadResult.Page).isTrue()

        val albumMedia: List<Media> = (mediaLoadResult as LoadResult.Page).data

        val expectedAlbumMedia = testContentProvider.albumMedia.get(albumId) ?: emptyList()
        assertThat(albumMedia.count()).isEqualTo(expectedAlbumMedia.count())
        for (index in albumMedia.indices) {
            assertThat(albumMedia[index]).isEqualTo(expectedAlbumMedia[index])
        }
    }

    @Test
    fun testFetchSearchResultsPage() = runTest {
        val mediaProviderClient = MediaProviderClient()

        val mediaLoadResult: LoadResult<MediaPageKey, Media> =
            mediaProviderClient.fetchSearchResults(
                searchRequestId = 1,
                pageKey = MediaPageKey(),
                pageSize = 5,
                contentResolver = testContentResolver,
                availableProviders = listOf(Provider("provider", MediaSource.LOCAL, 0, "")),
                config =
                    PhotopickerConfiguration(
                        action = MediaStore.ACTION_PICK_IMAGES,
                        sessionId = sessionId,
                    ),
                cancellationSignal = null,
            )

        assertThat(mediaLoadResult is LoadResult.Page).isTrue()

        val media: List<Media> = (mediaLoadResult as LoadResult.Page).data

        assertThat(media.count()).isEqualTo(testContentProvider.media.count())
        for (index in media.indices) {
            assertThat(media[index]).isEqualTo(testContentProvider.media[index])
        }
    }

    @Test
    fun testCreateSearchRequest() = runTest {
        val mediaProviderClient = MediaProviderClient()
        val providers: List<Provider> =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                )
            )
        val config =
            PhotopickerConfiguration(action = MediaStore.ACTION_PICK_IMAGES, sessionId = sessionId)
        val searchRequest = SearchRequest.SearchTextRequest("search_text")

        val searchRequestId =
            mediaProviderClient.createSearchRequest(
                searchRequest = searchRequest,
                providers = providers,
                resolver = testContentResolver,
                config = config,
            )

        assertThat(searchRequestId).isEqualTo(DEFAULT_SEARCH_REQUEST_ID)
    }

    @Test
    fun testFetchSearchSuggestions() = runTest {
        val mediaProviderClient = MediaProviderClient()
        val cancellationSignal = CancellationSignal()

        val searchSuggestions: List<SearchSuggestion> =
            mediaProviderClient.fetchSearchSuggestions(
                resolver = testContentResolver,
                prefix = "",
                limit = 10,
                historyLimit = 3,
                availableProviders = listOf(),
                cancellationSignal = cancellationSignal,
            )

        assertThat(searchSuggestions.size).isEqualTo(DEFAULT_SEARCH_SUGGESTIONS.size)

        for (index in 0..<DEFAULT_SEARCH_SUGGESTIONS.size) {
            assertThat(searchSuggestions[index]).isEqualTo(DEFAULT_SEARCH_SUGGESTIONS[index])
        }
    }

    @Test
    fun testFetchSearchProvidersWithAvailableProvidersKnown() = runTest {
        val mediaProviderClient = MediaProviderClient()
        val localProvider =
            Provider(
                authority = "local_authority",
                mediaSource = MediaSource.LOCAL,
                uid = 0,
                displayName = "",
            )
        val cloudProvider =
            Provider(
                authority = "cloud_authority",
                mediaSource = MediaSource.REMOTE,
                uid = 0,
                displayName = "",
            )
        val testContentProvider: TestMediaProvider =
            TestMediaProvider(searchProviders = listOf(localProvider, cloudProvider))
        val testContentResolver: ContentResolver = ContentResolver.wrap(testContentProvider)

        val searchProviderAuthorities =
            mediaProviderClient.fetchSearchProviderAuthorities(
                resolver = testContentResolver,
                availableProviders = listOf(localProvider),
            )

        assertThat(searchProviderAuthorities).isEqualTo(listOf(localProvider.authority))
    }

    @Test
    fun testFetchSearchProviders() = runTest {
        val mediaProviderClient = MediaProviderClient()
        val testContentResolver: ContentResolver = ContentResolver.wrap(testContentProvider)
        val searchProviderAuthorities =
            mediaProviderClient.fetchSearchProviderAuthorities(resolver = testContentResolver)

        assertThat(searchProviderAuthorities)
            .isEqualTo(DEFAULT_PROVIDERS.map { it.authority }.toList())
    }

    @Test
    fun testFetchCategories() = runTest {
        val mediaProviderClient = MediaProviderClient()

        val categoriesLoadResult: LoadResult<GroupPageKey, Group> =
            mediaProviderClient.fetchCategoriesAndAlbums(
                pageKey = GroupPageKey(),
                pageSize = 5,
                contentResolver = testContentResolver,
                availableProviders = testContentProvider.providers,
                parentCategoryId = null,
                config =
                    PhotopickerConfiguration(
                        action = MediaStore.ACTION_PICK_IMAGES,
                        sessionId = sessionId,
                    ),
                CancellationSignal(),
            )

        assertThat(categoriesLoadResult is LoadResult.Page).isTrue()

        val categoriesAndAlbums: List<Group> = (categoriesLoadResult as LoadResult.Page).data

        val expectedCategoriesAndAlbums = testContentProvider.categoriesAndAlbums
        assertThat(categoriesAndAlbums.count()).isEqualTo(expectedCategoriesAndAlbums.count())
        for (index in expectedCategoriesAndAlbums.indices) {
            assertThat(categoriesAndAlbums[index]).isEqualTo(expectedCategoriesAndAlbums[index])
        }
    }

    @Test
    fun testFetchMediaSets() = runTest {
        val mediaProviderClient = MediaProviderClient()

        val mediaSetsLoadResult: LoadResult<GroupPageKey, Group.MediaSet> =
            mediaProviderClient.fetchMediaSets(
                pageKey = GroupPageKey(),
                pageSize = 5,
                contentResolver = testContentResolver,
                availableProviders = testContentProvider.providers,
                parentCategory = testContentProvider.parentCategory,
                config =
                    PhotopickerConfiguration(
                        action = MediaStore.ACTION_PICK_IMAGES,
                        sessionId = sessionId,
                    ),
                cancellationSignal = CancellationSignal(),
            )

        assertThat(mediaSetsLoadResult is LoadResult.Page).isTrue()

        val mediaSets: List<Group.MediaSet> = (mediaSetsLoadResult as LoadResult.Page).data

        val expectedMediaSets = testContentProvider.mediaSets
        assertThat(mediaSets.count()).isEqualTo(expectedMediaSets.count())
        for (index in expectedMediaSets.indices) {
            assertThat(mediaSets[index]).isEqualTo(expectedMediaSets[index])
        }
    }

    @Test
    fun testFetchMediaSetContents() = runTest {
        val mediaProviderClient = MediaProviderClient()

        val mediaSetContentsLoadResult: LoadResult<MediaPageKey, Media> =
            mediaProviderClient.fetchMediaSetContents(
                pageKey = MediaPageKey(),
                pageSize = 5,
                contentResolver = testContentResolver,
                parentMediaSet = testContentProvider.mediaSets[0],
                config =
                    PhotopickerConfiguration(
                        action = MediaStore.ACTION_PICK_IMAGES,
                        sessionId = sessionId,
                    ),
                cancellationSignal = CancellationSignal(),
            )

        assertThat(mediaSetContentsLoadResult is LoadResult.Page).isTrue()

        val media: List<Media> = (mediaSetContentsLoadResult as LoadResult.Page).data

        val expectedMedia = testContentProvider.media
        assertThat(media.count()).isEqualTo(expectedMedia.count())
        for (index in expectedMedia.indices) {
            assertThat(media[index]).isEqualTo(expectedMedia[index])
        }
    }
}
