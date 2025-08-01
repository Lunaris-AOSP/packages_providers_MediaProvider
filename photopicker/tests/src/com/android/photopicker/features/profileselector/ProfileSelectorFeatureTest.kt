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

package com.android.photopicker.features.profileselector

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.UserProperties
import android.content.pm.UserProperties.SHOW_IN_QUIET_MODE_HIDDEN
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import com.android.modules.utils.build.SdkLevel
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
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.mockSystemService
import com.android.photopicker.util.test.whenever
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
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    EmbeddedServiceModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class ProfileSelectorFeatureTest : PhotopickerFeatureBaseTest() {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()

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

    @Inject lateinit var events: Events
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var userHandle: UserHandle
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>

    @BindValue @ApplicationOwned val contentResolver: ContentResolver = MockContentResolver()

    // Needed for UserMonitor
    @Inject lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    private val USER_HANDLE_MANAGED: UserHandle
    private val USER_ID_MANAGED: Int = 10

    init {

        val parcel = Parcel.obtain()
        parcel.writeInt(USER_ID_MANAGED)
        parcel.setDataPosition(0)
        USER_HANDLE_MANAGED = UserHandle(parcel)
        parcel.recycle()
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        hiltRule.inject()
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)
    }

    @Test
    fun testProfileSelectorEnabledInConfigurations() {

        assertWithMessage("ProfileSelectorFeature is not always enabled (ACTION_PICK_IMAGES)")
            .that(
                ProfileSelectorFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("ProfileSelectorFeature is not always enabled (ACTION_GET_CONTENT)")
            .that(
                ProfileSelectorFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(Intent.ACTION_GET_CONTENT)
                        intent(Intent(Intent.ACTION_GET_CONTENT))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("ProfileSelectorFeature should not be enabled (USER_SELECT_FOR_APP)")
            .that(
                ProfileSelectorFeature.Registration.isEnabled(
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
    fun testProfileSelectorIsShownWithMultipleProfiles() =
        testScope.runTest {

            // Initial setup state: Two profiles (Personal/Work), both enabled
            whenever(mockUserManager.userProfiles) { listOf(userHandle, USER_HANDLE_MANAGED) }
            whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
            whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
            whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { userHandle }

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            composeTestRule
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_profile_primary_label)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    fun testProfileSelectorIsNotShownOnlyOneProfile() =
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            composeTestRule
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_profile_primary_label)
                    )
                )
                .assertIsNotDisplayed()
        }

    @Test
    fun testHideQuietModeProfilesWhenRequestedPostV() {
        testScope.runTest {
            assumeTrue(SdkLevel.isAtLeastV())
            val resources = getTestableContext().getResources()

            val otherUserId = 30
            val parcel = Parcel.obtain()
            parcel.writeInt(otherUserId)
            parcel.setDataPosition(0)
            val otherProfile = UserHandle(parcel)
            parcel.recycle()

            // Initial setup state: Three profiles (Personal/Work/Other), both enabled
            whenever(mockUserManager.userProfiles) {
                listOf(userHandle, USER_HANDLE_MANAGED, otherProfile)
            }
            whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
            whenever(mockUserManager.isManagedProfile(otherUserId)) { true }
            whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
            whenever(mockUserManager.isQuietModeEnabled(otherProfile)) { true }
            whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { userHandle }
            whenever(mockUserManager.getProfileParent(otherProfile)) { userHandle }
            whenever(mockUserManager.getUserProperties(otherProfile)) @JvmSerializableLambda {
                UserProperties.Builder().setShowInQuietMode(SHOW_IN_QUIET_MODE_HIDDEN).build()
            }
            //
            // Create mock user contexts for both profiles
            val mockPersonalContext = mock(Context::class.java)
            val mockManagedContext = mock(Context::class.java)
            val mockOtherProfileContext = mock(Context::class.java)

            // And mock user managers for each profile
            val personalProfileUserManager = mock(UserManager::class.java)
            val managedProfileUserManager = mock(UserManager::class.java)
            val otherProfileUserManager = mock(UserManager::class.java)
            mockSystemService(mockPersonalContext, UserManager::class.java) {
                personalProfileUserManager
            }
            mockSystemService(mockManagedContext, UserManager::class.java) {
                managedProfileUserManager
            }
            mockSystemService(mockOtherProfileContext, UserManager::class.java) {
                otherProfileUserManager
            }
            //
            // Mock the apis that return profile content, for each profile.
            whenever(personalProfileUserManager.getProfileLabel()) {
                resources.getString(R.string.photopicker_profile_primary_label)
            }
            whenever(personalProfileUserManager.getUserBadge()) {
                resources.getDrawable(R.drawable.android, /* theme= */ null)
            }
            whenever(managedProfileUserManager.getProfileLabel()) {
                resources.getString(R.string.photopicker_profile_managed_label)
            }
            whenever(managedProfileUserManager.getUserBadge()) {
                resources.getDrawable(R.drawable.android, /* theme= */ null)
            }
            whenever(otherProfileUserManager.getProfileLabel()) { "other profile" }
            whenever(otherProfileUserManager.getUserBadge()) {
                resources.getDrawable(R.drawable.android, /* theme= */ null)
            }

            // Mock the user contexts for each profile off the main test context.
            whenever(mockContext.createContextAsUser(userHandle, 0)) { mockPersonalContext }
            whenever(mockContext.createContextAsUser(USER_HANDLE_MANAGED, 0)) { mockManagedContext }
            whenever(mockContext.createContextAsUser(otherProfile, 0)) { mockOtherProfileContext }

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_profile_primary_label)
                    )
                )
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            // Ensure personal profile option exists
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_profile_primary_label)))
                .assert(hasClickAction())
                .assertIsDisplayed()

            // Ensure managed profile option exists
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_profile_managed_label)))
                .assert(hasClickAction())
                .assertIsDisplayed()

            // Ensure other profile option does NOT exist
            composeTestRule.onNode(hasText("other profile")).assertIsNotDisplayed()
        }
    }

    @Test
    fun testAvailableProfilesAreDisplayedPostV() =
        testScope.runTest {
            assumeTrue(SdkLevel.isAtLeastV())
            val resources = getTestableContext().getResources()

            // Initial setup state: Two profiles (Personal/Work), both enabled
            whenever(mockUserManager.userProfiles) { listOf(userHandle, USER_HANDLE_MANAGED) }
            whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
            whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
            whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { userHandle }

            // Create mock user contexts for both profiles
            val mockPersonalContext = mock(Context::class.java)
            val mockManagedContext = mock(Context::class.java)

            // And mock user managers for each profile
            val personalProfileUserManager = mock(UserManager::class.java)
            val managedProfileUserManager = mock(UserManager::class.java)
            mockSystemService(mockPersonalContext, UserManager::class.java) {
                personalProfileUserManager
            }
            mockSystemService(mockManagedContext, UserManager::class.java) {
                managedProfileUserManager
            }

            // Mock the apis that return profile content, for each profile.
            whenever(personalProfileUserManager.getProfileLabel()) {
                resources.getString(R.string.photopicker_profile_primary_label)
            }
            whenever(personalProfileUserManager.getUserBadge()) {
                resources.getDrawable(R.drawable.android, /* theme= */ null)
            }
            whenever(managedProfileUserManager.getProfileLabel()) {
                resources.getString(R.string.photopicker_profile_managed_label)
            }
            whenever(managedProfileUserManager.getUserBadge()) {
                resources.getDrawable(R.drawable.android, /* theme= */ null)
            }

            // Mock the user contexts for each profile off the main test context.
            whenever(mockContext.createContextAsUser(userHandle, 0)) { mockPersonalContext }
            whenever(mockContext.createContextAsUser(USER_HANDLE_MANAGED, 0)) { mockManagedContext }

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_profile_primary_label)
                    )
                )
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            // Ensure personal profile option exists
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_profile_primary_label)))
                .assert(hasClickAction())
                .assertIsDisplayed()

            // Ensure managed profile option exists
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_profile_managed_label)))
                .assert(hasClickAction())
                .assertIsDisplayed()
        }

    @Test
    fun testAvailableProfilesAreDisplayedPreV() =
        testScope.runTest {
            assumeFalse(SdkLevel.isAtLeastV())
            val resources = getTestableContext().getResources()

            // Initial setup state: Two profiles (Personal/Work), both enabled
            whenever(mockUserManager.userProfiles) { listOf(userHandle, USER_HANDLE_MANAGED) }
            whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
            whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
            whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { userHandle }

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_profile_primary_label)
                    )
                )
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            // Ensure personal profile option exists
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_profile_primary_label)))
                .assert(hasClickAction())
                .assertIsDisplayed()

            // Ensure managed profile option exists
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_profile_managed_label)))
                .assert(hasClickAction())
                .assertIsDisplayed()
        }
}
