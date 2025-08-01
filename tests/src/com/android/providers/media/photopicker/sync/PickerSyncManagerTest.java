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

import static com.android.providers.media.photopicker.sync.PickerSyncManager.EXPIRED_SUGGESTIONS_RESET;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.EXTRA_MIME_TYPES;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.IMMEDIATE_CLOUD_MEDIA_IN_MEDIA_SET_SYNC_WORK_NAME;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.IMMEDIATE_CLOUD_SEARCH_SYNC_WORK_NAME;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SEARCH_RESULTS_FULL_CACHE_RESET;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SEARCH_PARTIAL_CACHE_RESET;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SEARCH_RESULTS_RESET_DELAY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SHOULD_SYNC_GRANTS;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_CLOUD_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_AND_CLOUD;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_AUTHORITY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_CATEGORY_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_RESET_TYPE;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SEARCH_REQUEST_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;
import static com.android.providers.media.util.BackgroundThreadUtils.waitForIdle;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkContinuation;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.android.providers.media.TestConfigStore;
import com.android.providers.media.cloudproviders.SearchProvider;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerSyncRequestExtras;
import com.android.providers.media.photopicker.v2.model.MediaInMediaSetSyncRequestParams;
import com.android.providers.media.photopicker.v2.model.MediaSetsSyncRequestParams;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PickerSyncManagerTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private PickerSyncManager mPickerSyncManager;
    private TestConfigStore mConfigStore;
    @Mock
    private WorkManager mMockWorkManager;
    @Mock
    private Operation mMockOperation;
    @Mock
    private WorkContinuation mMockWorkContinuation;
    @Mock
    private ListenableFuture<Operation.State.SUCCESS> mMockFuture;
    @Mock
    private Context mMockContext;
    @Mock
    private Resources mResources;
    @Captor
    ArgumentCaptor<PeriodicWorkRequest> mPeriodicWorkRequestArgumentCaptor;
    @Captor
    ArgumentCaptor<OneTimeWorkRequest> mOneTimeWorkRequestArgumentCaptor;
    @Captor
    ArgumentCaptor<List<OneTimeWorkRequest>> mOneTimeWorkRequestListArgumentCaptor;

    @Before
    public void setUp() {
        initMocks(this);
        doReturn(mResources).when(mMockContext).getResources();
        mConfigStore = new TestConfigStore();
        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(
                "com.hooli.super.awesome.cloudpicker");
        final SettableFuture<List<WorkInfo>> listenableFuture = SettableFuture.create();
        listenableFuture.set(List.of());
        doReturn(listenableFuture).when(mMockWorkManager).getWorkInfosByTag(anyString());
    }


    @Test
    public void testScheduleEndlessWorker() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ true);

        // The third call here comes from the EndlessWorker
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(),
                        any(),
                        mOneTimeWorkRequestArgumentCaptor.capture());

        final OneTimeWorkRequest workRequest = mOneTimeWorkRequestArgumentCaptor.getValue();
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(EndlessWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isFalse();
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    public void testSchedulePeriodicSyncs() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ true);

        verify(mMockWorkManager, times(2))
                .enqueueUniquePeriodicWork(anyString(),
                        any(),
                        mPeriodicWorkRequestArgumentCaptor.capture());

        final PeriodicWorkRequest periodicWorkRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(0);
        assertThat(periodicWorkRequest.getWorkSpec().workerClassName)
                .isEqualTo(ProactiveSyncWorker.class.getName());
        assertThat(periodicWorkRequest.getWorkSpec().expedited).isFalse();
        assertThat(periodicWorkRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().id).isNotNull();
        assertThat(periodicWorkRequest.getWorkSpec().constraints.requiresCharging()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().constraints.requiresDeviceIdle()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);

        final PeriodicWorkRequest periodicResetRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(1);
        assertThat(periodicResetRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaResetWorker.class.getName());
        assertThat(periodicResetRequest.getWorkSpec().expedited).isFalse();
        assertThat(periodicResetRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().id).isNotNull();
        assertThat(periodicResetRequest.getWorkSpec().constraints.requiresCharging()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().constraints.requiresDeviceIdle()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    public void testSchedulePeriodicSyncsWithSearchEnabled() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ true);

        verify(mMockWorkManager, times(3))
                .enqueueUniquePeriodicWork(anyString(),
                        any(),
                        mPeriodicWorkRequestArgumentCaptor.capture());

        final PeriodicWorkRequest periodicWorkRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(0);
        assertThat(periodicWorkRequest.getWorkSpec().workerClassName)
                .isEqualTo(ProactiveSyncWorker.class.getName());
        assertThat(periodicWorkRequest.getWorkSpec().expedited).isFalse();
        assertThat(periodicWorkRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().id).isNotNull();
        assertThat(periodicWorkRequest.getWorkSpec().constraints.requiresCharging()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().constraints.requiresDeviceIdle()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);

        final PeriodicWorkRequest periodicResetRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(1);
        assertThat(periodicResetRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaResetWorker.class.getName());
        assertThat(periodicResetRequest.getWorkSpec().expedited).isFalse();
        assertThat(periodicResetRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().id).isNotNull();
        assertThat(periodicResetRequest.getWorkSpec().constraints.requiresCharging()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().constraints.requiresDeviceIdle()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);

        final PeriodicWorkRequest searchSuggestionsResetRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(2);
        assertThat(searchSuggestionsResetRequest.getWorkSpec().workerClassName)
                .isEqualTo(SearchResetWorker.class.getName());
        assertThat(searchSuggestionsResetRequest.getWorkSpec().expedited).isFalse();
        assertThat(searchSuggestionsResetRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(searchSuggestionsResetRequest.getWorkSpec().id).isNotNull();
        assertThat(searchSuggestionsResetRequest.getWorkSpec()
                .constraints.requiresCharging()).isTrue();
        assertThat(searchSuggestionsResetRequest.getWorkSpec()
                .constraints.requiresDeviceIdle()).isTrue();
        assertThat(searchSuggestionsResetRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_RESET_TYPE, -1))
                .isEqualTo(EXPIRED_SUGGESTIONS_RESET);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    public void testPeriodicWorkIsScheduledOnDeviceConfigChanges() {

        mConfigStore.disableCloudMediaFeature();

        setupPickerSyncManager(true);

        // Ensure no syncs have been scheduled yet.
        verify(mMockWorkManager, times(0))
                .enqueueUniquePeriodicWork(anyString(),
                        any(),
                        mPeriodicWorkRequestArgumentCaptor.capture());

        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(
                "com.hooli.some.cloud.provider");

        waitForIdle();

        // Ensure the media and album reset syncs are now scheduled.
        verify(mMockWorkManager, atLeast(2))
                .enqueueUniquePeriodicWork(anyString(),
                        any(),
                        mPeriodicWorkRequestArgumentCaptor.capture());

        final PeriodicWorkRequest periodicWorkRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(0);
        assertThat(periodicWorkRequest.getWorkSpec().workerClassName)
                .isEqualTo(ProactiveSyncWorker.class.getName());
        assertThat(periodicWorkRequest.getWorkSpec().expedited).isFalse();
        assertThat(periodicWorkRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().id).isNotNull();
        assertThat(periodicWorkRequest.getWorkSpec().constraints.requiresCharging()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().constraints.requiresDeviceIdle()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);

        final PeriodicWorkRequest periodicResetRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(1);
        assertThat(periodicResetRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaResetWorker.class.getName());
        assertThat(periodicResetRequest.getWorkSpec().expedited).isFalse();
        assertThat(periodicResetRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().id).isNotNull();
        assertThat(periodicResetRequest.getWorkSpec().constraints.requiresCharging()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().constraints.requiresDeviceIdle()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);

        clearInvocations(mMockWorkManager);

        mConfigStore.disableCloudMediaFeature();
        waitForIdle();

        // There should be 3 invocations, one for cancelling proactive media syncs,
        // the other for albums reset and search reset syncs.
        verify(mMockWorkManager, times(3)).cancelUniqueWork(anyString());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    public void testOnDeviceConfigChangesWithSearchEnabled() {

        mConfigStore.disableCloudMediaFeature();

        setupPickerSyncManager(true);

        // Ensure only search sync is scheduled
        verify(mMockWorkManager, times(1))
                .enqueueUniquePeriodicWork(anyString(),
                        any(),
                        mPeriodicWorkRequestArgumentCaptor.capture());
        clearInvocations(mMockWorkManager);

        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(
                "com.hooli.some.cloud.provider");

        waitForIdle();

        // Ensure the media and album reset syncs are now scheduled.
        verify(mMockWorkManager, times(3))
                .enqueueUniquePeriodicWork(anyString(),
                        any(),
                        mPeriodicWorkRequestArgumentCaptor.capture());

        final PeriodicWorkRequest periodicWorkRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(1);
        assertThat(periodicWorkRequest.getWorkSpec().workerClassName)
                .isEqualTo(ProactiveSyncWorker.class.getName());
        assertThat(periodicWorkRequest.getWorkSpec().expedited).isFalse();
        assertThat(periodicWorkRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().id).isNotNull();
        assertThat(periodicWorkRequest.getWorkSpec().constraints.requiresCharging()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().constraints.requiresDeviceIdle()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);

        final PeriodicWorkRequest periodicResetRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(2);
        assertThat(periodicResetRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaResetWorker.class.getName());
        assertThat(periodicResetRequest.getWorkSpec().expedited).isFalse();
        assertThat(periodicResetRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().id).isNotNull();
        assertThat(periodicResetRequest.getWorkSpec().constraints.requiresCharging()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().constraints.requiresDeviceIdle()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);

        clearInvocations(mMockWorkManager);

        mConfigStore.disableCloudMediaFeature();
        waitForIdle();

        // There should be 2 invocations, one for cancelling proactive media syncs,
        // the other for albums reset.
        verify(mMockWorkManager, times(2)).cancelUniqueWork(anyString());
    }

    @Test
    public void testAdhocProactiveSyncLocalOnly() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncMediaProactively(/* localOnly */ true);
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(),
                        any(),
                        mOneTimeWorkRequestArgumentCaptor.capture());

        final OneTimeWorkRequest workRequest = mOneTimeWorkRequestArgumentCaptor.getValue();
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(ProactiveSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isFalse();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isTrue();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);
        assertThat(workRequest.getWorkSpec().initialDelay)
                .isEqualTo(PickerSyncManager.PROACTIVE_SYNC_DELAY_MS);
    }

    @Test
    public void testAdhocProactiveSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncMediaProactively(/* localOnly */ false);
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(),
                        any(),
                        mOneTimeWorkRequestArgumentCaptor.capture());

        final OneTimeWorkRequest workRequest = mOneTimeWorkRequestArgumentCaptor.getValue();
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(ProactiveSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isFalse();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isTrue();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);
        assertThat(workRequest.getWorkSpec().initialDelay)
                .isEqualTo(PickerSyncManager.PROACTIVE_SYNC_DELAY_MS);
    }

    @Test
    public void testImmediateGrantsSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mConfigStore.setIsModernPickerEnabled(true);
        mPickerSyncManager.syncMediaImmediately(new PickerSyncRequestExtras(/* albumId */null,
                /* albumAuthority */ null, /* initLocalDataOnly */ true,
                /* callingPackageUid */ 0, /* shouldSyncGrants */ true, null),
                mConfigStore);
        verify(mMockWorkManager, times(2))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final List<OneTimeWorkRequest> workRequestList =
                mOneTimeWorkRequestArgumentCaptor.getAllValues();
        assertThat(workRequestList.size()).isEqualTo(2);

        // work request 0 is for grants sync.
        WorkRequest workRequest = workRequestList.get(0);
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateGrantsSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input
                .getInt(Intent.EXTRA_UID, -1))
                .isEqualTo(0);
        assertThat(workRequest.getWorkSpec().input
                .getBoolean(SHOULD_SYNC_GRANTS, false))
                .isEqualTo(true);
    }

    @Test
    public void testImmediateLocalSync() {
        mConfigStore.setIsModernPickerEnabled(true);
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncMediaImmediately(new PickerSyncRequestExtras(/* albumId */null,
                /* albumAuthority */ null, /* initLocalDataOnly */ true,
                /* callingPackageUid */ 0, /* shouldSyncGrants */ false, null),
                mConfigStore);
        verify(mMockWorkManager, times(2))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final List<OneTimeWorkRequest> workRequestList =
                mOneTimeWorkRequestArgumentCaptor.getAllValues();
        assertThat(workRequestList.size()).isEqualTo(2);

        // work request 0 is for grants sync, so use request number 1 for local syncs.
        WorkRequest workRequest = workRequestList.get(1);
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);
    }

    @Test
    public void testImmediateCloudSync() {
        mConfigStore.setIsModernPickerEnabled(true);
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncMediaImmediately(new PickerSyncRequestExtras(/* albumId */null,
                /* albumAuthority */ null, /* initLocalDataOnly */ false,
                /* callingPackageUid */ 0, /* shouldSyncGrants */ false, null),
                mConfigStore);
        verify(mMockWorkManager, times(3))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final List<OneTimeWorkRequest> workRequestList =
                mOneTimeWorkRequestArgumentCaptor.getAllValues();
        assertThat(workRequestList.size()).isEqualTo(3);

        // work request 0 is for grants sync, 1 for local syncs and 2 for cloud syncs.

        WorkRequest localWorkRequest = workRequestList.get(1);
        assertThat(localWorkRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateSyncWorker.class.getName());
        assertThat(localWorkRequest.getWorkSpec().expedited).isTrue();
        assertThat(localWorkRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(localWorkRequest.getWorkSpec().id).isNotNull();
        assertThat(localWorkRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(localWorkRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);

        WorkRequest cloudWorkRequest = workRequestList.get(2);
        assertThat(cloudWorkRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateSyncWorker.class.getName());
        assertThat(cloudWorkRequest.getWorkSpec().expedited).isTrue();
        assertThat(cloudWorkRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(cloudWorkRequest.getWorkSpec().id).isNotNull();
        assertThat(cloudWorkRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(cloudWorkRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_CLOUD_ONLY);
    }

    @Test
    public void testImmediateLocalAlbumSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncAlbumMediaForProviderImmediately(
                "Not_null", PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY,
                /* isLocal= */ true);
        verify(mMockWorkManager, times(1))
                .beginUniqueWork(
                        anyString(),
                        any(ExistingWorkPolicy.class),
                        mOneTimeWorkRequestListArgumentCaptor.capture());
        verify(mMockWorkContinuation, times(1))
                .then(mOneTimeWorkRequestListArgumentCaptor.capture());
        verify(mMockWorkContinuation).enqueue();

        final OneTimeWorkRequest resetRequest =
                mOneTimeWorkRequestListArgumentCaptor.getAllValues().get(0).get(0);
        assertThat(resetRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaResetWorker.class.getName());
        assertThat(resetRequest.getWorkSpec().expedited).isTrue();
        assertThat(resetRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(resetRequest.getWorkSpec().id).isNotNull();
        assertThat(resetRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(resetRequest.getWorkSpec().input.getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);

        final OneTimeWorkRequest workRequest =
                mOneTimeWorkRequestListArgumentCaptor.getAllValues().get(1).get(0);
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateAlbumSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input.getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);
    }

    @Test
    public void testImmediateCloudAlbumSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncAlbumMediaForProviderImmediately(
                "Not_null", "com.hooli.cloudpicker", /* isLocal= */ false);
        verify(mMockWorkManager, times(1))
                .beginUniqueWork(
                        anyString(),
                        any(ExistingWorkPolicy.class),
                        mOneTimeWorkRequestListArgumentCaptor.capture());
        verify(mMockWorkContinuation, times(1))
                .then(mOneTimeWorkRequestListArgumentCaptor.capture());
        verify(mMockWorkContinuation).enqueue();

        final OneTimeWorkRequest resetRequest =
                mOneTimeWorkRequestListArgumentCaptor.getAllValues().get(0).get(0);
        assertThat(resetRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaResetWorker.class.getName());
        assertThat(resetRequest.getWorkSpec().expedited).isTrue();
        assertThat(resetRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(resetRequest.getWorkSpec().id).isNotNull();
        assertThat(resetRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(resetRequest.getWorkSpec().input.getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_CLOUD_ONLY);

        final OneTimeWorkRequest workRequest =
                mOneTimeWorkRequestListArgumentCaptor.getAllValues().get(1).get(0);
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateAlbumSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input.getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_CLOUD_ONLY);
    }

    @Test
    public void testSearchResultsLocalSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncSearchResultsForProvider(
                /* searchRequestId */ 10,
                SYNC_LOCAL_ONLY,
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY
        );
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final List<OneTimeWorkRequest> workRequestList =
                mOneTimeWorkRequestArgumentCaptor.getAllValues();
        assertThat(workRequestList.size()).isEqualTo(1);

        WorkRequest workRequest = workRequestList.get(0);
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(SearchResultsSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SEARCH_REQUEST_ID, -1))
                .isEqualTo(10);
    }

    @Test
    public void testExistingSearchResultsSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        final int searchRequestId = 10;
        final String authority = PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;

        final SettableFuture<List<WorkInfo>> listenableFuture = SettableFuture.create();
        final WorkInfo workInfo = new WorkInfo(
                UUID.randomUUID(), WorkInfo.State.RUNNING, new HashSet<>());
        final String tag = String.format(Locale.ROOT, "%s-%s-%s",
                IMMEDIATE_CLOUD_SEARCH_SYNC_WORK_NAME, authority, searchRequestId);
        listenableFuture.set(List.of(workInfo));
        doReturn(listenableFuture).when(mMockWorkManager).getWorkInfosByTag(eq(tag));

        mPickerSyncManager.syncSearchResultsForProvider(
                searchRequestId,
                SYNC_CLOUD_ONLY,
                authority
        );
        verify(mMockWorkManager, times(0))
                .enqueueUniqueWork(anyString(), any(), any(OneTimeWorkRequest.class));
    }

    @Test
    public void testExistingMediaSetContentsSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        final int pickerMediaSetId = 10;
        final String authority = PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;

        final SettableFuture<List<WorkInfo>> listenableFuture = SettableFuture.create();
        final WorkInfo workInfo = new WorkInfo(
                UUID.randomUUID(), WorkInfo.State.RUNNING, new HashSet<>());
        final String tag = String.format(Locale.ROOT, "%s-%s-%s",
                IMMEDIATE_CLOUD_MEDIA_IN_MEDIA_SET_SYNC_WORK_NAME, authority, pickerMediaSetId);
        listenableFuture.set(List.of(workInfo));
        doReturn(listenableFuture).when(mMockWorkManager).getWorkInfosByTag(eq(tag));

        Bundle extras = new Bundle();
        extras.putString(MediaInMediaSetSyncRequestParams.KEY_PARENT_MEDIA_SET_AUTHORITY,
                authority);
        extras.putLong(MediaInMediaSetSyncRequestParams.KEY_PARENT_MEDIA_SET_PICKER_ID,
                pickerMediaSetId);
        extras.putStringArrayList("providers", new ArrayList<>(List.of(authority)));

        MediaInMediaSetSyncRequestParams requestParams = new MediaInMediaSetSyncRequestParams(
                extras);
        mPickerSyncManager.syncMediaInMediaSetForProvider(
                requestParams,
                SYNC_CLOUD_ONLY
        );

        verify(mMockWorkManager, times(0))
                .enqueueUniqueWork(anyString(), any(), any(OneTimeWorkRequest.class));
    }

    @Test
    public void testSearchResultsSyncIsScheduled() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        final int searchRequestId = 10;
        final String authority = PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;

        final SettableFuture<List<WorkInfo>> listenableFuture = SettableFuture.create();
        final WorkInfo workInfo = new WorkInfo(
                UUID.randomUUID(), WorkInfo.State.RUNNING, new HashSet<>());
        final String tag = String.format(Locale.ROOT, "%s-%s-%s",
                IMMEDIATE_CLOUD_SEARCH_SYNC_WORK_NAME, authority, searchRequestId + 1);
        listenableFuture.set(List.of(workInfo));
        doReturn(listenableFuture).when(mMockWorkManager).getWorkInfosByTag(eq(tag));

        mPickerSyncManager.syncSearchResultsForProvider(
                searchRequestId,
                SYNC_CLOUD_ONLY,
                authority
        );
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(), any(), any(OneTimeWorkRequest.class));
    }

    @Test
    public void testMediaSetContentsSyncIsScheduled() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        final int pickerMediaSetId = 10;
        final String authority = PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;

        final SettableFuture<List<WorkInfo>> listenableFuture = SettableFuture.create();
        final WorkInfo workInfo = new WorkInfo(
                UUID.randomUUID(), WorkInfo.State.RUNNING, new HashSet<>());
        final String tag = String.format(Locale.ROOT, "%s-%s-%s",
                IMMEDIATE_CLOUD_MEDIA_IN_MEDIA_SET_SYNC_WORK_NAME,
                authority, pickerMediaSetId + 1);
        listenableFuture.set(List.of(workInfo));
        doReturn(listenableFuture).when(mMockWorkManager).getWorkInfosByTag(eq(tag));

        Bundle extras = new Bundle();
        extras.putString(MediaInMediaSetSyncRequestParams.KEY_PARENT_MEDIA_SET_AUTHORITY,
                authority);
        extras.putLong(MediaInMediaSetSyncRequestParams.KEY_PARENT_MEDIA_SET_PICKER_ID,
                pickerMediaSetId);
        extras.putStringArrayList("providers", new ArrayList<>(List.of(authority)));

        MediaInMediaSetSyncRequestParams requestParams = new MediaInMediaSetSyncRequestParams(
                extras);
        mPickerSyncManager.syncMediaInMediaSetForProvider(
                requestParams,
                SYNC_CLOUD_ONLY
        );

        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(), any(), any(OneTimeWorkRequest.class));
    }

    @Test
    public void testSearchResultsCloudSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncSearchResultsForProvider(
                /* searchRequestId */ 10,
                SYNC_CLOUD_ONLY,
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY
        );
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final List<OneTimeWorkRequest> workRequestList =
                mOneTimeWorkRequestArgumentCaptor.getAllValues();
        assertThat(workRequestList.size()).isEqualTo(1);

        WorkRequest workRequest = workRequestList.get(0);
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(SearchResultsSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_CLOUD_ONLY);
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SEARCH_REQUEST_ID, -1))
                .isEqualTo(10);
    }

    @Test
    public void testMediaSetsSyncLocalProvider() {
        setupPickerSyncManager(/*schedulePeriodicSyncs*/ false);

        String categoryId = "id";
        String[] mimeTypes = new String[] { "image/*" };
        Bundle extras = new Bundle();
        extras.putString(MediaSetsSyncRequestParams.KEY_PARENT_CATEGORY_AUTHORITY,
                SearchProvider.AUTHORITY);
        extras.putStringArrayList(MediaSetsSyncRequestParams.KEY_MIME_TYPES,
                new ArrayList<String>(Arrays.asList(mimeTypes)));
        extras.putString(MediaSetsSyncRequestParams.KEY_PARENT_CATEGORY_ID, categoryId);
        extras.putStringArrayList("providers", new ArrayList<>(List.of(
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY)));

        MediaSetsSyncRequestParams requestParams = new MediaSetsSyncRequestParams(extras);

        mPickerSyncManager.syncMediaSetsForProvider(requestParams, SYNC_LOCAL_ONLY);
        verify(mMockWorkManager, times(1))
                .beginUniqueWork(
                        anyString(),
                        any(ExistingWorkPolicy.class),
                        mOneTimeWorkRequestListArgumentCaptor.capture());
        verify(mMockWorkContinuation, times(1))
                .then(mOneTimeWorkRequestListArgumentCaptor.capture());
        verify(mMockWorkContinuation).enqueue();

        final List<List<OneTimeWorkRequest>> workRequestList =
                mOneTimeWorkRequestListArgumentCaptor.getAllValues();
        assertThat(workRequestList.size()).isEqualTo(2);

        WorkRequest resetRequest = workRequestList.get(0).get(0);
        assertThat(resetRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaSetsResetWorker.class.getName());
        assertThat(resetRequest.getWorkSpec().expedited).isTrue();
        assertThat(resetRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(resetRequest.getWorkSpec().id).isNotNull();
        assertThat(resetRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(resetRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);
        assertThat(resetRequest.getWorkSpec().input
                .getString(SYNC_WORKER_INPUT_CATEGORY_ID))
                .isEqualTo(categoryId);
        assertThat(resetRequest.getWorkSpec().input
                .getString(SYNC_WORKER_INPUT_AUTHORITY))
                .isEqualTo(SearchProvider.AUTHORITY);

        WorkRequest syncRequest = workRequestList.get(1).get(0);
        assertThat(syncRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaSetsSyncWorker.class.getName());
        assertThat(syncRequest.getWorkSpec().expedited).isTrue();
        assertThat(syncRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(syncRequest.getWorkSpec().id).isNotNull();
        assertThat(syncRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(syncRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);
        assertThat(syncRequest.getWorkSpec().input
                .getString(SYNC_WORKER_INPUT_CATEGORY_ID))
                .isEqualTo(categoryId);
        assertThat(syncRequest.getWorkSpec().input
                .getString(SYNC_WORKER_INPUT_AUTHORITY))
                .isEqualTo(SearchProvider.AUTHORITY);
        assertThat(syncRequest.getWorkSpec().input
                .getStringArray(EXTRA_MIME_TYPES))
                .isEqualTo(mimeTypes);
    }

    @Test
    public void testMediaSetsSyncCloudProvider() {
        setupPickerSyncManager(/*schedulePeriodicSyncs*/ false);

        String categoryId = "id";
        String[] mimeTypes = new String[] { "image/*" };
        Bundle extras = new Bundle();
        extras.putString(MediaSetsSyncRequestParams.KEY_PARENT_CATEGORY_AUTHORITY,
                SearchProvider.AUTHORITY);
        extras.putStringArrayList(MediaSetsSyncRequestParams.KEY_MIME_TYPES,
                new ArrayList<String>(Arrays.asList(mimeTypes)));
        extras.putString(MediaSetsSyncRequestParams.KEY_PARENT_CATEGORY_ID, categoryId);
        extras.putStringArrayList("providers", new ArrayList<>(List.of(
                SearchProvider.AUTHORITY)));

        MediaSetsSyncRequestParams requestParams = new MediaSetsSyncRequestParams(extras);

        mPickerSyncManager.syncMediaSetsForProvider(requestParams, SYNC_CLOUD_ONLY);
        verify(mMockWorkManager, times(1))
                .beginUniqueWork(
                        anyString(),
                        any(ExistingWorkPolicy.class),
                        mOneTimeWorkRequestListArgumentCaptor.capture());
        verify(mMockWorkContinuation, times(1))
                .then(mOneTimeWorkRequestListArgumentCaptor.capture());
        verify(mMockWorkContinuation).enqueue();

        final List<List<OneTimeWorkRequest>> workRequestList =
                mOneTimeWorkRequestListArgumentCaptor.getAllValues();
        assertThat(workRequestList.size()).isEqualTo(2);

        WorkRequest resetRequest = workRequestList.get(0).get(0);
        assertThat(resetRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaSetsResetWorker.class.getName());
        assertThat(resetRequest.getWorkSpec().expedited).isTrue();
        assertThat(resetRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(resetRequest.getWorkSpec().id).isNotNull();
        assertThat(resetRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(resetRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_CLOUD_ONLY);
        assertThat(resetRequest.getWorkSpec().input
                .getString(SYNC_WORKER_INPUT_CATEGORY_ID))
                .isEqualTo(categoryId);
        assertThat(resetRequest.getWorkSpec().input
                .getString(SYNC_WORKER_INPUT_AUTHORITY))
                .isEqualTo(SearchProvider.AUTHORITY);

        WorkRequest syncRequest = workRequestList.get(1).get(0);
        assertThat(syncRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaSetsSyncWorker.class.getName());
        assertThat(syncRequest.getWorkSpec().expedited).isTrue();
        assertThat(syncRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(syncRequest.getWorkSpec().id).isNotNull();
        assertThat(syncRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(syncRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_CLOUD_ONLY);
        assertThat(syncRequest.getWorkSpec().input
                .getString(SYNC_WORKER_INPUT_CATEGORY_ID))
                .isEqualTo(categoryId);
        assertThat(syncRequest.getWorkSpec().input
                .getString(SYNC_WORKER_INPUT_AUTHORITY))
                .isEqualTo(SearchProvider.AUTHORITY);
        assertThat(syncRequest.getWorkSpec().input
                .getStringArray(EXTRA_MIME_TYPES))
                .isEqualTo(mimeTypes);
    }

    @Test
    public void testMediaInMediaSetSyncLocalProvider() {
        setupPickerSyncManager(/*schedulePeriodicSyncs*/ false);

        Long mediaSetPickerId = 1L;
        Bundle extras = new Bundle();
        extras.putString(MediaInMediaSetSyncRequestParams.KEY_PARENT_MEDIA_SET_AUTHORITY,
                SearchProvider.AUTHORITY);
        extras.putLong(MediaInMediaSetSyncRequestParams.KEY_PARENT_MEDIA_SET_PICKER_ID,
                mediaSetPickerId);
        extras.putStringArrayList("providers", new ArrayList<>(List.of(
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY)));

        MediaInMediaSetSyncRequestParams requestParams = new MediaInMediaSetSyncRequestParams(
                extras);

        mPickerSyncManager.syncMediaInMediaSetForProvider(requestParams, SYNC_LOCAL_ONLY);
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final List<OneTimeWorkRequest> workRequestList =
                mOneTimeWorkRequestArgumentCaptor.getAllValues();
        assertThat(workRequestList.size()).isEqualTo(1);

        WorkRequest workRequest = workRequestList.get(0);
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaInMediaSetsSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);
        assertThat(workRequest.getWorkSpec().input
                .getLong(SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID, -1))
                .isEqualTo(mediaSetPickerId);
        assertThat(workRequest.getWorkSpec().input
                .getString(SYNC_WORKER_INPUT_AUTHORITY))
                .isEqualTo(SearchProvider.AUTHORITY);
    }

    @Test
    public void testMediaInMediaSetSyncCloudProvider() {
        setupPickerSyncManager(/*schedulePeriodicSyncs*/ false);

        Long mediaSetPickerId = 1L;
        Bundle extras = new Bundle();
        extras.putString(
                MediaInMediaSetSyncRequestParams.KEY_PARENT_MEDIA_SET_AUTHORITY,
                SearchProvider.AUTHORITY);
        extras.putLong(
                MediaInMediaSetSyncRequestParams.KEY_PARENT_MEDIA_SET_PICKER_ID,
                mediaSetPickerId);
        extras.putStringArrayList("providers", new ArrayList<>(List.of(
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY)));

        MediaInMediaSetSyncRequestParams requestParams = new MediaInMediaSetSyncRequestParams(
                extras);

        mPickerSyncManager.syncMediaInMediaSetForProvider(requestParams, SYNC_CLOUD_ONLY);
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final List<OneTimeWorkRequest> workRequestList =
                mOneTimeWorkRequestArgumentCaptor.getAllValues();
        assertThat(workRequestList.size()).isEqualTo(1);

        WorkRequest workRequest = workRequestList.get(0);
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaInMediaSetsSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_CLOUD_ONLY);
        assertThat(workRequest.getWorkSpec().input
                .getLong(SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID, -1))
                .isEqualTo(mediaSetPickerId);
        assertThat(workRequest.getWorkSpec().input
                .getString(SYNC_WORKER_INPUT_AUTHORITY))
                .isEqualTo(SearchProvider.AUTHORITY);
    }

    @Test
    public void testResetCloudSearchResults() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.resetCloudSearchCache(null);
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(),
                        any(),
                        mOneTimeWorkRequestArgumentCaptor.capture());

        final OneTimeWorkRequest workRequest = mOneTimeWorkRequestArgumentCaptor.getValue();
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(SearchResetWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_CLOUD_ONLY);
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_RESET_TYPE, -1))
                .isEqualTo(SEARCH_PARTIAL_CACHE_RESET);
    }

    @Test
    public void testDelayedResetSearchCache() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.delayedResetSearchCache();
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(),
                        any(),
                        mOneTimeWorkRequestArgumentCaptor.capture());

        final OneTimeWorkRequest workRequest = mOneTimeWorkRequestArgumentCaptor.getValue();
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(SearchResetWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isFalse();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_RESET_TYPE, -1))
                .isEqualTo(SEARCH_RESULTS_FULL_CACHE_RESET);
        assertThat(workRequest.getWorkSpec().initialDelay)
                .isEqualTo(TimeUnit.MINUTES.toMillis(SEARCH_RESULTS_RESET_DELAY));
        assertThat(workRequest.getWorkSpec().constraints.requiresDeviceIdle())
                .isTrue();

    }

    @Test
    public void testScheduleClearExpiredSuggestions() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.schedulePeriodicSearchSuggestionsReset();
        verify(mMockWorkManager, times(1))
                .enqueueUniquePeriodicWork(anyString(),
                        any(),
                        mPeriodicWorkRequestArgumentCaptor.capture());

        final PeriodicWorkRequest workRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(0);
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(SearchResetWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isFalse();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_RESET_TYPE, -1))
                .isEqualTo(EXPIRED_SUGGESTIONS_RESET);
        assertThat(workRequest.getWorkSpec().constraints.requiresCharging()).isTrue();
        assertThat(workRequest.getWorkSpec().constraints.requiresDeviceIdle()).isTrue();
    }

    private void setupPickerSyncManager(boolean schedulePeriodicSyncs) {
        doReturn(mMockOperation).when(mMockWorkManager)
                .enqueueUniquePeriodicWork(anyString(),
                        any(ExistingPeriodicWorkPolicy.class),
                        any(PeriodicWorkRequest.class));
        doReturn(mMockOperation).when(mMockWorkManager)
                .enqueueUniqueWork(anyString(),
                        any(ExistingWorkPolicy.class),
                        any(OneTimeWorkRequest.class));
        doReturn(mMockWorkContinuation)
                .when(mMockWorkManager)
                .beginUniqueWork(
                        anyString(), any(ExistingWorkPolicy.class), any(List.class));
        // Handle .then chaining
        doReturn(mMockWorkContinuation)
                .when(mMockWorkContinuation)
                .then(any(List.class));
        doReturn(mMockOperation).when(mMockWorkContinuation).enqueue();
        doReturn(mMockFuture).when(mMockOperation).getResult();

        mPickerSyncManager = new PickerSyncManager(mMockWorkManager, mMockContext);
        if (schedulePeriodicSyncs) {
            mPickerSyncManager.schedulePeriodicSync(
                    mConfigStore, /* periodicSyncInitialDelay */ 0L);
        }
        waitForIdle();
    }
}
