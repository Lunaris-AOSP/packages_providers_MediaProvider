/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.providers.media.photopicker.sync;

import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markAlbumMediaSyncAsComplete;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markMediaInMediaSetSyncAsComplete;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markMediaSetsSyncAsComplete;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markSearchResultsSyncAsComplete;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markSyncAsComplete;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.trackNewAlbumMediaSyncRequests;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.trackNewMediaInMediaSetSyncRequest;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.trackNewMediaSetsSyncRequest;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.trackNewSearchResultsSyncRequests;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.trackNewSyncRequests;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;

import com.android.modules.utils.BackgroundThread;
import com.android.providers.media.ConfigStore;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.photopicker.data.PickerSyncRequestExtras;
import com.android.providers.media.photopicker.v2.model.MediaInMediaSetSyncRequestParams;
import com.android.providers.media.photopicker.v2.model.MediaSetsSyncRequestParams;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * This class manages all the triggers for Picker syncs.
 * <p></p>
 * There are different use cases for triggering a sync:
 * <p>
 * 1. Proactive sync - these syncs are proactively performed to minimize the changes that need to be
 *      synced when the user opens the Photo Picker. The sync should only be performed if the device
 *      state allows it.
 * <p>
 * 2. Reactive sync - these syncs are triggered by the user opening the Photo Picker. These should
 *      be run immediately since the user is likely to be waiting for the sync response on the UI.
 */
public class PickerSyncManager {
    private static final String TAG = "SyncWorkManager";
    public static final int SYNC_LOCAL_ONLY = 1;
    public static final int SYNC_CLOUD_ONLY = 2;
    public static final int SYNC_LOCAL_AND_CLOUD = 3;
    public static final int SYNC_MEDIA_GRANTS = 4;

    @IntDef(value = { SYNC_LOCAL_ONLY, SYNC_CLOUD_ONLY, SYNC_LOCAL_AND_CLOUD, SYNC_MEDIA_GRANTS })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SyncSource {}

    public static final int SYNC_RESET_MEDIA = 1;
    public static final int SYNC_RESET_ALBUM = 2;

    @IntDef(value = {SYNC_RESET_MEDIA, SYNC_RESET_ALBUM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SyncResetType {}

    /** Clears all search requests and search results from the database. */
    public static final int SEARCH_RESULTS_FULL_CACHE_RESET = 1;
    /** Clears search results and suggestions of the local or cloud provider from the database. */
    public static final int SEARCH_PARTIAL_CACHE_RESET = 2;
    /** Clears all expired history and cached suggestions from the database. */
    public static final int EXPIRED_SUGGESTIONS_RESET = 3;

    @IntDef(value = {
            SEARCH_RESULTS_FULL_CACHE_RESET,
            SEARCH_PARTIAL_CACHE_RESET,
            EXPIRED_SUGGESTIONS_RESET})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SearchCacheResetType {}

    static final String SYNC_WORKER_INPUT_AUTHORITY = "INPUT_AUTHORITY";
    static final String SYNC_WORKER_INPUT_SYNC_SOURCE = "INPUT_SYNC_TYPE";
    static final String SYNC_WORKER_INPUT_RESET_TYPE = "INPUT_RESET_TYPE";
    static final String SYNC_WORKER_INPUT_ALBUM_ID = "INPUT_ALBUM_ID";
    static final String SYNC_WORKER_INPUT_SEARCH_REQUEST_ID = "INPUT_SEARCH_REQUEST_ID";
    static final String SYNC_WORKER_INPUT_CATEGORY_ID = "INPUT_CATEGORY_ID";
    static final String SYNC_WORKER_INPUT_MEDIA_SET_ID = "INPUT_MEDIA_SET_ID";
    static final String SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID = "INPUT_MEDIA_SET_PICKER_ID";
    static final String SYNC_WORKER_TAG_IS_PERIODIC = "PERIODIC";
    static final long PROACTIVE_SYNC_DELAY_MS = 1500;
    private static final int SYNC_MEDIA_PERIODIC_WORK_INTERVAL = 4; // Time unit is hours.
    private static final int RESET_ALBUM_MEDIA_PERIODIC_WORK_INTERVAL = 12; // Time unit is hours.
    // Time unit is days.
    private static final int RESET_SEARCH_SUGGESTIONS_PERIODIC_WORK_INTERVAL = 1;
    static final int SEARCH_RESULTS_RESET_DELAY = 30; // Time unit is minutes.

    public static final String PERIODIC_SYNC_WORK_NAME;
    private static final String PROACTIVE_LOCAL_SYNC_WORK_NAME;
    private static final String PROACTIVE_SYNC_WORK_NAME;
    public static final String IMMEDIATE_LOCAL_SYNC_WORK_NAME;
    private static final String IMMEDIATE_CLOUD_SYNC_WORK_NAME;
    public static final String IMMEDIATE_ALBUM_SYNC_WORK_NAME;
    public static final String IMMEDIATE_LOCAL_SEARCH_SYNC_WORK_NAME;
    public static final String IMMEDIATE_CLOUD_SEARCH_SYNC_WORK_NAME;
    public static final String IMMEDIATE_LOCAL_MEDIA_SETS_SYNC_WORK_NAME;
    public static final String IMMEDIATE_CLOUD_MEDIA_SETS_SYNC_WORK_NAME;
    public static final String IMMEDIATE_LOCAL_MEDIA_IN_MEDIA_SET_SYNC_WORK_NAME;
    public static final String IMMEDIATE_CLOUD_MEDIA_IN_MEDIA_SET_SYNC_WORK_NAME;
    public static final String SEARCH_CACHE_RESET_WORK_NAME;
    public static final String PERIODIC_SEARCH_SUGGESTIONS_RESET_WORK_NAME;
    public static final String PERIODIC_ALBUM_RESET_WORK_NAME;
    private static final String ENDLESS_WORK_NAME;
    public static final String IMMEDIATE_GRANTS_SYNC_WORK_NAME;
    public static final String SHOULD_SYNC_GRANTS;
    public static final String EXTRA_MIME_TYPES;

    static {
        final String syncPeriodicPrefix = "SYNC_MEDIA_PERIODIC_";
        final String syncProactivePrefix = "SYNC_MEDIA_PROACTIVE_";
        final String syncImmediatePrefix = "SYNC_MEDIA_IMMEDIATE_";
        final String syncSearchResultsImmediatePrefix = "SYNC_SEARCH_RESULTS_IMMEDIATE_";
        final String syncMediaSetsImmediatePrefix = "SYNC_MEDIA_SETS_IMMEDIATE_";
        final String syncMediaInMediaSetImmediatePrefix = "SYNC_MEDIA_IN_MEDIA_SET_IMMEDIATE";
        final String syncAllSuffix = "ALL";
        final String syncLocalSuffix = "LOCAL";
        final String syncCloudSuffix = "CLOUD";
        final String syncGrantsSuffix = "GRANTS";

        PERIODIC_ALBUM_RESET_WORK_NAME = "RESET_ALBUM_MEDIA_PERIODIC";
        PERIODIC_SYNC_WORK_NAME = syncPeriodicPrefix + syncAllSuffix;
        PROACTIVE_LOCAL_SYNC_WORK_NAME = syncProactivePrefix + syncLocalSuffix;
        PROACTIVE_SYNC_WORK_NAME = syncProactivePrefix + syncAllSuffix;
        IMMEDIATE_GRANTS_SYNC_WORK_NAME = syncImmediatePrefix + syncGrantsSuffix;
        IMMEDIATE_LOCAL_SYNC_WORK_NAME = syncImmediatePrefix + syncLocalSuffix;
        IMMEDIATE_CLOUD_SYNC_WORK_NAME = syncImmediatePrefix + syncCloudSuffix;
        IMMEDIATE_ALBUM_SYNC_WORK_NAME = "SYNC_ALBUM_MEDIA_IMMEDIATE";
        IMMEDIATE_LOCAL_SEARCH_SYNC_WORK_NAME = syncSearchResultsImmediatePrefix + syncLocalSuffix;
        // Use this work name to schedule cloud search results sync and cloud search results
        // reset both.
        IMMEDIATE_CLOUD_SEARCH_SYNC_WORK_NAME = syncSearchResultsImmediatePrefix + syncCloudSuffix;
        IMMEDIATE_LOCAL_MEDIA_SETS_SYNC_WORK_NAME = syncMediaSetsImmediatePrefix + syncLocalSuffix;
        IMMEDIATE_CLOUD_MEDIA_SETS_SYNC_WORK_NAME = syncMediaSetsImmediatePrefix + syncCloudSuffix;
        IMMEDIATE_LOCAL_MEDIA_IN_MEDIA_SET_SYNC_WORK_NAME =
                syncMediaInMediaSetImmediatePrefix + syncLocalSuffix;
        IMMEDIATE_CLOUD_MEDIA_IN_MEDIA_SET_SYNC_WORK_NAME =
                syncMediaInMediaSetImmediatePrefix + syncCloudSuffix;
        SEARCH_CACHE_RESET_WORK_NAME = "SEARCH_CACHE_FULL_RESET";
        PERIODIC_SEARCH_SUGGESTIONS_RESET_WORK_NAME = "RESET_SEARCH_SUGGESTIONS";
        ENDLESS_WORK_NAME = "ENDLESS_WORK";
        SHOULD_SYNC_GRANTS = "SHOULD_SYNC_GRANTS";
        EXTRA_MIME_TYPES = "mime_types";
    }

    private final WorkManager mWorkManager;
    private final Context mContext;

    public PickerSyncManager(@NonNull WorkManager workManager, @NonNull Context context) {
        mWorkManager = requireNonNull(workManager);
        mContext = requireNonNull(context);
    }

    /**
     * Schedule proactive periodic media syncs.
     *
     * @param configStore And instance of {@link ConfigStore} that holds all config info.
     */
    public void schedulePeriodicSync(@NonNull ConfigStore configStore) {
        schedulePeriodicSync(configStore, /* periodicSyncInitialDelay */10000L);
    }

    /**
     * Schedule proactive periodic media syncs.
     *
     * @param configStore And instance of {@link ConfigStore} that holds all the config info.
     * @param periodicSyncInitialDelay Initial delay of periodic sync in milliseconds.
     */
    public void schedulePeriodicSync(
            @NonNull ConfigStore configStore,
            long periodicSyncInitialDelay) {
        requireNonNull(configStore);

        // Move to a background thread to remove from MediaProvider boot path.
        BackgroundThread.getHandler().postDelayed(
                () -> {
                    try {
                        setUpEndlessWork();
                        setUpPeriodicWork(configStore);
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Could not schedule workers", e);
                    }
                },
                periodicSyncInitialDelay
        );

        // Subscribe to device config changes so we can enable periodic workers if Cloud
        // Photopicker is enabled.
        configStore.addOnChangeListener(
                BackgroundThread.getExecutor(),
                () -> setUpPeriodicWork(configStore));
    }

    /**
     * Will register new unique {@link Worker} for periodic sync and picker database maintenance if
     * the cloud photopicker experiment is currently enabled.
     */
    private void setUpPeriodicWork(@NonNull ConfigStore configStore) {
        try {
            requireNonNull(configStore);

            if (configStore.isCloudMediaInPhotoPickerEnabled()) {
                PickerSyncNotificationHelper.createNotificationChannel(mContext);

                schedulePeriodicSyncs();
                schedulePeriodicAlbumReset();
            } else {
                // Disable any scheduled ongoing work if the feature is disabled.
                mWorkManager.cancelUniqueWork(PERIODIC_SYNC_WORK_NAME);
                mWorkManager.cancelUniqueWork(PERIODIC_ALBUM_RESET_WORK_NAME);
            }

            if (Flags.enablePhotopickerSearch()) {
                schedulePeriodicSearchSuggestionsReset();
            } else {
                mWorkManager.cancelUniqueWork(PERIODIC_SEARCH_SUGGESTIONS_RESET_WORK_NAME);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not schedule periodic work", e);
        }
    }

    /**
     * Will register a new {@link Worker} for 1 year in the future. This is to prevent the {@link
     * androidx.work.impl.background.systemalarm.RescheduleReceiver} from being disabled by WM
     * internals, which triggers PACKAGE_CHANGED broadcasts every time a new worker is scheduled. As
     * a work around to prevent these broadcasts, we enqueue a worker here very far in the future to
     * prevent the component from being disabled by work manager.
     *
     * <p>{@see b/314863434 for additional context.}
     */
    private void setUpEndlessWork() {

        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(EndlessWorker.class)
                        .setInitialDelay(365, TimeUnit.DAYS)
                        .build();

        mWorkManager.enqueueUniqueWork(
                ENDLESS_WORK_NAME, ExistingWorkPolicy.KEEP, request);
        Log.d(TAG, "EndlessWorker has been enqueued");
    }

    private void schedulePeriodicSyncs() {
        Log.i(TAG, "Scheduling periodic proactive syncs");

        final Data inputData =
                new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_AND_CLOUD));
        final PeriodicWorkRequest periodicSyncRequest = getPeriodicProactiveSyncRequest(inputData);

        try {
            // Note that the first execution of periodic work happens immediately or as soon as the
            // given Constraints are met.
            final Operation enqueueOperation = mWorkManager
                    .enqueueUniquePeriodicWork(
                            PERIODIC_SYNC_WORK_NAME,
                            ExistingPeriodicWorkPolicy.KEEP,
                            periodicSyncRequest
                    );

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Could not enqueue periodic proactive picker sync request", e);
        }
    }

    private void schedulePeriodicAlbumReset() {
        Log.i(TAG, "Scheduling periodic picker album data resets");

        final Data inputData =
                new Data(
                        Map.of(
                                SYNC_WORKER_INPUT_SYNC_SOURCE,
                                SYNC_LOCAL_AND_CLOUD,
                                SYNC_WORKER_INPUT_RESET_TYPE,
                                SYNC_RESET_ALBUM));
        final PeriodicWorkRequest periodicAlbumResetRequest =
                getPeriodicAlbumResetRequest(inputData);

        try {
            // Note that the first execution of periodic work happens immediately or as soon
            // as the given Constraints are met.
            Operation enqueueOperation =
                    mWorkManager.enqueueUniquePeriodicWork(
                            PERIODIC_ALBUM_RESET_WORK_NAME,
                            ExistingPeriodicWorkPolicy.KEEP,
                            periodicAlbumResetRequest);

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Could not enqueue periodic picker album resets request", e);
        }
    }

    /**
     * Use this method for proactive syncs. The sync might take a while to start. Some device state
     * conditions may apply before the sync can start like battery level etc.
     *
     * @param localOnly - whether the proactive sync should only sync with the local provider.
     */
    public void syncMediaProactively(Boolean localOnly) {

        final int syncSource = localOnly ? SYNC_LOCAL_ONLY : SYNC_LOCAL_AND_CLOUD;
        final String workName =
                localOnly ? PROACTIVE_LOCAL_SYNC_WORK_NAME : PROACTIVE_SYNC_WORK_NAME;

        final Data inputData = new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, syncSource));
        final OneTimeWorkRequest syncRequest = getOneTimeProactiveSyncRequest(inputData);

        // Don't wait for the sync operation to enqueue so that Picker sync enqueue
        // requests in order to avoid adding latency to critical MP code paths.
        mWorkManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, syncRequest);
    }

    /**
     * Use this method for reactive syncs which are user triggered.
     *
     * @param pickerSyncRequestExtras extras used to figure out which all syncs to trigger.
     */
    public void syncMediaImmediately(
            @NonNull PickerSyncRequestExtras pickerSyncRequestExtras,
            @NonNull ConfigStore configStore) {
        requireNonNull(pickerSyncRequestExtras);
        requireNonNull(configStore);

        if (configStore.isModernPickerEnabled()) {
            // sync for grants is only required for the modern picker, the java picker uses
            // MediaStore to directly fetch the grants for all purposes of selection.
            syncGrantsImmediately(
                    IMMEDIATE_GRANTS_SYNC_WORK_NAME,
                    pickerSyncRequestExtras.getCallingPackageUid(),
                    pickerSyncRequestExtras.isShouldSyncGrants(),
                    pickerSyncRequestExtras.getMimeTypes());
        }

        syncMediaImmediately(PickerSyncManager.SYNC_LOCAL_ONLY, IMMEDIATE_LOCAL_SYNC_WORK_NAME);
        if (!pickerSyncRequestExtras.shouldSyncLocalOnlyData()) {
            syncMediaImmediately(PickerSyncManager.SYNC_CLOUD_ONLY, IMMEDIATE_CLOUD_SYNC_WORK_NAME);
        }
    }

    /**
     * Use this method for reactive syncs for grants from the external database.
     */
    private void syncGrantsImmediately(@NonNull String workName, int callingPackageUid,
            boolean shouldSyncGrants, String[] mimeTypes) {
        final Data inputData = new Data(
                Map.of(
                        Intent.EXTRA_UID, callingPackageUid,
                        SHOULD_SYNC_GRANTS, shouldSyncGrants,
                        EXTRA_MIME_TYPES, mimeTypes
                )
        );

        final OneTimeWorkRequest syncRequestForGrants =
                buildOneTimeWorkerRequest(ImmediateGrantsSyncWorker.class, inputData);

        // Track the new sync request(s)
        trackNewSyncRequests(PickerSyncManager.SYNC_MEDIA_GRANTS, syncRequestForGrants.getId());

        // Enqueue grants sync request
        try {
            final Operation enqueueOperation = mWorkManager
                    .enqueueUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE,
                            syncRequestForGrants);

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (Exception e) {
            Log.e(TAG, "Could not enqueue expedited picker grants sync request", e);
            markSyncAsComplete(PickerSyncManager.SYNC_MEDIA_GRANTS,
                    syncRequestForGrants.getId());
        }
    }

    /**
     * Use this method for reactive syncs with either, local and cloud providers, or both.
     */
    private void syncMediaImmediately(@SyncSource int syncSource, @NonNull String workName) {
        final Data inputData = new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, syncSource));
        final OneTimeWorkRequest syncRequest =
                buildOneTimeWorkerRequest(ImmediateSyncWorker.class, inputData);

        // Track the new sync request(s)
        trackNewSyncRequests(syncSource, syncRequest.getId());

        // Enqueue local or cloud sync request
        try {
            final Operation enqueueOperation = mWorkManager
                    .enqueueUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE, syncRequest);

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (Exception e) {
            Log.e(TAG, "Could not enqueue expedited picker sync request", e);
            markSyncAsComplete(syncSource, syncRequest.getId());
        }
    }

    /**
     * Use this method for reactive syncs which are user action triggered.
     *
     * @param albumId is the id of the album that needs to be synced.
     * @param authority The authority of the album media.
     * @param isLocal is {@code true} iff the album authority is of the local provider.
     */
    public void syncAlbumMediaForProviderImmediately(
            @NonNull String albumId, @NonNull String authority, boolean isLocal) {
        syncAlbumMediaForProviderImmediately(albumId, getSyncSource(isLocal), authority);
    }

    /**
     * Use this method for reactive syncs which are user action triggered.
     *
     * @param albumId is the id of the album that needs to be synced.
     * @param syncSource indicates if the sync is required with local provider or cloud provider or
     *     both.
     */
    private void syncAlbumMediaForProviderImmediately(
            @NonNull String albumId, @SyncSource int syncSource, String authority) {
        final Data inputData =
                new Data(
                        Map.of(
                                SYNC_WORKER_INPUT_AUTHORITY, authority,
                                SYNC_WORKER_INPUT_SYNC_SOURCE, syncSource,
                                SYNC_WORKER_INPUT_RESET_TYPE, SYNC_RESET_ALBUM,
                                SYNC_WORKER_INPUT_ALBUM_ID, albumId));
        final OneTimeWorkRequest resetRequest =
                buildOneTimeWorkerRequest(MediaResetWorker.class, inputData);
        final OneTimeWorkRequest syncRequest =
                buildOneTimeWorkerRequest(ImmediateAlbumSyncWorker.class, inputData);

        // Track the new sync request(s)
        trackNewAlbumMediaSyncRequests(syncSource, resetRequest.getId());
        trackNewAlbumMediaSyncRequests(syncSource, syncRequest.getId());

        // Enqueue local or cloud sync requests
        try {
            final Operation enqueueOperation =
                    mWorkManager
                            .beginUniqueWork(
                                    IMMEDIATE_ALBUM_SYNC_WORK_NAME,
                                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                                    resetRequest)
                            .then(syncRequest).enqueue();

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (Exception e) {
            Log.e(TAG, "Could not enqueue expedited picker sync request", e);
            markAlbumMediaSyncAsComplete(syncSource, resetRequest.getId());
            markAlbumMediaSyncAsComplete(syncSource, syncRequest.getId());
        }
    }

    /**
     * Use this method for reactive search results sync which are user action triggered.
     *
     * @param searchRequestId Identifier for the search request.
     * @param syncSource indicates if the sync is required with local provider or cloud provider.
     *                   Sync source cannot be both in this case.
     * @param authority Authority of the provider.
     */
    public void syncSearchResultsForProvider(
            int searchRequestId, @SyncSource int syncSource, String authority) {
        final Data inputData =
                new Data(
                        Map.of(
                                SYNC_WORKER_INPUT_AUTHORITY, authority,
                                SYNC_WORKER_INPUT_SYNC_SOURCE, syncSource,
                                SYNC_WORKER_INPUT_SEARCH_REQUEST_ID, searchRequestId));
        final OneTimeWorkRequest syncRequest =
                buildOneTimeWorkerRequest(SearchResultsSyncWorker.class, inputData);

        // Track the new sync request
        trackNewSearchResultsSyncRequests(syncSource, syncRequest.getId());

        final String workName = syncSource == SYNC_LOCAL_ONLY
                ? IMMEDIATE_LOCAL_SEARCH_SYNC_WORK_NAME
                : IMMEDIATE_CLOUD_SEARCH_SYNC_WORK_NAME;
        // Enqueue local or cloud sync request
        try {
            final Operation enqueueOperation = mWorkManager.enqueueUniqueWork(
                    workName,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    syncRequest);

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (Exception e) {
            Log.e(TAG, "Could not enqueue expedited search results sync request", e);
            markSearchResultsSyncAsComplete(syncSource, syncRequest.getId());
        }
    }

    /**
     * Schedules work to reset all cloud search results and suggestions synced in the database.
     * This is used when the cloud media provider changes or the collection id of the cloud media
     * provider changes indicating that a full reset of cloud media is required.
     *
     * @param cloudAuthority Cloud authority might be null if there was an error in getting it.
     */
    public void resetCloudSearchCache(@Nullable String cloudAuthority) {
        final Map<String, Object> inputMap = new HashMap<>();
        inputMap.put(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_CLOUD_ONLY);
        inputMap.put(SYNC_WORKER_INPUT_RESET_TYPE, SEARCH_PARTIAL_CACHE_RESET);
        if (cloudAuthority != null) {
            inputMap.put(SYNC_WORKER_INPUT_AUTHORITY, cloudAuthority);
        }
        final Data inputData = new Data(inputMap);

        final OneTimeWorkRequest syncRequest =
                buildOneTimeWorkerRequest(SearchResetWorker.class, inputData);

        try {
            Log.d(TAG, "Scheduling cloud search results reset request.");

            // Enqueue cloud search reset request with the ExistingWorkPolicy as REPLACE so
            // that any currently running synced will be cancelled. Don't wait to check the
            // results of the enqueue operation because this runs the critical path.
            mWorkManager.enqueueUniqueWork(
                    IMMEDIATE_CLOUD_SEARCH_SYNC_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    syncRequest);
        } catch (Exception e) {
            Log.e(TAG, "Could not enqueue search results cloud reset request", e);
        }
    }

    /**
     * Schedules work to reset all search results cache after some delay from the search database.
     */
    public void delayedResetSearchCache() {
        final Data inputData =
                new Data(Map.of(
                        SYNC_WORKER_INPUT_RESET_TYPE, SEARCH_RESULTS_FULL_CACHE_RESET,
                        SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_AND_CLOUD));
        final OneTimeWorkRequest syncRequest =
                getDelayedSearchResetRequest(inputData);

        // Enqueue full cache reset request. Ensure that this runs when the device is idle to
        // prevent search requests from clearing when the user is using PhotoPicker search feature.
        try {
            Log.d(TAG, "Scheduling search results full cache reset request.");
            mWorkManager.enqueueUniqueWork(
                    SEARCH_CACHE_RESET_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    syncRequest);
        } catch (Exception e) {
            Log.e(TAG, "Could not enqueue search results full cache reset request", e);
        }
    }

    /**
     * Schedules periodic syncs that clears expired search history and cached suggestions from the
     * Picker database when the search feature is turned on.
     */
    public void schedulePeriodicSearchSuggestionsReset() {
        final Data inputData =
                new Data(Map.of(
                        SYNC_WORKER_INPUT_RESET_TYPE, EXPIRED_SUGGESTIONS_RESET,
                        SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_AND_CLOUD));
        final PeriodicWorkRequest syncRequest =
                getPeriodicSearchSuggestionsResetRequest(inputData);

        try {
            Operation enqueueOperation =
                    mWorkManager.enqueueUniquePeriodicWork(
                            PERIODIC_SEARCH_SUGGESTIONS_RESET_WORK_NAME,
                            ExistingPeriodicWorkPolicy.KEEP,
                            syncRequest);

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Could not enqueue periodic search suggestions request", e);
        }
    }

    /**
     * Creates OneTimeWork request for syncing media sets with the given provider
     * @param requestParams The MediaSetsSyncRequestsParams object containing all input parameters
     *                      for creating a sync request
     * @param syncSource Indicates whether the sync is required with the local provider or
     *                   the cloud provider.
     */
    public void syncMediaSetsForProvider(
            MediaSetsSyncRequestParams requestParams, @SyncSource int syncSource) {
        final Map<String, Object> inputMap = new HashMap<>();
        inputMap.put(SYNC_WORKER_INPUT_AUTHORITY, requestParams.getAuthority());
        inputMap.put(SYNC_WORKER_INPUT_SYNC_SOURCE, syncSource);
        inputMap.put(SYNC_WORKER_INPUT_CATEGORY_ID, requestParams.getCategoryId());
        if (requestParams.getMimeTypes() != null) {
            inputMap.put(EXTRA_MIME_TYPES, requestParams.getMimeTypes().toArray(new String[0]));
        }
        final Data inputData = new Data(inputMap);
        final OneTimeWorkRequest syncRequest =
                buildOneTimeWorkerRequest(MediaSetsSyncWorker.class, inputData);

        // track the new request
        trackNewMediaSetsSyncRequest(syncSource, syncRequest.getId());

        final String workName = syncSource == SYNC_LOCAL_ONLY
                ? IMMEDIATE_LOCAL_MEDIA_SETS_SYNC_WORK_NAME
                : IMMEDIATE_CLOUD_MEDIA_SETS_SYNC_WORK_NAME;
        // Enqueue local or cloud sync request
        try {
            final Operation enqueueOperation = mWorkManager.enqueueUniqueWork(
                    workName,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    syncRequest);

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (Exception e) {
            Log.e(TAG, "Could not enqueue expedited media sets sync request", e);
            markMediaSetsSyncAsComplete(syncSource, syncRequest.getId());
        }
    }

    /**
     * Creates OneTimeWork request for syncing media in media set with the given provider
     * @param requestParams The MediaInMediaSetSyncRequestParams object containing all input
     *                      parameters for creating a sync request
     * @param syncSource Indicates whether the sync is required with the local provider or
     *                   the cloud provider.
     */
    public void syncMediaInMediaSetForProvider(
            MediaInMediaSetSyncRequestParams requestParams, @SyncSource int syncSource) {
        final Data inputData =
                new Data(
                        Map.of(
                                SYNC_WORKER_INPUT_AUTHORITY, requestParams.getAuthority(),
                                SYNC_WORKER_INPUT_SYNC_SOURCE, syncSource,
                                SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID,
                                requestParams.getMediaSetPickerId()));
        final OneTimeWorkRequest syncRequest =
                buildOneTimeWorkerRequest(MediaInMediaSetsSyncWorker.class, inputData);

        // track the new request
        trackNewMediaInMediaSetSyncRequest(syncSource, syncRequest.getId());

        final String workName = syncSource == SYNC_LOCAL_ONLY
                ? IMMEDIATE_LOCAL_MEDIA_IN_MEDIA_SET_SYNC_WORK_NAME
                : IMMEDIATE_CLOUD_MEDIA_IN_MEDIA_SET_SYNC_WORK_NAME;
        // Enqueue local or cloud sync request
        try {
            final Operation enqueueOperation = mWorkManager.enqueueUniqueWork(
                    workName,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    syncRequest
            );

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (Exception e) {
            Log.e(TAG, "Could not enqueue expedited media in media set sync request", e);
            markMediaInMediaSetSyncAsComplete(syncSource, syncRequest.getId());
        }
    }

    @NonNull
    private OneTimeWorkRequest buildOneTimeWorkerRequest(
            @NonNull Class<? extends Worker> workerClass, @NonNull Data inputData) {
        return new OneTimeWorkRequest.Builder(workerClass)
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
    }

    @NonNull
    private PeriodicWorkRequest getPeriodicProactiveSyncRequest(@NonNull Data inputData) {
        return new PeriodicWorkRequest.Builder(
                ProactiveSyncWorker.class, SYNC_MEDIA_PERIODIC_WORK_INTERVAL, TimeUnit.HOURS)
                .setInputData(inputData)
                .setConstraints(getRequiresChargingAndIdleConstraints())
                .build();
    }

    @NonNull
    private PeriodicWorkRequest getPeriodicAlbumResetRequest(@NonNull Data inputData) {

        return new PeriodicWorkRequest.Builder(
                        MediaResetWorker.class,
                        RESET_ALBUM_MEDIA_PERIODIC_WORK_INTERVAL,
                        TimeUnit.HOURS)
                .setInputData(inputData)
                .setConstraints(getRequiresChargingAndIdleConstraints())
                .addTag(SYNC_WORKER_TAG_IS_PERIODIC)
                .build();
    }

    /**
     * @param inputData Input data required by the Worker.
     * @return A PeriodicWorkRequest for periodically clearing expired search suggestions from
     * the database.
     */
    @NonNull
    private PeriodicWorkRequest getPeriodicSearchSuggestionsResetRequest(@NonNull Data inputData) {

        return new PeriodicWorkRequest.Builder(
                SearchResetWorker.class,
                RESET_SEARCH_SUGGESTIONS_PERIODIC_WORK_INTERVAL,
                TimeUnit.DAYS)
                .setInputData(inputData)
                .setConstraints(getRequiresChargingAndIdleConstraints())
                .build();
    }

    /**
     * @param inputData Input data required by the Worker.
     * @return A OneTimeWorkRequest for clearing all search results cache after an initial delay.
     */
    @NonNull
    private OneTimeWorkRequest getDelayedSearchResetRequest(@NonNull Data inputData) {
        Constraints constraints =  new Constraints.Builder()
                .setRequiresDeviceIdle(true)
                .build();

        return new OneTimeWorkRequest
                .Builder(SearchResetWorker.class)
                .setConstraints(constraints)
                .setInitialDelay(SEARCH_RESULTS_RESET_DELAY, TimeUnit.MINUTES)
                .setInputData(inputData)
                .build();
    }

    @NonNull
    private OneTimeWorkRequest getOneTimeProactiveSyncRequest(@NonNull Data inputData) {
        Constraints constraints =  new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build();

        return new OneTimeWorkRequest.Builder(ProactiveSyncWorker.class)
                .setInputData(inputData)
                .setConstraints(constraints)
                .setInitialDelay(PROACTIVE_SYNC_DELAY_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    @NonNull
    private static Constraints getRequiresChargingAndIdleConstraints() {
        return new Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build();
    }

    @SyncSource
    private static int getSyncSource(boolean isLocal) {
        return isLocal
                ? SYNC_LOCAL_ONLY
                : SYNC_CLOUD_ONLY;
    }
}
