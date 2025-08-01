/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.photopicker.core.user

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.UserProperties
import android.content.pm.UserProperties.SHOW_IN_QUIET_MODE_HIDDEN
import android.content.res.Resources
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.extensions.requireSystemService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Provides a long-living [StateFlow] that represents the current application's [UserStatus]. This
 * class also provides methods to switch the current active profile.
 *
 * This is provided as a part of Core and will be lazily initialized to prevent it from being
 * created before it is needed, but it will live as a singleton for the life of the activity once it
 * has been initialized.
 *
 * Will emit a value immediately of the current list available [UserProfile] as well as the current
 * Active profile. (Initialized as the Profile of the user that owns the process the [Activity] is
 * running in.)
 *
 * Additionally, this class registers a [BroadcastReceiver] on behalf of the activity to subscribe
 * to profile changes as they happen on the device, although those are subject to delivery delays
 * depending on how busy the device currently is (and if Photopicker is currently in the
 * foreground).
 *
 * @param context The context of the Application this UserMonitor is provided in.
 * @param configuration a [PhotopickerConfiguration] flow from the [ConfigurationManager]
 * @property scope The [CoroutineScope] that the BroadcastReceiver will listen in.
 * @property dispatcher [CoroutineDispatcher] scope that the BroadcastReceiver will listen in.
 * @property processOwnerUserHandle the user handle of the process that owns the Photopicker
 *   session.
 */
class UserMonitor(
    context: Context,
    private val configuration: StateFlow<PhotopickerConfiguration>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val processOwnerUserHandle: UserHandle,
) {

    companion object {
        const val TAG: String = "PhotopickerUserMonitor"
    }

    private val userManager: UserManager = context.requireSystemService()
    private val packageManager: PackageManager = context.packageManager

    /**
     * Internal state flow that the external flow is derived from. When making state changes, this
     * is the flow that should be updated.
     */
    private val _userStatus: MutableStateFlow<UserStatus> =
        MutableStateFlow(
            UserStatus(
                activeUserProfile = getUserProfileFromHandle(processOwnerUserHandle, context),
                allProfiles =
                    userManager.userProfiles
                        .filter {
                            // Filter out any profiles that should not be shown in sharing surfaces.
                            if (SdkLevel.isAtLeastV()) {
                                val properties = userManager.getUserProperties(it)
                                properties.getShowInSharingSurfaces() ==
                                    UserProperties.SHOW_IN_SHARING_SURFACES_SEPARATE
                            } else {
                                when {
                                    processOwnerUserHandle.identifier == it.identifier -> true
                                    // For SDK < V, accept all managed profiles, and the parent
                                    // of the current process owner. Ignore all others.
                                    userManager.isManagedProfile(it.identifier) -> true
                                    it.identifier ==
                                        userManager
                                            .getProfileParent(processOwnerUserHandle)
                                            ?.identifier -> true
                                    else -> false
                                }
                            }
                        }
                        .map { getUserProfileFromHandle(it, context) },
                activeContentResolver = getContentResolver(context, processOwnerUserHandle),
            )
        )

    /**
     * This flow exposes the current internal [UserStatus], and replays the most recent value for
     * new subscribers.
     */
    val userStatus: StateFlow<UserStatus> =
        _userStatus.stateIn(
            scope,
            SharingStarted.WhileSubscribed(),
            initialValue = _userStatus.value,
        )

    /** Setup a BroadcastReceiver to receive broadcasts for profile availability changes */
    private val profileChanges =
        callbackFlow<Pair<Intent, Context>> {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        Log.d(TAG, "Received profile changed: $intent")
                        trySend(Pair(intent, context))
                    }
                }
            val intentFilter = IntentFilter()

            // It's ok if these intents send duplicate broadcasts, the resulting state is only
            // updated & emitted if something actually changed. (Meaning duplicate broadcasts will
            // not cause subscribers to be notified, although there is a marginal cost to parse the
            // profile state again)
            intentFilter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            intentFilter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)

            if (SdkLevel.isAtLeastS()) {
                // On S+ devices use the broader profile listners to capture other types of
                // profiles.
                intentFilter.addAction(Intent.ACTION_PROFILE_ACCESSIBLE)
                intentFilter.addAction(Intent.ACTION_PROFILE_INACCESSIBLE)
            }

            /*
             TODO(b/303779617)
             This broadcast receiver should be launched in the parent profile of the user since
             child profiles do not receive these broadcasts.
            */
            if (SdkLevel.isAtLeastT()) {
                context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                // manually set the flags since [Context.RECEIVER_NOT_EXPORTED] doesn't exist pre
                // Sdk33
                context.registerReceiver(receiver, intentFilter, /* flags= */ 0x4)
            }

            awaitClose {
                Log.d(TAG, """BroadcastReceiver was closed, unregistering.""")
                context.unregisterReceiver(receiver)
            }
        }

    init {
        // Begin to collect emissions from the BroadcastReceiver. Started in the init block
        // to ensure only one collection is ever started. This collection is launched in the
        // injected scope with the injected dispatcher.
        scope.launch(dispatcher) {
            profileChanges.collect { (intent, context) ->
                handleProfileChangeBroadcast(intent, context)
            }
        }
    }

    /** The profile that the current Photopicker session is running under */
    val launchingProfile: UserProfile = getUserProfileFromHandle(processOwnerUserHandle, context)

    /**
     * Attempt to switch the Active [UserProfile] to a known profile that matches the passed
     * [UserProfile].
     *
     * This is not guaranteed to succeed. The target profile type may be disabled, not exist or
     * already be active. If the profile switch is successful, [UserMonitor] will emit a new
     * [UserStatus] with the updated state.
     *
     * @return The [SwitchProfileResult] of the requested change.
     */
    suspend fun requestSwitchActiveUserProfile(
        requested: UserProfile,
        context: Context,
    ): SwitchUserProfileResult {

        // Attempt to find the requested profile amongst the profiles known.
        val profile: UserProfile? =
            _userStatus.value.allProfiles.find { it.identifier == requested.identifier }

        profile?.let {

            // Only allow the switch if a profile is currently enabled.
            if (profile.enabled) {
                _userStatus.update {
                    it.copy(
                        activeUserProfile = profile,
                        activeContentResolver =
                            getContentResolver(context, UserHandle.of(profile.identifier)),
                    )
                }
                return SwitchUserProfileResult.SUCCESS
            }

            return SwitchUserProfileResult.FAILED_PROFILE_DISABLED
        }

        return SwitchUserProfileResult.FAILED_UNKNOWN_PROFILE
    }

    /**
     * Handler for the incoming BroadcastReceiver emissions representing a profile state change.
     *
     * This handler will check the currently known profiles in the current user state and emit an
     * updated user status value.
     */
    private suspend fun handleProfileChangeBroadcast(intent: Intent, context: Context) {

        val handle: UserHandle? = getUserHandleFromIntent(intent)

        handle?.let {
            Log.d(
                TAG,
                "Received a profile update for ${handle.getIdentifier()} from intent $intent",
            )

            // Assemble a new UserProfile from the updated UserHandle.
            val profile = getUserProfileFromHandle(handle, context)

            // Generate a new list of profiles to in preparation for an update.
            val newProfilesList: List<UserProfile> =
                listOf(
                    // Copy the current list but remove the matching profile
                    *_userStatus.value.allProfiles
                        .filterNot { it.identifier == profile.identifier }
                        .toTypedArray(),
                    // Replace the matching profile with the updated one.
                    profile,
                )

            // Check and see if the profile we just updated is still enabled, and if it is the
            // current active profile
            if (
                !profile.enabled &&
                    profile.identifier == _userStatus.value.activeUserProfile.identifier
            ) {
                Log.i(
                    TAG,
                    "The active profile is no longer enabled, transitioning back to the process owner's profile.",
                )

                // The current profile is disabled, we need to transition back to the process
                // owner's profile.
                val processOwnerProfile =
                    newProfilesList.find { it.identifier == processOwnerUserHandle.getIdentifier() }

                processOwnerProfile?.let {
                    // Update userStatus with the updated list of UserProfiles.
                    _userStatus.update {
                        it.copy(
                            activeUserProfile = processOwnerProfile,
                            allProfiles = newProfilesList,
                            activeContentResolver =
                                getContentResolver(context, processOwnerProfile.handle),
                        )
                    }
                }

                    // This is potentially a problematic state, the current profile is disabled,
                    // and attempting to find the process owner's profile was unsuccessful.
                    ?: run {
                        Log.w(
                            TAG,
                            "Could not find the process owner's profile to switch to when the active profile was disabled.",
                        )

                        // Still attempt to update the list of profiles.
                        _userStatus.update { it.copy(allProfiles = newProfilesList) }
                    }
            } else {

                // Update userStatus with the updated list of UserProfiles.
                _userStatus.update { it.copy(allProfiles = newProfilesList) }
            }
        }
            // If the incoming Intent does not include a UserHandle, there is nothing to update,
            // but Log a warning to help with debugging.
            ?: run {
                Log.w(
                    TAG,
                    "Received intent: $intent but could not find matching UserHandle. Ignoring.",
                )
            }
    }

    /**
     * Determines if the current handle supports CrossProfile content sharing.
     *
     * This method accepts a pair of user handles (from/to) and determines if CrossProfile access is
     * permitted between those two profiles.
     *
     * There are differences is on how the access is determined based on the platform SDK:
     * - For Platform SDK < V:
     *
     *   A check for CrossProfileIntentForwarders in the origin (from) profile that target the
     *   destination (to) profile. If such a forwarder exists, then access is allowed, and denied
     *   otherwise.
     * - For Platform SDK >= V:
     *
     *   The method now takes into account access delegation, which was first added in Android V.
     *
     *   For profiles that set the [CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT] property in
     *   its [UserProperties], its parent profile will be substituted in for its side of the check.
     *
     *   ex. For access checks between a Managed (from) and Private (to) profile, where:
     *     - Managed does not delegate to its parent
     *     - Private delegates to its parent
     *
     *   The following logic is performed: Managed -> parent(Private)
     *
     *   The same check in the other direction would yield: parent(Private) -> Managed
     *
     *   Note how the private profile is never actually used for either side of the check, since it
     *   is delegating its access check to the parent. And thus, if Managed can access the parent,
     *   it can also access the private.
     *
     * @param context Current context object, for switching user contexts.
     * @param fromUser The Origin profile, where the user is coming from
     * @param toUser The destination profile, where the user is attempting to go to.
     * @return Whether CrossProfile content sharing is supported in this handle.
     */
    private fun getIsCrossProfileAllowedForHandle(
        context: Context,
        fromUser: UserHandle,
        toUser: UserHandle,
    ): Boolean {

        /**
         * Determine if the provided [UserHandle] delegates its cross profile content sharing (both
         * to / from this profile) to its parent's access.
         *
         * @return True if the profile delegates to its parent, false otherwise.
         */
        fun profileDelegatesToParent(handle: UserHandle): Boolean {

            // Early exit, this check only exists on V+
            if (!SdkLevel.isAtLeastV()) {
                return false
            }

            val props = userManager.getUserProperties(handle)
            return props.getCrossProfileContentSharingStrategy() ==
                UserProperties.CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT
        }

        // Early exit conditions, accessing self.
        // NOTE: It is also possible to reach this state if this method is recursively checking
        // from: parent(A) to:parent(B) where A and B are both children of the same parent.
        if (fromUser.identifier == toUser.identifier) {
            return true
        }

        // Decide if we should use actual from or parent(from)
        val currentFromUser: UserHandle =
            if (profileDelegatesToParent(fromUser)) {
                userManager.getProfileParent(fromUser) ?: fromUser
            } else {
                fromUser
            }

        // Decide if we should use actual to or parent(to)
        val currentToUser: UserHandle =
            if (profileDelegatesToParent(toUser)) {
                userManager.getProfileParent(toUser) ?: toUser
            } else {
                toUser
            }

        // When the from/to has changed from the original parameters, recursively restart the checks
        // with the new from/to handles.
        if (
            fromUser.identifier != currentFromUser.identifier ||
                toUser.identifier != currentToUser.identifier
        ) {
            return getIsCrossProfileAllowedForHandle(context, currentFromUser, currentToUser)
        }

        // As a last resort, no applicable cross profile information found, so inspect the current
        // configuration and if there is an intent set, try to see
        // if there is a matching CrossProfileIntentForwarder
        return configuration.value.doesCrossProfileIntentForwarderExists(
            packageManager,
            fromUser,
            toUser,
        )
    }

    /**
     * Assemble a [UserProfile] from a provided [UserHandle]
     *
     * @return A UserProfile that corresponds to the UserHandle.
     */
    private fun getUserProfileFromHandle(handle: UserHandle, context: Context): UserProfile {

        val isParentProfile = userManager.getProfileParent(handle) == null
        val isManaged = userManager.isManagedProfile(handle.getIdentifier())
        val isQuietModeEnabled = userManager.isQuietModeEnabled(handle)
        var isCrossProfileSupported =
            getIsCrossProfileAllowedForHandle(context, processOwnerUserHandle, handle)

        val (icon, label) =
            try {

                val userContext = context.createContextAsUser(handle, /* flags= */ 0)
                val localUserManager: UserManager = userContext.requireSystemService()

                with(localUserManager) {
                    if (SdkLevel.isAtLeastV()) {
                        // Since these require an external call to generate, create them once
                        // and cache them in the profile that is getting passed to the UI to
                        // speed things up!
                        Pair(getUserBadge().toBitmap().asImageBitmap(), getProfileLabel())
                    } else {
                        // For Pre-V the UI will use pre-compiled resources and mappings to generate
                        // the icon.
                        Pair(null, null)
                    }
                }
            } catch (ex: Exception) {
                when (ex) {
                    is IllegalStateException -> {
                        Log.w(TAG, "IllegalState encountered while fetching icon and label.", ex)
                    }
                    is Resources.NotFoundException -> {
                        // If either resource is not defined by the system, fall back to the
                        // pre-compiled options to ensure that the UI doesn't end up in a weird
                        // state.
                        Log.w(TAG, "Expected profile resources could not be found", ex)
                    }
                    else -> {
                        Log.w(TAG, "Encountered exception during profile initialization: ", ex)
                    }
                }
                Pair(null, null)
            }

        return UserProfile(
            handle = handle,
            icon = icon,
            label = label,
            profileType =
                when {
                    // Profiles that do not have a parent are considered the primary profile
                    isParentProfile -> UserProfile.ProfileType.PRIMARY
                    isManaged -> UserProfile.ProfileType.MANAGED
                    else -> UserProfile.ProfileType.UNKNOWN
                },
            disabledReasons =
                when (handle) {
                    // The profile is never disabled if it is the current process' profile
                    processOwnerUserHandle -> emptySet()
                    else ->
                        buildSet {
                            if (isQuietModeEnabled) {
                                add(UserProfile.DisabledReason.QUIET_MODE)

                                // For V plus devices another check is required to see if the
                                // profile would like to be hidden when in quiet mode.
                                if (SdkLevel.isAtLeastV()) {
                                    val userProperties = userManager.getUserProperties(handle)
                                    if (
                                        userProperties.getShowInQuietMode() ==
                                            SHOW_IN_QUIET_MODE_HIDDEN
                                    ) {
                                        add(UserProfile.DisabledReason.QUIET_MODE_DO_NOT_SHOW)
                                    }
                                }
                            }
                            if (!isCrossProfileSupported)
                                add(UserProfile.DisabledReason.CROSS_PROFILE_NOT_ALLOWED)
                        }
                },
        )
    }

    /**
     * Attempts to extract a user handle from the provided intent, using the [Intent.EXTRA_USER]
     * key.
     *
     * @return the nullable UserHandle if the handle isn't provided, or if the object in
     *   [Intent.EXTRA_USER] isn't a [UserHandle]
     */
    private suspend fun getUserHandleFromIntent(intent: Intent): UserHandle? {

        if (SdkLevel.isAtLeastT())
        // Use the type-safe API when it's available.
        return intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
        else
            @Suppress("DEPRECATION")
            return intent.getParcelableExtra(Intent.EXTRA_USER) as? UserHandle
    }

    /** @return the content resolver for given profile. */
    private fun getContentResolver(context: Context, userHandle: UserHandle): ContentResolver =
        context
            .createPackageContextAsUser(context.packageName, /* flags */ 0, userHandle)
            .contentResolver
}
