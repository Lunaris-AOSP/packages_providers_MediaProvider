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

package com.android.photopicker.features.categorygrid

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.filters.SdkSuppress
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.CategoryType
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Icon
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.paging.FakeInMemoryAlbumPagingSource
import com.android.photopicker.data.paging.FakeInMemoryCategoryPagingSource.Companion.TEST_ALBUM_NAME_PREFIX
import com.android.photopicker.extensions.navigateToCategoryGrid
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.features.categorygrid.data.CategoryDataService
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.MockContentProviderWrapper
import com.android.photopicker.util.test.whenever
import com.android.providers.media.flags.Flags
import com.google.common.truth.Truth.assertWithMessage
import dagger.Lazy
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.MockitoAnnotations
import src.com.android.photopicker.features.categorygrid.data.TestCategoryDataServiceImpl

@UninstallModules(
    ActivityModule::class,
    EmbeddedServiceModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class CategoryGridFeatureTest : PhotopickerFeatureBaseTest() {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()
    @get:Rule(order = 3) var setFlagsRule = SetFlagsRule()

    /* Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : PhotopickerTestModule()

    val testDispatcher = StandardTestDispatcher()

    /* Overrides for ActivityModule */
    val testScope: TestScope = TestScope(testDispatcher)
    @BindValue @Main val mainScope: CoroutineScope = testScope
    @BindValue @Background var testBackgroundScope: CoroutineScope = testScope.backgroundScope

    /* Overrides for ViewModelModule */
    @BindValue val viewModelScopeOverride: CoroutineScope? = testScope.backgroundScope

    /* Overrides for the ConcurrencyModule */
    @BindValue @Main val mainDispatcher: CoroutineDispatcher = testDispatcher
    @BindValue @Background val backgroundDispatcher: CoroutineDispatcher = testDispatcher

    /**
     * Preview uses Glide for loading images, so we have to mock out the dependencies for Glide
     * Replace the injected ContentResolver binding in [ApplicationModule] with this test value.
     */
    @BindValue @ApplicationOwned lateinit var contentResolver: ContentResolver
    private lateinit var provider: MockContentProviderWrapper
    @Mock lateinit var mockContentProvider: ContentProvider

    // Needed for UserMonitor
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    @Inject lateinit var mockContext: Context
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var events: Events
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>
    @Inject lateinit var dataService: DataService
    @Inject lateinit var categoryDataService: CategoryDataService

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        hiltRule.inject()

        // Stub for MockContentResolver constructor
        whenever(mockContext.getApplicationInfo()) { getTestableContext().getApplicationInfo() }

        // Stub out the content resolver for Glide
        val mockContentResolver = MockContentResolver(mockContext)
        provider = MockContentProviderWrapper(mockContentProvider)
        mockContentResolver.addProvider(MockContentProviderWrapper.AUTHORITY, provider)
        contentResolver = mockContentResolver

        // Return a resource png so that glide actually has something to load
        whenever(mockContentProvider.openTypedAssetFile(any(), any(), any(), any())) {
            getTestableContext().getResources().openRawResourceFd(R.drawable.android)
        }
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testCategoryGridIsEnabledWhenSearchFlagOn() {
        assertWithMessage("CategoryGridFeature is not enabled for TEST_ACTION")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("CategoryGridFeature is not enabled")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("CategoryGridFeature is not enabled")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(Intent.ACTION_GET_CONTENT)
                        intent(Intent(Intent.ACTION_GET_CONTENT))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("AlbumGridFeature is not enabled")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                        intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                        callingPackage("com.example.test")
                        callingPackageUid(1234)
                        callingPackageLabel("test_app")
                    }
                )
            )
            .isEqualTo(true)
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testCategoryGridIsDisabledWhenSearchFlagOff() {
        assertWithMessage("CategoryGridFeature is enabled for TEST_ACTION")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage("CategoryGridFeature is enabled")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage("CategoryGridFeature is enabled")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(Intent.ACTION_GET_CONTENT)
                        intent(Intent(Intent.ACTION_GET_CONTENT))
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage("AlbumGridFeature is enabled")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                        intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                        callingPackage("com.example.test")
                        callingPackageUid(1234)
                        callingPackageLabel("test_app")
                    }
                )
            )
            .isEqualTo(false)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testNavigateCategoryGridAndAlbumsAreVisible() =
        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            // In the [FakeInMemoryPagingSource] the albums are names using TEST_ALBUM_NAME_PREFIX
            // appended by a count in their sequence. Verify that an album with the name exists
            composeTestRule
                .onNode(hasText(TEST_ALBUM_NAME_PREFIX + "1"))
                .assert(hasClickAction())
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testConsistentCategoryFocus() =
        testScope.runTest {
            val dataList =
                buildList<Group.Category> {
                    for (i in 1..3) {
                        add(
                            Group.Category(
                                id = "$i",
                                pickerId = i.toLong(),
                                authority = "a",
                                displayName =
                                    FakeInMemoryAlbumPagingSource.Companion.TEST_ALBUM_NAME_PREFIX +
                                        "$i",
                                categoryType = CategoryType.PEOPLE_AND_PETS,
                                icons = emptyList(),
                                isLeafCategory = true,
                            )
                        )
                    }
                }

            val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
            checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }
            testCategoryDataService.categoryAlbumList = dataList

            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // wait for the composition to finish
            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            composeTestRule.waitForIdle()

            // wait for the album grid to show up
            advanceTimeBy(100)

            val allAlbumNodes =
                composeTestRule.onAllNodes(
                    hasText(
                        text = FakeInMemoryAlbumPagingSource.Companion.TEST_ALBUM_NAME_PREFIX,
                        substring = true,
                    )
                )

            allAlbumNodes[0].assert(hasClickAction()).assertIsDisplayed().performClick()

            assertWithMessage("Expected route to be media set grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_GRID.route)

            composeTestRule.waitForIdle()

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // wait for the album grid to show up
            advanceTimeBy(150)

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                try {
                    composeTestRule
                        .onNode(
                            hasText(
                                FakeInMemoryAlbumPagingSource.Companion.TEST_ALBUM_NAME_PREFIX +
                                    "1",
                                substring = true,
                            )
                        )
                        .assertExists()
                        .assertIsFocused()
                    true // Condition met
                } catch (e: AssertionError) {
                    false // Condition not yet met
                }
            }

            allAlbumNodes[0].assertIsFocused()
            allAlbumNodes[1].assertIsNotFocused()
            allAlbumNodes[2].assertIsNotFocused()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testAlbumsCanBeSelected() =
        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            val testAlbumDisplayName = TEST_ALBUM_NAME_PREFIX + "1"
            // In the [FakeInMemoryPagingSource] the albums are names using TEST_ALBUM_NAME_PREFIX
            // appended by a count in their sequence. Verify that an album with the name exists
            composeTestRule.onNode(hasText(testAlbumDisplayName)).assertIsDisplayed()

            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected route to be album media grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_MEDIA_GRID.route)
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSwipeLeftToNavigateToPhotoGrid() =
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            composeTestRule.onNode(hasText(TEST_ALBUM_NAME_PREFIX + "1")).performTouchInput {
                swipeRight()
            }
            composeTestRule.waitForIdle()

            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to navigate to Photogrid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testAlbumMediaShowsEmptyStateWhenEmpty() {

        val dataService = dataService as? TestDataServiceImpl
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }
        checkNotNull(dataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        dataService.albumMediaSetSize = 0

        val resources = getTestableContext().getResources()

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            val testAlbumDisplayName = TEST_ALBUM_NAME_PREFIX + "1"
            // In the [FakeInMemoryPagingSource] the albums are names using TEST_ALBUM_NAME_PREFIX
            // appended by a count in their sequence. Verify that an album with the name exists
            composeTestRule.onNode(hasText(testAlbumDisplayName)).assertIsDisplayed()
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForFavorites() {

        val testDataService = dataService as? TestDataServiceImpl
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0
        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Album(
                    id = ALBUM_ID_FAVORITES,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Favorites",
                    coverUri =
                        Uri.EMPTY.buildUpon()
                            .apply {
                                scheme("content")
                                authority("a")
                                path("1234")
                            }
                            .build(),
                    dateTakenMillisLong = 12345678L,
                    coverMediaSource = MediaSource.LOCAL,
                )
            )

        val resources = getTestableContext().getResources()

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val testAlbumDisplayName = "Favorites"
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_favorites_empty_state_title))
                )
                .assertIsDisplayed()

            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_favorites_empty_state_body))
                )
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForVideos() {

        val testDataService = dataService as? TestDataServiceImpl
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0
        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Album(
                    id = ALBUM_ID_VIDEOS,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Videos",
                    coverUri =
                        Uri.EMPTY.buildUpon()
                            .apply {
                                scheme("content")
                                authority("a")
                                path("1234")
                            }
                            .build(),
                    dateTakenMillisLong = 12345678L,
                    coverMediaSource = MediaSource.LOCAL,
                )
            )

        val resources = getTestableContext().getResources()

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            val testAlbumDisplayName = "Videos"
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_videos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_videos_empty_state_body)))
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForCamera() {
        val testDataService = dataService as? TestDataServiceImpl
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0
        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Album(
                    id = ALBUM_ID_CAMERA,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Camera",
                    coverUri =
                        Uri.EMPTY.buildUpon()
                            .apply {
                                scheme("content")
                                authority("a")
                                path("1234")
                            }
                            .build(),
                    dateTakenMillisLong = 12345678L,
                    coverMediaSource = MediaSource.LOCAL,
                )
            )

        val resources = getTestableContext().getResources()

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            val testAlbumDisplayName = "Camera"
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_camera_empty_state_body)))
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testMediaSetCanBeSelected() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        val testCategoryDisplayName = "People & Pets"
        val testMediaSetname = "mediaset"

        testCategoryDataService.mediaSetContentSize = 0
        // Force the data service to return no data for all test sources during this test.
        testCategoryDataService.mediaSetList =
            listOf(
                Group.MediaSet(
                    id = testMediaSetname,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testMediaSetname,
                    icon = Icon(Uri.parse(""), MediaSource.LOCAL),
                )
            )

        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Category(
                    id = testCategoryDisplayName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testCategoryDisplayName,
                    categoryType = CategoryType.PEOPLE_AND_PETS,
                    icons = emptyList(),
                    isLeafCategory = true,
                )
            )

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            composeTestRule.onNode(hasText(testCategoryDisplayName)).performClick()

            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            assertWithMessage("Expected route to be media set grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_GRID.route)

            composeTestRule.onNode(hasText(testMediaSetname)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            assertWithMessage("Expected route to be mediasetcontentgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_CONTENT_GRID.route)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForMediaSet() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        val testCategoryDisplayName = "People & Pets"
        val testMediaSetname = "mediaset"

        val resources = getTestableContext().getResources()

        testCategoryDataService.mediaSetContentSize = 0
        // Force the data service to return no data for all test sources during this test.
        testCategoryDataService.mediaSetList =
            listOf(
                Group.MediaSet(
                    id = testMediaSetname,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testMediaSetname,
                    icon = Icon(Uri.parse(""), MediaSource.LOCAL),
                )
            )

        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Category(
                    id = testCategoryDisplayName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testCategoryDisplayName,
                    categoryType = CategoryType.PEOPLE_AND_PETS,
                    icons = emptyList(),
                    isLeafCategory = true,
                )
            )

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            composeTestRule.onNode(hasText(testCategoryDisplayName)).performClick()

            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            assertWithMessage("Expected route to be media set grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_GRID.route)

            composeTestRule.onNode(hasText(testMediaSetname)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForPeoplePetsCategory() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        val testCategoryDisplayName = "People & Pets"

        val resources = getTestableContext().getResources()

        testCategoryDataService.mediaSetSize = 0
        // Force the data service to return no data for all test sources during this test.
        testCategoryDataService.mediaSetList = emptyList()

        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Category(
                    id = testCategoryDisplayName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testCategoryDisplayName,
                    categoryType = CategoryType.PEOPLE_AND_PETS,
                    icons = emptyList(),
                    isLeafCategory = true,
                )
            )

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            composeTestRule.onNode(hasText(testCategoryDisplayName)).performClick()

            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            assertWithMessage("Expected route to be media set grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_GRID.route)

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_people_category_empty_state_title)
                    )
                )
                .assertIsDisplayed()

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_people_category_empty_state_body)
                    )
                )
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForOtherCategory() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        val testCategoryDisplayName = "Other Categoreis"

        val resources = getTestableContext().getResources()

        testCategoryDataService.mediaSetSize = 0
        // Force the data service to return no data for all test sources during this test.
        testCategoryDataService.mediaSetList = emptyList()

        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Category(
                    id = testCategoryDisplayName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testCategoryDisplayName,
                    categoryType = CategoryType.USER_ABLUMS,
                    icons = emptyList(),
                    isLeafCategory = true,
                )
            )

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            composeTestRule.onNode(hasText(testCategoryDisplayName)).performClick()

            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            assertWithMessage("Expected route to be media set grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_GRID.route)

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
        }
    }
}
