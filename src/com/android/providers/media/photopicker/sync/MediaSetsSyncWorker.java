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

package com.android.providers.media.photopicker.sync;

import static com.android.providers.media.photopicker.sync.PickerSyncManager.EXTRA_MIME_TYPES;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_CLOUD_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_AUTHORITY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_CATEGORY_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markMediaSetsSyncAsComplete;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.photopicker.util.exceptions.RequestObsoleteException;
import com.android.providers.media.photopicker.v2.PhotopickerSyncHelper;
import com.android.providers.media.photopicker.v2.PickerNotificationSender;
import com.android.providers.media.photopicker.v2.sqlite.MediaSetsDatabaseUtil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class MediaSetsSyncWorker extends Worker {

    private final String TAG = "MediaSetsSyncWorker";
    private final int INVALID_SYNC_SOURCE = -1;
    private final int SYNC_PAGE_COUNT = Integer.MAX_VALUE;
    private final String SYNC_COMPLETE_KEY = "SYNCED";
    private final int PAGE_SIZE = 500;
    private final Context mContext;
    private final CancellationSignal mCancellationSignal;
    private boolean mMarkedSyncWorkAsComplete = false;
    private final PhotopickerSyncHelper mPhotopickerSyncHelper;


    public MediaSetsSyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);

        mContext = context;
        mCancellationSignal = new CancellationSignal();
        mPhotopickerSyncHelper = new PhotopickerSyncHelper();
    }

    @NonNull
    @Override
    public ListenableWorker.Result doWork() {

        final int syncSource = getInputData().getInt(SYNC_WORKER_INPUT_SYNC_SOURCE,
                /* defaultValue */ INVALID_SYNC_SOURCE);
        final String categoryId = getInputData().getString(SYNC_WORKER_INPUT_CATEGORY_ID);
        String categoryAuthority = getInputData().getString(SYNC_WORKER_INPUT_AUTHORITY);
        final String[] mimeTypes = getInputData().getStringArray(EXTRA_MIME_TYPES);

        try {
            checkValidityOfWorkerInputParams(categoryId, syncSource, categoryAuthority);

            Log.i(TAG, "Fetching media sets from sync source "
                    + syncSource + " for " + categoryId);

            // Fail if the worker has stopped
            if (isStopped()) {
                throw new RequestObsoleteException("Work is stopped" + getId());
            }

            syncMediaSets(syncSource, categoryId, categoryAuthority, mimeTypes);

            Log.i(TAG, "Completed media sets sync from sync source" + syncSource
                    + " for categoryId " + categoryId);

            return ListenableWorker.Result.success();
        } catch (RuntimeException | RequestObsoleteException e) {
            Log.e(TAG, "Could not complete media sets sync from "
                            + syncSource + " with categoryId " + categoryId, e);
            return ListenableWorker.Result.failure();
        }
    }

    private void checkValidityOfWorkerInputParams(
            String categoryId, int syncSource, String categoryAuth)
            throws RequestObsoleteException {
        Objects.requireNonNull(categoryId);
        if (categoryId.isEmpty()) {
            Log.e(TAG, "Received empty category id to fetch media set data");
            throw new IllegalArgumentException("CategoryId was an empty string");
        }

        // SyncSource should either be cloud or local in order to fetch media sets
        if (syncSource != SYNC_LOCAL_ONLY && syncSource != SYNC_CLOUD_ONLY) {
            throw new IllegalArgumentException("Invalid media sets sync source " + syncSource);
        }

        Objects.requireNonNull(categoryAuth);
        checkIfCurrentCloudProviderAuthorityHasChanged(categoryAuth);

    }

    private void syncMediaSets(
            int syncSource, @NonNull String categoryId,
            @NonNull String categoryAuthority, @Nullable String[] mimeTypes)
            throws RequestObsoleteException, IllegalArgumentException, OperationCanceledException {

        List<String> mimeTypesList = mimeTypes == null || mimeTypes.length == 0 ? null
                : Arrays.asList(mimeTypes);
        final PickerSearchProviderClient searchClient =
                PickerSearchProviderClient.create(mContext, categoryAuthority);
        final Set<String> knownTokens = new HashSet<>();
        String nextPageToken = null;

        try {
            for (int currentIteration = 0; currentIteration < SYNC_PAGE_COUNT; currentIteration++) {
                checkIfWorkerHasStopped();
                checkIfCurrentCloudProviderAuthorityHasChanged(categoryAuthority);

                try (Cursor mediaSetsCursor = fetchMediaSetsFromCmp(
                        searchClient, categoryId, nextPageToken, mimeTypes, mCancellationSignal)) {
                    // Cache the retrieved media sets
                    int numberOfRowsInserted = MediaSetsDatabaseUtil.cacheMediaSets(
                            mPhotopickerSyncHelper.getDatabase(), mediaSetsCursor, categoryId,
                            categoryAuthority, mimeTypesList);
                    Log.i(TAG, "Cached " + numberOfRowsInserted + " media sets");

                    // Update the next page token
                    nextPageToken = getNextPageToken(mediaSetsCursor.getExtras());
                    if (nextPageToken.equals(SYNC_COMPLETE_KEY)) {
                        Log.d(TAG, "Number of media set results pages synced: "
                                + (currentIteration + 1));
                        break;
                    } else if (knownTokens.contains(nextPageToken)) {
                        Log.e(TAG, "Loop detected! CMP has sent the same page token twice: "
                                + nextPageToken);
                        break;
                    }
                    knownTokens.add(nextPageToken);

                    // Mark sync as complete
                    if (mMarkedSyncWorkAsComplete) {
                        // Notify the UI that a change has been made in the DB
                        if (numberOfRowsInserted > 0) {
                            PickerNotificationSender.notifyMediaSetsChange(mContext, categoryId);
                        }
                    } else {
                        markMediaSetsSyncAsComplete(syncSource, getId());
                        mMarkedSyncWorkAsComplete = true;
                    }
                }
            }
        } finally {
            if (!mMarkedSyncWorkAsComplete) {
                markMediaSetsSyncAsComplete(syncSource, getId());
            }
        }
    }

    private Cursor fetchMediaSetsFromCmp(
            PickerSearchProviderClient client,
            String categoryId,
            String nextPageToken,
            String[] mimeTypes,
            CancellationSignal cancellationSignal) throws OperationCanceledException {
        final Cursor cursor = client.fetchMediaSetsFromCmp(
                categoryId, nextPageToken, PAGE_SIZE, mimeTypes, cancellationSignal);

        if (cursor == null) {
            throw new IllegalStateException("Cursor returned from provider is null.");
        }
        return cursor;
    }

    @NonNull
    private String getNextPageToken(Bundle extras) {
        if (extras == null
                || extras.getString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN) == null) {
            return SYNC_COMPLETE_KEY;
        }
        return extras.getString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN);
    }

    private void checkIfWorkerHasStopped() throws RequestObsoleteException {
        if (isStopped()) {
            throw new RequestObsoleteException("MediaInMediaSets worker has stopped" + getId());
        }
    }

    private void checkIfCurrentCloudProviderAuthorityHasChanged(@NonNull String authority)
            throws RequestObsoleteException {
        if (mPhotopickerSyncHelper.isAuthorityLocal(authority)) {
            return;
        }
        final String currentCloudAuthority =
                mPhotopickerSyncHelper.getCurrentCloudProviderAuthority();
        if (!authority.equals(currentCloudAuthority)) {
            throw new RequestObsoleteException("Cloud provider authority has changed."
                    + " Sync will not be continued."
                    + " Current cloud provider authority: " + currentCloudAuthority
                    + " Cloud provider authority to sync with: " + authority);
        }
    }
}
