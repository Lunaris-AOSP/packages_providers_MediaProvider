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

package com.android.photopicker.core.features

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.data.PrefetchDataService
import com.android.photopicker.features.albumgrid.AlbumGridFeature
import com.android.photopicker.features.browse.BrowseFeature
import com.android.photopicker.features.categorygrid.CategoryGridFeature
import com.android.photopicker.features.cloudmedia.CloudMediaFeature
import com.android.photopicker.features.navigationbar.NavigationBarFeature
import com.android.photopicker.features.overflowmenu.OverflowMenuFeature
import com.android.photopicker.features.photogrid.PhotoGridFeature
import com.android.photopicker.features.preparemedia.PrepareMediaFeature
import com.android.photopicker.features.preview.PreviewFeature
import com.android.photopicker.features.privacyexplainer.PrivacyExplainerFeature
import com.android.photopicker.features.profileselector.ProfileSelectorFeature
import com.android.photopicker.features.search.SearchFeature
import com.android.photopicker.features.selectionbar.SelectionBarFeature
import com.android.photopicker.features.snackbar.SnackbarFeature
import com.android.photopicker.util.mapOfDeferredWithTimeout
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The core class in the feature framework, the FeatureManager manages the registration,
 * initialiation and compose calls for the compose UI.
 *
 * The feature manager is responsible for calling Features via the [PhotopickerFeature] interface
 * framework, for various lifecycles, as well as providing the APIs for callers to inspect feature
 * state, change configuration, and generate composable units for various UI [Location]s.
 *
 * @property configuration a collectable [StateFlow] of configuration changes
 * @property scope A CoroutineScope that PhotopickerConfiguration updates are collected in.
 * @property registeredFeatures A set of Registrations that correspond to (potentially) enabled
 *   features.
 */
class FeatureManager(
    private val configuration: StateFlow<PhotopickerConfiguration>,
    private val scope: CoroutineScope,
    private val prefetchDataService: PrefetchDataService,
    // This is in the constructor to allow tests to swap in test features.
    private val registeredFeatures: Set<FeatureRegistration> =
        FeatureManager.KNOWN_FEATURE_REGISTRATIONS,
    // These are in the constructor to allow tests to swap in core event overrides.
    private val coreEventsConsumed: Set<RegisteredEventClass> = FeatureManager.CORE_EVENTS_CONSUMED,
    private val coreEventsProduced: Set<RegisteredEventClass> = FeatureManager.CORE_EVENTS_PRODUCED,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        val TAG: String = "PhotopickerFeatureManager"

        /*
         * The list of known [FeatureRegistration]s.
         * Any features that include their registration here, are subject to be enabled by the
         * [FeatureManager] when their [FeatureRegistration#isEnabled] returns true.
         */
        val KNOWN_FEATURE_REGISTRATIONS: Set<FeatureRegistration> =
            setOf(
                PhotoGridFeature.Registration,
                SelectionBarFeature.Registration,
                NavigationBarFeature.Registration,
                PreviewFeature.Registration,
                ProfileSelectorFeature.Registration,
                AlbumGridFeature.Registration,
                SnackbarFeature.Registration,
                CloudMediaFeature.Registration,
                OverflowMenuFeature.Registration,
                PrivacyExplainerFeature.Registration,
                BrowseFeature.Registration,
                SearchFeature.Registration,
                PrepareMediaFeature.Registration,
                CategoryGridFeature.Registration,
            )

        /* The list of events that the core library consumes. */
        val CORE_EVENTS_CONSUMED: Set<RegisteredEventClass> = setOf()

        /* The list of events that the core library produces. */
        val CORE_EVENTS_PRODUCED: Set<RegisteredEventClass> =
            setOf(
                Event.ShowSnackbarMessage::class.java,
                Event.ReportPhotopickerSessionInfo::class.java,
                Event.ReportPhotopickerApiInfo::class.java,
                Event.LogPhotopickerUIEvent::class.java,
                Event.LogPhotopickerAlbumOpenedUIEvent::class.java,
                Event.ReportPhotopickerMediaItemStatus::class.java,
                Event.LogPhotopickerPreviewInfo::class.java,
                Event.LogPhotopickerMenuInteraction::class.java,
                Event.LogPhotopickerBannerInteraction::class.java,
                Event.LogPhotopickerMediaLibraryInfo::class.java,
                Event.LogPhotopickerPageInfo::class.java,
                Event.ReportPhotopickerMediaGridSyncInfo::class.java,
                Event.ReportPhotopickerAlbumSyncInfo::class.java,
                Event.ReportPhotopickerSearchInfo::class.java,
                Event.ReportSearchDataExtractionDetails::class.java,
                Event.ReportEmbeddedPhotopickerInfo::class.java,
                Event.ReportPickerAppMediaCapabilities::class.java,
                Event.ReportTranscodingVideoDetails::class.java,
            )
    }

    // The internal mutable set of enabled features.
    // This field is read in the public method [isFeatureEnabled] which can be called from both the
    // main thread as well as various background threads, so ensure concurrency by using a slower,
    // but thread safe data structure. This list is fairly small (roughly the size of
    // KNOWN_FEATURE_REGISTRATIONS), and it's access is retrieval heavy rather than mutation heavy.
    private val _enabledFeatures: CopyOnWriteArraySet<PhotopickerFeature> = CopyOnWriteArraySet()

    // The internal map of claimed [FeatureToken] to the claiming [PhotopickerFeature]
    private val _tokenMap: HashMap<String, PhotopickerFeature> = HashMap()

    // A map containing the deferred results of all prefetched data.
    // Prefetched data is the data that features can request FeatureManager to fetch for them
    // (typically from a different process), before the features have to decide if they are enabled
    // or not.
    private val _deferredPrefetchResults: Map<PrefetchResultKey, Deferred<Any?>> =
        getDeferredPrefetchResults()

    /* Returns an immutable copy rather than the actual set. */
    val enabledFeatures: Set<PhotopickerFeature>
        get() = _enabledFeatures.toSet()

    val enabledUiFeatures: Set<PhotopickerUiFeature>
        get() = _enabledFeatures.filterIsInstance<PhotopickerUiFeature>().toSet()

    /*
     * The location registry for [PhotopickerUiFeature].
     *
     * The key in this map is the UI [Location]
     * The value is a *always* a sorted "priority-descending" set of Pairs
     *
     * Each pair represents a Feature which would like to draw UI at this Location, and the Priority
     * with which it would like to do so.
     *
     * It is critical that the list always remains sorted to avoid drawing the wrong element for a
     * Location with a limited number of slots. It can be sorted with [PriorityDescendingComparator]
     * to keep features sorted in order of Priority, then Registration (insertion) order.
     *
     * For Features who set the default Location [Priority.REGISTRATION_ORDER] they will
     * be drawn in order of registration in the [FeatureManager.KNOWN_FEATURE_REGISTRATIONS].
     *
     */
    private val locationRegistry: HashMap<Location, MutableList<Pair<PhotopickerUiFeature, Int>>> =
        HashMap()

    /* Instantiate a shared single instance of our custom priority sorter to save memory */
    private val priorityDescending: Comparator<Pair<Any, Int>> = PriorityDescendingComparator()

    init {
        initializeFeatureSet()

        // Begin collecting the PhotopickerConfiguration and update the feature configuration
        // accordingly.
        scope.launch {
            // Drop the first value here to prevent initializing twice.
            // (initializeFeatureSet will pick up the first value on its own.)
            configuration.drop(1).collect { onConfigurationChanged(it) }
        }
    }

    /**
     * Set a new configuration value in the FeatureManager.
     *
     * Warning: This is an expensive operation, and should be batched if multiple configuration
     * updates are expected in the near future.
     * 1. Notify all existing features of the pending configuration change,
     * 2. Wipe existing features
     * 3. Re-initialize Feature set with new configuration
     */
    private fun onConfigurationChanged(newConfig: PhotopickerConfiguration) {
        Log.d(TAG, """Configuration has changed, re-initializing. $newConfig""")

        // Notify all active features of the incoming config change.
        _enabledFeatures.forEach { it.onConfigurationChanged(newConfig) }

        // Drop all registrations and prepare to reinitialize.
        resetAllRegistrations()

        // Re-initialize.
        initializeFeatureSet(newConfig)
    }

    /** Drops all known registrations and returns to a pre-initialization state */
    private fun resetAllRegistrations() {
        _enabledFeatures.clear()
        _tokenMap.clear()
        locationRegistry.clear()
    }

    /**
     * 1. Collects all prefetch data requests from features.
     * 2. Tries to fetch all prefetched results asynchronously (in parallel on background threads).
     *    Each prefetch result should be received within a timeout of 200ms, otherwise the task will
     *    be cancelled and the result will be null. If an error occurs, it will be swallowed and the
     *    result will be null.
     *
     * @return A Map of prefetch result key to a Deferred prefetch result value.
     */
    private fun getDeferredPrefetchResults(): Map<PrefetchResultKey, Deferred<Any?>> {
        Log.d(TAG, "Beginning prefetching results in the background.")

        val prefetchRequestMap:
            MutableMap<PrefetchResultKey, suspend (PrefetchDataService) -> Any?> =
            mutableMapOf()
        registeredFeatures
            .mapNotNull { it.getPrefetchRequest(configuration.value) }
            .forEach { prefetchRequestMap.putAll(it) }

        val prefetchDeferredResultsMap =
            runBlocking(dispatcher) {
                mapOfDeferredWithTimeout<PrefetchResultKey, PrefetchDataService>(
                    inputMap = prefetchRequestMap,
                    input = prefetchDataService,
                    timeoutMillis = 250L,
                    backgroundScope = scope,
                    dispatcher = dispatcher,
                )
            }

        Log.d(
            TAG,
            "Creation of deferred prefetch results map is complete for keys: " +
                "${prefetchRequestMap.keys}",
        )

        return prefetchDeferredResultsMap
    }

    /**
     * For the provided set of [FeatureRegistration]s, attempt to initialize the runtime Feature set
     * with the current [PhotopickerConfiguration].
     *
     * @param config The configuration to use for initialization. Defaults to the current
     *   configuration.
     * @throws [IllegalStateException] if multiple features attempt to claim the same
     *   [FeatureToken].
     */
    private fun initializeFeatureSet(config: PhotopickerConfiguration = configuration.value) {
        Log.d(TAG, "Beginning feature initialization with config: ${configuration.value}")

        for (featureCompanion in registeredFeatures) {
            if (featureCompanion.isEnabled(config, _deferredPrefetchResults)) {
                val feature = featureCompanion.build(this)
                _enabledFeatures.add(feature)
                if (_tokenMap.contains(feature.token))
                    throw IllegalStateException(
                        "A feature has already claimed ${feature.token}. " +
                            "Tokens must be unique for any given configuration."
                    )
                _tokenMap.put(feature.token, feature)
                if (feature is PhotopickerUiFeature) registerLocationsForFeature(feature)
            }
        }

        validateEventRegistrations()

        Log.d(
            TAG,
            "Feature initialization complete. Features: ${_enabledFeatures.map { it.token }}",
        )
    }

    /**
     * Inspect the event registrations for consumed and produced events based on the core library
     * and the current set of enabledFeatures.
     *
     * This check ensures that all events that need to be consumed have at least one possible
     * producer (it does not guarantee the event will actually be produced).
     *
     * In the event consumed events are not produced, this behaves differently depending on the
     * [PhotopickerConfiguration].
     * - If [PhotopickerConfiguration.deviceIsDebuggable] this will throw [IllegalStateException]
     *   This is done to try to prevent bad configurations from escaping test and dev builds.
     * - Else This will Log a warning, but allow initialization to proceed to avoid a runtime crash.
     */
    private fun validateEventRegistrations() {
        // Include the events the CORE library expects to consume in the list of consumed events,
        // along with all enabledFeatures.
        val consumedEvents: Set<RegisteredEventClass> =
            listOf(coreEventsConsumed, *_enabledFeatures.map { it.eventsConsumed }.toTypedArray())
                .flatten()
                .toSet()

        // Include the events the CORE library expects to produce in the list of produced events,
        // along with all enabledFeatures.
        val producedEvents: Set<RegisteredEventClass> =
            listOf(coreEventsProduced, *_enabledFeatures.map { it.eventsProduced }.toTypedArray())
                .flatten()
                .toSet()

        val consumedButNotProduced = (consumedEvents subtract producedEvents)

        if (consumedButNotProduced.isNotEmpty()) {
            if (configuration.value.deviceIsDebuggable) {
                // If the device is a debuggable build, throw an [IllegalStateException] to ensure
                // that unregistered events don't introduce un-intentional side-effects.
                throw IllegalStateException(
                    "Events are expected to be consumed that are not produced: " +
                        "$consumedButNotProduced"
                )
            } else {
                // If this is a production build, this is still a bad state, but avoid crashing, and
                // put a note in the logs that the event registration is potentially problematic.
                Log.w(
                    TAG,
                    "Events are expected to be consumed that are not produced: " +
                        "$consumedButNotProduced",
                )
            }
        }
    }

    /**
     * Adds the [PhotopickerUiFeature]'s registered locations to the internal location registry.
     *
     * To minimize memory footprint, the location is only initialized if at least one feature has it
     * in its list of registeredLocations. This avoids the underlying registry carrying empty lists
     * for location that no feature wishes to use.
     *
     * The list that is initialized uses the local [PriorityDescendingComparator] to keep the
     * features at that location sorted by priority.
     */
    private fun registerLocationsForFeature(feature: PhotopickerUiFeature) {
        val locationPairs = feature.registerLocations()

        for ((first, second) in locationPairs) {
            // Try to add the feature to this location's registry.
            locationRegistry.get(first)?.let {
                it.add(Pair(feature, second))
                it.sortWith(priorityDescending)
            }
                // If this is the first registration for this location, initialize the list and add
                // the current feature to the registry for this location.
                ?: locationRegistry.put(first, mutableListOf(Pair(feature, second)))
        }
    }

    /**
     * Whether or not a requested feature is enabled
     *
     * @param featureClass - The class of the feature (doesn't require an instance to be created)
     * @return true if the requested feature is enabled in the current session.
     */
    fun isFeatureEnabled(featureClass: Class<out PhotopickerFeature>): Boolean {
        return _enabledFeatures.any { it::class.java == featureClass }
    }

    /**
     * Check if a provided event can be dispatched with the current enabled feature set.
     *
     * This is called when an event is dispatched to ensure that features cannot dispatch events
     * that they do not include in their [PhotopickerFeature.eventsProduced] event registry.
     *
     * This checks the claiming [dispatcherToken] in the Event and checks the corresponding
     * feature's event registry to ensure the event has claimed it dispatches the particular Event
     * class. In the event of a CORE library event, check the internal mapping owned by
     * [FeatureManager].
     *
     * @return Whether the event complies with the event registry.
     */
    fun isEventDispatchable(event: Event): Boolean {
        if (event.dispatcherToken == FeatureToken.CORE.token)
            return coreEventsProduced.contains(event::class.java)
        return _tokenMap.get(event.dispatcherToken)?.eventsProduced?.contains(event::class.java)
            ?: false
    }

    /**
     * Checks the run-time (current) maximum size (in terms of number of children created) of the
     * provided [Location] in the [FeatureManager] internal [locationRegistry].
     *
     * This allows features to determine if a given [composeLocation] call will actually create any
     * child elements at the location.
     *
     * The size returned is always stable for the current [PhotopickerConfiguration] but may change
     * if the configuration is changed, since features could be added or removed under the new
     * configuration.
     *
     * NOTE: This only returns the number of children, there is no way to directly interact with the
     * feature classes registered at the given location.
     *
     * @param location The location to check the size of.
     * @return the max number of children of the location. Cannot be negative.
     * @see [composeLocation] for rendering the children of a [Location] in the compose tree.
     */
    fun getSizeOfLocationInRegistry(location: Location): Int {
        // There is no guarantee the [Location] exists in the registry, since it is initialized
        // lazily, its possible that features have not been registered for the current
        // configuration.
        return locationRegistry.get(location)?.size ?: 0
    }

    /**
     * Calls all of the relevant compose methods for all enabled [PhotopickerUiFeature] that have
     * the [Location] in their registered locations, in their declared priority descending order.
     *
     * Features with a higher priority are composed first.
     *
     * This is the primary API for features to compose UI using the [Location] framework.
     *
     * This can result in an empty [Composable] if no features have the provided [Location] in their
     * list of registered locations.
     *
     * Additional parameters can be passed via the [LocationParams] interface for providing
     * functionality such as click handlers or passing primitive data.
     *
     * @param location The UI location that needs to be composed
     * @param maxSlots (Optional, default unlimited) The maximum number of features that can compose
     *   at this location. If set, this will call features in priority order until all slots of been
     *   exhausted.
     * @param modifier (Optional) A [Modifier] to pass in the compose call.
     * @param params (Optional) A [LocationParams] to pass in the compose call.
     * @see [LocationParams]
     *
     * Note: Be careful where this is called in the UI tree. Calling this inside of a composable
     * that is regularly re-composed will result in the entire sub tree being re-composed, which can
     * impact performance.
     */
    @Composable
    fun composeLocation(
        location: Location,
        maxSlots: Int? = null,
        modifier: Modifier = Modifier,
        params: LocationParams = LocationParams.None,
    ) {
        val featurePairs = locationRegistry.get(location)

        // There is no guarantee the [Location] exists in the registry, since it is initialized
        // lazily, its possible that features have not been registered.
        featurePairs?.let {
            for (feature in featurePairs.take(maxSlots ?: featurePairs.size)) {
                Log.d(TAG, "Composing for $location for $feature")
                feature.first.compose(location, modifier, params)
            }
        }
    }
}
