/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.photopicker;

import static android.content.ContentResolver.EXTRA_HONORED_ARGS;
import static android.provider.CloudMediaProviderContract.EXTRA_ALBUM_ID;
import static android.provider.CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID;
import static android.provider.CloudMediaProviderContract.EXTRA_PAGE_SIZE;
import static android.provider.CloudMediaProviderContract.EXTRA_PAGE_TOKEN;
import static android.provider.CloudMediaProviderContract.EXTRA_SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_CONFIGURATION_INTENT;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_NAME;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo.MEDIA_COLLECTION_ID;
import static android.provider.MediaStore.AUTHORITY;
import static android.provider.MediaStore.MY_UID;
import static android.provider.MediaStore.PER_USER_RANGE;

import static com.android.providers.media.PickerUriResolver.INIT_PATH;
import static com.android.providers.media.PickerUriResolver.PICKER_INTERNAL_URI;
import static com.android.providers.media.PickerUriResolver.REFRESH_UI_PICKER_INTERNAL_OBSERVABLE_URI;
import static com.android.providers.media.PickerUriResolver.getDeletedMediaUri;
import static com.android.providers.media.PickerUriResolver.getMediaCollectionInfoUri;
import static com.android.providers.media.PickerUriResolver.getMediaUri;
import static com.android.providers.media.photopicker.NotificationContentObserver.ALBUM_CONTENT;
import static com.android.providers.media.photopicker.NotificationContentObserver.MEDIA;
import static com.android.providers.media.photopicker.NotificationContentObserver.UPDATE;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorString;

import android.annotation.IntDef;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.RemoteException;
import android.os.Trace;
import android.os.storage.StorageManager;
import android.provider.CloudMediaProvider;
import android.provider.CloudMediaProviderContract;
import android.provider.CloudMediaProviderContract.MediaColumns;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.InstanceId;
import com.android.modules.utils.BackgroundThread;
import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.ConfigStore;
import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.CloudProviderInfo;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.metrics.NonUiEventLogger;
import com.android.providers.media.photopicker.sync.CloseableReentrantLock;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;
import com.android.providers.media.photopicker.util.CloudProviderUtils;
import com.android.providers.media.photopicker.util.exceptions.RequestObsoleteException;
import com.android.providers.media.photopicker.util.exceptions.UnableToAcquireLockException;
import com.android.providers.media.photopicker.util.exceptions.WorkCancelledException;
import com.android.providers.media.photopicker.v2.PickerNotificationSender;
import com.android.providers.media.photopicker.v2.model.ProviderCollectionInfo;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Syncs the local and currently enabled cloud {@link CloudMediaProvider} instances on the device
 * into the picker db.
 */
public class PickerSyncController {

    public static final ReentrantLock sIdleMaintenanceSyncLock = new ReentrantLock();
    private static final String TAG = "PickerSyncController";
    private static final boolean DEBUG = false;

    private static final String PREFS_KEY_CLOUD_PROVIDER_AUTHORITY = "cloud_provider_authority";
    private static final String PREFS_KEY_CLOUD_PREFIX = "cloud_provider:";
    private static final String PREFS_KEY_LOCAL_PREFIX = "local_provider:";

    private static final String PREFS_KEY_RESUME = "resume";
    private static final String PREFS_KEY_OPERATION_MEDIA_ADD_PREFIX = "media_add:";
    private static final String PREFS_KEY_OPERATION_MEDIA_REMOVE_PREFIX = "media_remove:";
    private static final String PREFS_KEY_OPERATION_ALBUM_ADD_PREFIX = "album_add:";

    private static final String PICKER_USER_PREFS_FILE_NAME = "picker_user_prefs";
    public static final String PICKER_SYNC_PREFS_FILE_NAME = "picker_sync_prefs";
    public static final String LOCAL_PICKER_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker";

    private static final String PREFS_VALUE_CLOUD_PROVIDER_UNSET = "-";

    private static final int OPERATION_ADD_MEDIA = 1;
    private static final int OPERATION_ADD_ALBUM = 2;
    private static final int OPERATION_REMOVE_MEDIA = 3;

    @IntDef(
            flag = false,
            value = {OPERATION_ADD_MEDIA, OPERATION_ADD_ALBUM, OPERATION_REMOVE_MEDIA})
    @Retention(RetentionPolicy.SOURCE)
    private @interface OperationType {}

    private static final int SYNC_TYPE_NONE = 0;
    private static final int SYNC_TYPE_MEDIA_INCREMENTAL = 1;
    private static final int SYNC_TYPE_MEDIA_FULL = 2;
    private static final int SYNC_TYPE_MEDIA_RESET = 3;
    private static final int SYNC_TYPE_MEDIA_FULL_WITH_RESET = 4;
    public static final int PAGE_SIZE = 1000;
    @NonNull
    private static final Handler sBgThreadHandler = BackgroundThread.getHandler();
    @IntDef(flag = false, prefix = { "SYNC_TYPE_" }, value = {
            SYNC_TYPE_NONE,
            SYNC_TYPE_MEDIA_INCREMENTAL,
            SYNC_TYPE_MEDIA_FULL,
            SYNC_TYPE_MEDIA_RESET,
            SYNC_TYPE_MEDIA_FULL_WITH_RESET,
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface SyncType {}

    private static final long DEFAULT_GENERATION = -1;
    private final Context mContext;
    private final ConfigStore mConfigStore;
    private final PickerDbFacade mDbFacade;
    private final SharedPreferences mSyncPrefs;
    private final SharedPreferences mUserPrefs;
    private final PickerSyncLockManager mPickerSyncLockManager;
    private final String mLocalProvider;

    private CloudProviderInfo mCloudProviderInfo;
    @NonNull
    private ProviderCollectionInfo mLatestLocalProviderCollectionInfo;
    @NonNull
    private ProviderCollectionInfo mLatestCloudProviderCollectionInfo;
    @NonNull
    private SearchState mSearchState;
    @NonNull
    private CategoriesState mCategoriesState;
    @Nullable
    private static PickerSyncController sInstance;

    /**
     * This URI path when used in a MediaProvider.query() method redirects the call to media_grants
     * table present in the external database.
     */
    private static final String MEDIA_GRANTS_URI_PATH = "content://media/media_grants";

    /**
     * Extra that can be passed in the grants sync query to ensure only the data corresponding to
     * the required mimeTypes is synced.
     */
    public static final String EXTRA_MEDIA_GRANTS_MIME_TYPES = "media_grant_mime_type_selection";

    /**
     * Initialize {@link PickerSyncController} object.{@link PickerSyncController} should only be
     * initialized from {@link com.android.providers.media.MediaProvider#onCreate}.
     *
     * @param context the app context of type {@link Context}
     * @param dbFacade instance of {@link PickerDbFacade} that will be used for DB queries.
     * @param configStore {@link ConfigStore} that returns the sync config of the device.
     * @return an instance of {@link PickerSyncController}
     */
    @NonNull
    public static PickerSyncController initialize(@NonNull Context context,
            @NonNull PickerDbFacade dbFacade, @NonNull ConfigStore configStore, @NonNull
            PickerSyncLockManager pickerSyncLockManager) {
        return initialize(context, dbFacade, configStore, pickerSyncLockManager,
                LOCAL_PICKER_PROVIDER_AUTHORITY);
    }

    /**
     * Initialize {@link PickerSyncController} object.{@link PickerSyncController} should only be
     * initialized from {@link com.android.providers.media.MediaProvider#onCreate}.
     *
     * @param context the app context of type {@link Context}
     * @param dbFacade instance of {@link PickerDbFacade} that will be used for DB queries.
     * @param configStore {@link ConfigStore} that returns the sync config of the device.
     * @param localProvider is the name of the local provider that is responsible for providing the
     *                      local media items.
     * @return an instance of {@link PickerSyncController}
     */
    @NonNull
    @VisibleForTesting
    public static PickerSyncController initialize(@NonNull Context context,
            @NonNull PickerDbFacade dbFacade, @NonNull ConfigStore configStore,
            @NonNull PickerSyncLockManager pickerSyncLockManager, @NonNull String localProvider) {
        sInstance = new PickerSyncController(context, dbFacade, configStore, pickerSyncLockManager,
                localProvider);
        return sInstance;
    }

    /**
     * This method is available for injecting a mock instance from tests. PickerSyncController is
     * used in Worker classes. They cannot directly be injected with a mock controller instance.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setInstance(PickerSyncController controller) {
        sInstance = controller;
    }

    /**
     * Returns PickerSyncController instance if it is initialized else throws an exception.
     * @return a PickerSyncController object.
     * @throws IllegalStateException when the PickerSyncController is not initialized.
     */
    @NonNull
    public static PickerSyncController getInstanceOrThrow() throws IllegalStateException {
        if (sInstance == null) {
            throw new IllegalStateException("PickerSyncController is not initialised.");
        }
        return sInstance;
    }

    private PickerSyncController(@NonNull Context context, @NonNull PickerDbFacade dbFacade,
            @NonNull ConfigStore configStore, @NonNull PickerSyncLockManager pickerSyncLockManager,
            @NonNull String localProvider) {
        mContext = context;
        mConfigStore = configStore;
        mSyncPrefs = mContext.getSharedPreferences(PICKER_SYNC_PREFS_FILE_NAME,
                Context.MODE_PRIVATE);
        mUserPrefs = mContext.getSharedPreferences(PICKER_USER_PREFS_FILE_NAME,
                Context.MODE_PRIVATE);
        mDbFacade = dbFacade;
        mPickerSyncLockManager = pickerSyncLockManager;
        mLocalProvider = localProvider;
        mSearchState = new SearchState(mConfigStore);
        mCategoriesState = new CategoriesState(mConfigStore);

        // Listen to the device config, and try to enable cloud features when the config changes.
        mConfigStore.addOnChangeListener(BackgroundThread.getExecutor(), this::initCloudProvider);
        initCloudProvider();
    }

    /**
     * This method is called after the broadcast intent action {@link Intent.ACTION_BOOT_COMPLETE}
     * is received.
     */
    public void onBootComplete() {
        tryEnablingCloudMediaQueries(/* delay */ TimeUnit.MINUTES.toMillis(3));
    }

    private Integer mEnableCloudQueryRemainingRetry = 2;

    /**
     * Attempt to enable cloud media queries in Picker DB with a retry mechanism.
     */
    @VisibleForTesting
    public void tryEnablingCloudMediaQueries(@NonNull long delay) {
        Log.d(TAG, "Schedule enable cloud media query task.");

        BackgroundThread.getHandler().postDelayed(() -> {
            Log.d(TAG, "Attempting to enable cloud media queries.");
            try {
                maybeEnableCloudMediaQueries();
            } catch (UnableToAcquireLockException | RequestObsoleteException | RuntimeException e) {
                // Cloud media provider can return unexpected values if it's still bootstrapping.
                // Retry in case a possibly transient error is encountered.
                Log.d(TAG, "Error occurred, remaining retry count: "
                        + mEnableCloudQueryRemainingRetry, e);
                mEnableCloudQueryRemainingRetry--;
                if (mEnableCloudQueryRemainingRetry >= 0) {
                    tryEnablingCloudMediaQueries(/* delay */ TimeUnit.MINUTES.toMillis(3));
                }
            }
        }, delay);
    }

    @NonNull
    public PickerSyncLockManager getPickerSyncLockManager() {
        return mPickerSyncLockManager;
    }

    private void initCloudProvider() {
        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .lock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            if (!mConfigStore.isCloudMediaInPhotoPickerEnabled()) {
                Log.d(TAG, "Cloud-Media-in-Photo-Picker feature is disabled during " + TAG
                        + " construction.");
                persistCloudProviderInfo(CloudProviderInfo.EMPTY, /* shouldUnset */ false);
                return;
            }

            final String cachedAuthority = mUserPrefs.getString(
                    PREFS_KEY_CLOUD_PROVIDER_AUTHORITY, null);

            if (isCloudProviderUnset(cachedAuthority)) {
                Log.d(TAG, "Cloud provider state is unset during " + TAG + " construction.");
                setCurrentCloudProviderInfo(CloudProviderInfo.EMPTY);
                return;
            }

            initCloudProviderLocked(cachedAuthority);
        }
    }

    private void initCloudProviderLocked(@Nullable String cachedAuthority) {
        final CloudProviderInfo defaultInfo = getDefaultCloudProviderInfo(cachedAuthority);

        if (Objects.equals(defaultInfo.authority, cachedAuthority)) {
            // Just set it without persisting since it's not changing and persisting would
            // notify the user that cloud media is now available
            setCurrentCloudProviderInfo(defaultInfo);
        } else {
            // Persist it so that we notify the user that cloud media is now available
            persistCloudProviderInfo(defaultInfo, /* shouldUnset */ false);
        }

        Log.d(TAG, "Initialized cloud provider to: " + defaultInfo.authority);
    }

    /**
     * Enables Cloud media queries if the Picker DB is in sync with the latest collection id.
     * @throws RequestObsoleteException if the cloud authority changes during the operation.
     * @throws UnableToAcquireLockException If the required locks cannot be acquired to complete the
     * operation.
     */
    public void maybeEnableCloudMediaQueries()
            throws RequestObsoleteException, UnableToAcquireLockException {
        try (CloseableReentrantLock ignored =
                     mPickerSyncLockManager.tryLock(PickerSyncLockManager.CLOUD_SYNC_LOCK)) {
            final String cloudProvider = getCloudProviderWithTimeout();
            final SyncRequestParams params =
                    getSyncRequestParams(cloudProvider, /* isLocal */ false);
            switch (params.syncType) {
                case SYNC_TYPE_NONE:
                case SYNC_TYPE_MEDIA_INCREMENTAL:
                case SYNC_TYPE_MEDIA_FULL:
                    enablePickerCloudMediaQueries(cloudProvider, /* isLocal */ false);
                    break;

                case SYNC_TYPE_MEDIA_RESET:
                case SYNC_TYPE_MEDIA_FULL_WITH_RESET:
                    disablePickerCloudMediaQueries(/* isLocal */ false);
                    break;

                default:
                    throw new IllegalArgumentException(
                            "Could not recognize sync type " + params.syncType);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Could not enable picker cloud media queries", e);
        }
    }

    /**
     * Syncs the local and currently enabled cloud {@link CloudMediaProvider} instances
     */
    public void syncAllMedia() {
        Log.d(TAG, "syncAllMedia");

        Trace.beginSection(traceSectionName("syncAllMedia"));
        try {
            syncAllMediaFromLocalProvider(/*CancellationSignal=*/ null);
            syncAllMediaFromCloudProvider(/*CancellationSignal=*/ null);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Syncs the local media
     */
    public void syncAllMediaFromLocalProvider(@Nullable CancellationSignal cancellationSignal) {
        // Picker sync and special format update can execute concurrently and run into a deadlock.
        // Acquiring a lock before execution of each flow to avoid this.
        sIdleMaintenanceSyncLock.lock();
        try {
            final InstanceId instanceId = NonUiEventLogger.generateInstanceId();
            syncAllMediaFromProvider(mLocalProvider, /* isLocal */ true, /* retryOnFailure */ true,
                    /* enablePagedSync= */ true, instanceId, cancellationSignal);
        } finally {
            sIdleMaintenanceSyncLock.unlock();
        }
    }

    /**
     * Syncs the cloud media
     */
    public void syncAllMediaFromCloudProvider(@Nullable CancellationSignal cancellationSignal) {

        try (CloseableReentrantLock ignored =
                     mPickerSyncLockManager.tryLock(PickerSyncLockManager.CLOUD_SYNC_LOCK)) {
            final String cloudProvider = getCloudProviderWithTimeout();

            // Trigger a sync.
            final InstanceId instanceId = NonUiEventLogger.generateInstanceId();
            final boolean didSyncFinish = syncAllMediaFromProvider(cloudProvider,
                    /* isLocal= */ false, /* retryOnFailure= */ true, /* enablePagedSync= */ true,
                    instanceId, cancellationSignal);

            // Check if sync was completed successfully.
            if (!didSyncFinish) {
                Log.e(TAG, "Failed to fully complete sync with cloud provider - " + cloudProvider
                        + ". The cloud provider may have changed during the sync, or only a"
                        + " partial sync was completed.");
            }
        } catch (UnableToAcquireLockException e) {
            Log.e(TAG, "Could not sync with the cloud provider", e);
        }
    }

    /**
     * Syncs album media from the local and currently enabled cloud {@link CloudMediaProvider}
     * instances
     */
    public void syncAlbumMedia(String albumId, boolean isLocal) {
        if (isLocal) {
            executeSyncAlbumReset(getLocalProvider(), isLocal, albumId);
            syncAlbumMediaFromLocalProvider(albumId, /* cancellationSignal=*/ null);
        } else {
            try (CloseableReentrantLock ignored = mPickerSyncLockManager
                    .tryLock(PickerSyncLockManager.CLOUD_ALBUM_SYNC_LOCK)) {
                executeSyncAlbumReset(getCloudProviderWithTimeout(), isLocal, albumId);
            } catch (UnableToAcquireLockException e) {
                Log.e(TAG, "Unable to reset cloud album media " + albumId, e);
                // Continue to attempt cloud album sync. This may show deleted album media on
                // the album view.
            }
            syncAlbumMediaFromCloudProvider(albumId, /*cancellationSignal=*/ null);
        }
    }

    /** Syncs album media from the local provider. */
    public void syncAlbumMediaFromLocalProvider(
            @NonNull String albumId, @Nullable CancellationSignal cancellationSignal) {
        syncAlbumMediaFromProvider(mLocalProvider, /* isLocal */ true, albumId,
                /* enablePagedSync= */ true, cancellationSignal);
    }

    /** Syncs album media from the currently enabled cloud {@link CloudMediaProvider}. */
    public void syncAlbumMediaFromCloudProvider(
            @NonNull String albumId, @Nullable CancellationSignal cancellationSignal) {
        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .tryLock(PickerSyncLockManager.CLOUD_ALBUM_SYNC_LOCK)) {
            syncAlbumMediaFromProvider(getCloudProviderWithTimeout(), /* isLocal */ false, albumId,
                    /* enablePagedSync= */ true, cancellationSignal);
        } catch (UnableToAcquireLockException e) {
            Log.e(TAG, "Unable to sync cloud album media " + albumId, e);
        }
    }

    /**
     * Resets media library previously synced from the current {@link CloudMediaProvider} as well
     * as the {@link #mLocalProvider local provider}.
     */
    public void resetAllMedia() throws UnableToAcquireLockException {
        // No need to acquire cloud lock for local reset.
        resetAllMedia(mLocalProvider, /* isLocal */ true);

        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .lock(PickerSyncLockManager.CLOUD_SYNC_LOCK)) {

            // This does not fall in any sync path. Try to acquire the lock indefinitely.
            resetAllMedia(getCloudProvider(), /* isLocal */ false);
        }
    }

    private boolean resetAllMedia(@Nullable String authority, boolean isLocal)
            throws UnableToAcquireLockException {
        Trace.beginSection(traceSectionName("resetAllMedia", isLocal));
        try {
            executeSyncReset(authority, isLocal);
            return resetCachedMediaCollectionInfo(authority, isLocal);
        } finally {
            Trace.endSection();
        }
    }

    @NonNull
    private CloudProviderInfo getCloudProviderInfo(String authority, boolean ignoreAllowlist) {
        if (authority == null) {
            return CloudProviderInfo.EMPTY;
        }

        final List<CloudProviderInfo> availableProviders = ignoreAllowlist
                ? CloudProviderUtils.getAllAvailableCloudProviders(mContext, mConfigStore)
                : CloudProviderUtils.getAvailableCloudProviders(mContext, mConfigStore);

        for (CloudProviderInfo info : availableProviders) {
            if (Objects.equals(info.authority, authority)) {
                return info;
            }
        }

        return CloudProviderInfo.EMPTY;
    }

    /**
     * @return list of available <b>and</b> allowlisted {@link CloudMediaProvider}-s.
     */
    @VisibleForTesting
    List<CloudProviderInfo> getAvailableCloudProviders() {
        return CloudProviderUtils.getAvailableCloudProviders(mContext, mConfigStore);
    }

    /**
     * Enables a provider with {@code authority} as the default cloud {@link CloudMediaProvider}.
     * If {@code authority} is set to {@code null}, it simply clears the cloud provider.
     *
     * Note, that this doesn't sync the new provider after switching, however, no cloud items will
     * be available from the picker db until the next sync. Callers should schedule a sync in the
     * background after switching providers.
     *
     * @return {@code true} if the provider was successfully enabled or cleared, {@code false}
     *         otherwise.
     */
    public boolean setCloudProvider(@Nullable String authority) {
        Trace.beginSection(traceSectionName("setCloudProvider"));
        try {
            return setCloudProviderInternal(authority, /* ignoreAllowlist */ false);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Set cloud provider ignoring allowlist.
     *
     * @return {@code true} if the provider was successfully enabled or cleared, {@code false}
     *         otherwise.
     */
    public boolean forceSetCloudProvider(@Nullable String authority) {
        Trace.beginSection(traceSectionName("forceSetCloudProvider"));
        try {
            return setCloudProviderInternal(authority, /* ignoreAllowlist */ true);
        } finally {
            Trace.endSection();
        }
    }

    private boolean setCloudProviderInternal(@Nullable String authority, boolean ignoreAllowList) {
        Log.d(TAG, "setCloudProviderInternal() auth=" + authority + ", "
                + "ignoreAllowList=" + ignoreAllowList);
        if (DEBUG) {
            Log.v(TAG, "Thread=" + Thread.currentThread() + "; Stacktrace:", new Throwable());
        }

        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .lock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            if (Objects.equals(mCloudProviderInfo.authority, authority)) {
                Log.w(TAG, "Cloud provider already set: " + authority);
                return true;
            }
        }

        final CloudProviderInfo newProviderInfo = getCloudProviderInfo(authority, ignoreAllowList);
        if (authority == null || !newProviderInfo.isEmpty()) {
            try (CloseableReentrantLock ignored = mPickerSyncLockManager
                    .lock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
                // Disable cloud provider queries on the db until next sync
                // This will temporarily *clear* the cloud provider on the db facade and prevent
                // any queries from seeing cloud media until a sync where the cloud provider will be
                // reset on the facade
                mDbFacade.setCloudProvider(null);

                final String oldAuthority = mCloudProviderInfo.authority;
                persistCloudProviderInfo(newProviderInfo, /* shouldUnset */ true);

                // TODO(b/242897322): Log from PickerViewModel using its InstanceId when relevant
                NonUiEventLogger.logPickerCloudProviderChanged(newProviderInfo.uid,
                        newProviderInfo.packageName);
                Log.i(TAG, "Cloud provider changed successfully. Old: "
                        + oldAuthority + ". New: " + newProviderInfo.authority);
            }

            return true;
        }

        Log.w(TAG, "Cloud provider not supported: " + authority);
        return false;
    }

    /**
     * @return {@link CloudProviderInfo} for the current {@link CloudMediaProvider} or
     *         {@link CloudProviderInfo#EMPTY} if the {@link CloudMediaProvider} integration is not
     *         enabled.
     */
    @NonNull
    public CloudProviderInfo getCurrentCloudProviderInfo() {
        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .lock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            return mCloudProviderInfo;
        }
    }

    /**
     * Set {@link PickerSyncController#mCloudProviderInfo} as the current {@link CloudMediaProvider}
     *         or {@link CloudProviderInfo#EMPTY} if the {@link CloudMediaProvider} integration
     *         disabled by the user.
     */
    private void setCurrentCloudProviderInfo(@NonNull CloudProviderInfo cloudProviderInfo) {
        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .lock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            mCloudProviderInfo = cloudProviderInfo;
        }
    }

    /**
     * This should not be used in picker sync paths because we should not wait on a lock
     * indefinitely during the picker sync process.
     * Use {@link this#getCloudProviderWithTimeout()} instead.
     * @return {@link android.content.pm.ProviderInfo#authority authority} of the current
     *         {@link CloudMediaProvider} or {@code null} if the {@link CloudMediaProvider}
     *         integration is not enabled.
     */
    @Nullable
    public String getCloudProvider() {
        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .lock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            return mCloudProviderInfo.authority;
        }
    }

    /**
     * @return {@link android.content.pm.ProviderInfo#authority authority} of the current
     *         {@link CloudMediaProvider} or {@code null} if the {@link CloudMediaProvider}
     *         integration is not enabled. This operation acquires a lock internally with a timeout.
     * @throws UnableToAcquireLockException if the lock was not acquired within the given timeout.
     */
    @Nullable
    public String getCloudProviderWithTimeout() throws UnableToAcquireLockException {
        try (CloseableReentrantLock ignored  = mPickerSyncLockManager
                .tryLock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            return mCloudProviderInfo.authority;
        }
    }

    /**
     * @param defaultValue The default cloud provider authority to return if cloud provider cannot
     *                     be fetched within the given timeout.
     * @return {@link android.content.pm.ProviderInfo#authority authority} of the current
     *         {@link CloudMediaProvider} or {@code null} if the {@link CloudMediaProvider}
     *         integration is not enabled. This operation acquires a lock internally with a timeout.
     */
    @Nullable
    public String getCloudProviderOrDefault(@Nullable String defaultValue) {
        try {
            return getCloudProviderWithTimeout();
        } catch (UnableToAcquireLockException e) {
            Log.e(TAG, "Could not get cloud provider, returning default value: " + defaultValue, e);
            return defaultValue;
        }
    }

    /**
     * @return {@link android.content.pm.ProviderInfo#authority authority} of the local provider.
     */
    @NonNull
    public String getLocalProvider() {
        return mLocalProvider;
    }

    /**
     * Returns the local provider's collection info.
     */
    @Nullable
    public ProviderCollectionInfo getLocalProviderLatestCollectionInfo() {
        return getLatestCollectionInfoLocked(/* isLocal */ true, mLocalProvider);
    }

    /**
     * Returns the current cloud provider's collection info. First, attempt to get it from cache.
     * If the cache is not up to date, get it from the cloud provider directly.
     */
    @Nullable
    public ProviderCollectionInfo getCloudProviderLatestCollectionInfo() {
        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .tryLock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            final String currentCloudProvider = getCloudProviderWithTimeout();
            return getLatestCollectionInfoLocked(/* isLocal */ false, currentCloudProvider);
        } catch (UnableToAcquireLockException e) {
            Log.e(TAG, "Could not get latest collection info", e);
            return null;
        }
    }

    @Nullable
    private ProviderCollectionInfo getLatestCollectionInfoLocked(
            boolean isLocal,
            @Nullable String authority) {
        final ProviderCollectionInfo latestCachedProviderCollectionInfo =
                getLatestCollectionInfoLocked(isLocal);

        if (latestCachedProviderCollectionInfo != null
                && TextUtils.equals(authority, latestCachedProviderCollectionInfo.getAuthority())) {
            Log.d(TAG, "Latest collection info up to date " + latestCachedProviderCollectionInfo);
            return latestCachedProviderCollectionInfo;
        } else {
            final ProviderCollectionInfo latestCollectionInfo;
            if (authority == null) {
                return null;
            } else {
                Log.d(TAG, "Latest collection info up is NOT up to date. "
                        + "Fetching the latest collection info from CMP.");
                final Bundle latestMediaCollectionInfoBundle =
                        getLatestMediaCollectionInfo(authority);
                final String latestCollectionId =
                        latestMediaCollectionInfoBundle.getString(MEDIA_COLLECTION_ID);
                final String latestAccountName =
                        latestMediaCollectionInfoBundle.getString(ACCOUNT_NAME);
                final Intent latestAccountConfigurationIntent =
                        getAccountConfigurationIntent(latestMediaCollectionInfoBundle);
                latestCollectionInfo = new ProviderCollectionInfo(authority, latestCollectionId,
                        latestAccountName, latestAccountConfigurationIntent);
            }

            updateLatestKnownCollectionInfoLocked(isLocal, latestCollectionInfo);
            return (ProviderCollectionInfo) latestCollectionInfo.clone();
        }
    }

    @Nullable
    private Intent getAccountConfigurationIntent(@NonNull Bundle bundle) {
        if (SdkLevel.isAtLeastT()) {
            return bundle.getParcelable(ACCOUNT_CONFIGURATION_INTENT, Intent.class);
        } else {
            return (Intent) bundle.getParcelable(ACCOUNT_CONFIGURATION_INTENT);
        }
    }

    @Nullable
    private ProviderCollectionInfo getLatestCollectionInfoLocked(boolean isLocal) {
        ProviderCollectionInfo latestCollectionInfo;
        if (isLocal) {
            latestCollectionInfo = mLatestLocalProviderCollectionInfo;
        } else {
            latestCollectionInfo = mLatestCloudProviderCollectionInfo;
        }
        return latestCollectionInfo != null
                ? (ProviderCollectionInfo) latestCollectionInfo.clone()
                : null;
    }


    /**
     * @return current cloud provider app localized label. This operation acquires a lock
     *         internally with a timeout.
     * @throws UnableToAcquireLockException if the lock was not acquired within the given timeout.
     */
    public String getCurrentCloudProviderLocalizedLabel() throws UnableToAcquireLockException {
        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .tryLock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            if (mCloudProviderInfo.isEmpty()) {
                return mContext.getResources().getString(R.string.picker_settings_no_provider);
            }
            return CloudProviderUtils.getProviderLabel(
                    mContext.getPackageManager(), mCloudProviderInfo.authority);
        }
    }

    public boolean isProviderEnabled(String authority) {
        if (mLocalProvider.equals(authority)) {
            return true;
        }

        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .lock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            if (!mCloudProviderInfo.isEmpty()
                    && Objects.equals(mCloudProviderInfo.authority, authority)) {
                return true;
            }
        }

        return false;
    }

    public boolean isProviderEnabled(String authority, int uid) {
        if (uid == MY_UID && mLocalProvider.equals(authority)) {
            return true;
        }

        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .lock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            if (!mCloudProviderInfo.isEmpty() && uid == mCloudProviderInfo.uid
                    && Objects.equals(mCloudProviderInfo.authority, authority)) {
                return true;
            }
        }

        return false;
    }

    public boolean isProviderSupported(String authority, int uid) {
        if (uid == MY_UID && mLocalProvider.equals(authority)) {
            return true;
        }

        // TODO(b/232738117): Enforce allow list here. This works around some CTS failure late in
        // Android T. The current implementation is fine since cloud providers is only supported
        // for app developers testing.
        final List<CloudProviderInfo> infos =
                CloudProviderUtils.getAllAvailableCloudProviders(mContext, mConfigStore);
        for (CloudProviderInfo info : infos) {
            if (info.uid == uid && Objects.equals(info.authority, authority)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Notifies about package removal
     */
    public void notifyPackageRemoval(String packageName) {
        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .lock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            if (mCloudProviderInfo.matches(packageName)) {
                Log.i(TAG, "Package " + packageName
                        + " is the current cloud provider and got removed");
                resetCloudProvider();
            }
        }
    }

    @NonNull
    public SearchState getSearchState() {
        return mSearchState;
    }

    private void resetCloudProvider() {
        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .lock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            setCloudProvider(/* authority */ null);

            /**
             * {@link #setCloudProvider(String null)} sets the cloud provider state to UNSET.
             * Clearing the persisted cloud provider authority to set the state as NOT_SET instead.
             */
            clearPersistedCloudProviderAuthority();

            initCloudProviderLocked(/* cachedAuthority */ null);
        }
    }

    /**
     * Syncs album media.
     *
     * @param enablePagedSync Set to true if the data from the provider may be synced in batches.
     *                         If true, {@link CloudMediaProviderContract#EXTRA_PAGE_SIZE
     *                         is passed during query to the provider.
     */
    private void syncAlbumMediaFromProvider(String authority, boolean isLocal, String albumId,
            boolean enablePagedSync, @Nullable CancellationSignal cancellationSignal) {
        final InstanceId instanceId = NonUiEventLogger.generateInstanceId();
        NonUiEventLogger.logPickerAlbumMediaSyncStart(instanceId, MY_UID, authority);

        final Bundle queryArgs = new Bundle();
        queryArgs.putString(EXTRA_ALBUM_ID, albumId);
        if (enablePagedSync) {
            queryArgs.putInt(EXTRA_PAGE_SIZE, PAGE_SIZE);
        }

        Trace.beginSection(traceSectionName("syncAlbumMediaFromProvider", isLocal));
        try {
            if (authority != null) {
                executeSyncAddAlbum(
                        authority, isLocal, albumId, queryArgs, instanceId, cancellationSignal);
            }
        } catch (RuntimeException | UnableToAcquireLockException | WorkCancelledException e) {
            // Unlike syncAllMediaFromProvider, we don't retry here because any errors would have
            // occurred in fetching all the album_media since incremental sync is not supported.
            // A full sync is therefore unlikely to resolve any issue
            Log.e(TAG, "Failed to sync album media", e);
        } catch (RequestObsoleteException e) {
            Log.e(TAG, "Failed to sync all album media because authority has changed.", e);
            executeSyncAlbumReset(authority, isLocal, albumId);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Returns true if the sync was successful and the latest collection info was persisted.
     *
     * @param enablePagedSync Set to true if the data from the provider may be synced in batches.
     *                         If true, {@link CloudMediaProviderContract#EXTRA_PAGE_SIZE} is passed
     *                         during query to the provider.
     */
    private boolean syncAllMediaFromProvider(
            @Nullable String authority,
            boolean isLocal,
            boolean retryOnFailure,
            boolean enablePagedSync,
            InstanceId instanceId,
            @Nullable CancellationSignal cancellationSignal) {
        Log.d(TAG, "syncAllMediaFromProvider() " + (isLocal ? "LOCAL" : "CLOUD")
                + ", auth=" + authority
                + ", retry=" + retryOnFailure);
        if (DEBUG) {
            Log.v(TAG, "Thread=" + Thread.currentThread() + "; Stacktrace:", new Throwable());
        }

        Trace.beginSection(traceSectionName("syncAllMediaFromProvider", isLocal));
        try {
            final SyncRequestParams params = getSyncRequestParams(authority, isLocal);
            switch (params.syncType) {
                case SYNC_TYPE_MEDIA_RESET:
                    // Can only happen when |authority| has been set to null and we need to clean up
                    disablePickerCloudMediaQueries(isLocal);
                    return resetAllMedia(authority, isLocal);
                case SYNC_TYPE_MEDIA_FULL_WITH_RESET:
                    disablePickerCloudMediaQueries(isLocal);
                    if (!resetAllMedia(authority, isLocal)) {
                        return false;
                    }

                    // Cache collection id with default generation id to prevent DB reset if full
                    // sync resumes the next time sync is triggered.
                    cacheMediaCollectionInfo(
                            authority, isLocal,
                            getDefaultGenerationCollectionInfo(params.latestMediaCollectionInfo));
                    // Fall through to run full sync
                case SYNC_TYPE_MEDIA_FULL:
                    NonUiEventLogger.logPickerFullSyncStart(instanceId, MY_UID, authority);

                    enablePickerCloudMediaQueries(authority, isLocal);

                    // Send UI refresh notification for any active picker sessions, as the
                    // UI data might be stale if a full sync needs to be run.
                    sendPickerUiRefreshNotification(/* isInitPending */ false);

                    final Bundle fullSyncQueryArgs = new Bundle();
                    if (enablePagedSync) {
                        fullSyncQueryArgs.putInt(EXTRA_PAGE_SIZE, params.mPageSize);
                    }
                    // Pass a mutable empty bundle intentionally because it might be populated with
                    // the next page token as part of a query to a cloud provider supporting
                    // pagination
                    executeSyncAdd(authority, isLocal, params.getMediaCollectionId(),
                            /* isIncrementalSync */ false, fullSyncQueryArgs,
                            instanceId, cancellationSignal);

                    // Commit sync position
                    return cacheMediaCollectionInfo(
                            authority, isLocal, params.latestMediaCollectionInfo);
                case SYNC_TYPE_MEDIA_INCREMENTAL:
                    enablePickerCloudMediaQueries(authority, isLocal);
                    NonUiEventLogger.logPickerIncrementalSyncStart(instanceId, MY_UID, authority);
                    final Bundle queryArgs = new Bundle();
                    queryArgs.putLong(EXTRA_SYNC_GENERATION, params.syncGeneration);
                    if (enablePagedSync) {
                        queryArgs.putInt(EXTRA_PAGE_SIZE, params.mPageSize);
                    }

                    executeSyncAdd(
                            authority,
                            isLocal,
                            params.getMediaCollectionId(),
                            /* isIncrementalSync */ true,
                            queryArgs,
                            instanceId,
                            cancellationSignal);
                    executeSyncRemove(authority, isLocal, params.getMediaCollectionId(), queryArgs,
                            instanceId, cancellationSignal);

                    // Commit sync position
                    return cacheMediaCollectionInfo(
                            authority, isLocal, params.latestMediaCollectionInfo);
                case SYNC_TYPE_NONE:
                    enablePickerCloudMediaQueries(authority, isLocal);
                    return true;
                default:
                    throw new IllegalArgumentException("Unexpected sync type: " + params.syncType);
            }
        } catch (WorkCancelledException e) {
            // Do not reset picker DB here so that the sync operation resumes the next time sync is
            // triggered.
            Log.e(TAG, "Failed to sync all media because the sync was cancelled.", e);
        } catch (RequestObsoleteException e) {
            Log.e(TAG, "Failed to sync all media because authority has changed.", e);
            try {
                resetAllMedia(authority, isLocal);
            } catch (UnableToAcquireLockException ex) {
                Log.e(TAG, "Could not reset media", e);
            }
        } catch (IllegalStateException e) {
            // If we're in an illegal state, reset and start a full sync again.
            Log.e(TAG, "Failed to sync all media. Reset media and retry: " + retryOnFailure, e);
            try {
                resetAllMedia(authority, isLocal);
                if (retryOnFailure) {
                    return syncAllMediaFromProvider(authority, isLocal, /* retryOnFailure */ false,
                            enablePagedSync, instanceId, cancellationSignal);
                }
            } catch (UnableToAcquireLockException ex) {
                Log.e(TAG, "Could not reset media", e);
            }
        } catch (RuntimeException | UnableToAcquireLockException e) {
            // Retry the failed operation to see if it was an intermittent problem. If this fails,
            // the database will be in a partial state until the sync resumes from this point
            // on next run.
            Log.e(TAG, "Failed to sync all media. Reset media and retry: " + retryOnFailure, e);
            if (retryOnFailure) {
                return syncAllMediaFromProvider(authority, isLocal, /* retryOnFailure */ false,
                        enablePagedSync, instanceId, cancellationSignal);
            }
        } finally {
            Trace.endSection();
        }
        return false;
    }

    /**
     * Disable cloud media queries from Picker database. After disabling cloud media queries, when a
     * media query will run on Picker database, only local media items will be returned.
     */
    private void disablePickerCloudMediaQueries(boolean isLocal)
            throws UnableToAcquireLockException {
        if (!isLocal) {
            mDbFacade.setCloudProviderWithTimeout(null);
        }
    }

    /**
     * Enable cloud media queries from Picker database. After enabling cloud media queries, when a
     * media query will run on Picker database, both local and cloud media items will be returned.
     */
    private void enablePickerCloudMediaQueries(String authority, boolean isLocal)
            throws UnableToAcquireLockException {
        if (!isLocal) {
            try (CloseableReentrantLock ignored = mPickerSyncLockManager
                    .tryLock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
                if (Objects.equals(mCloudProviderInfo.authority, authority)) {
                    mDbFacade.setCloudProviderWithTimeout(authority);
                }
            }
        }
    }

    private void executeSyncReset(String authority, boolean isLocal) {
        Log.i(TAG, "Executing SyncReset. isLocal: " + isLocal + ". authority: " + authority);

        Trace.beginSection(traceSectionName("executeSyncReset", isLocal));
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginResetMediaOperation(authority)) {
            final int writeCount = operation.execute(null /* cursor */);
            operation.setSuccess();

            PickerNotificationSender.notifyMediaChange(mContext);

            Log.i(TAG, "SyncReset. isLocal:" + isLocal + ". authority: " + authority
                    +  ". result count: " + writeCount);
        } finally {
            Trace.endSection();
        }
    }

    private void executeSyncAlbumReset(String authority, boolean isLocal, String albumId) {
        Log.i(TAG, "Executing SyncAlbumReset."
                + " isLocal: " + isLocal + ". authority: " + authority + ". albumId: " + albumId);

        Trace.beginSection(traceSectionName("executeSyncAlbumReset", isLocal));
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginResetAlbumMediaOperation(authority, albumId)) {
            final int writeCount = operation.execute(null /* cursor */);
            operation.setSuccess();

            Log.i(TAG, "Successfully executed SyncResetAlbum. authority: " + authority
                    + ". albumId: " + albumId + ". Result count: " + writeCount);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Queries the provider and adds media to the picker database.
     *
     * @param authority Provider's authority
     * @param isLocal Whether this is the local provider or not
     * @param expectedMediaCollectionId The MediaCollectionId from the last sync point.
     * @param isIncrementalSync If true, {@link CloudMediaProviderContract#EXTRA_SYNC_GENERATION}
     *     should be honoured by the provider.
     * @param queryArgs Query arguments to pass in query.
     * @param instanceId Metrics related Picker session instance Id.
     * @param cancellationSignal CancellationSignal used to abort the sync.
     * @throws RequestObsoleteException When the sync is interrupted due to the provider
     *     changing.
     */
    private void executeSyncAdd(
            String authority,
            boolean isLocal,
            String expectedMediaCollectionId,
            boolean isIncrementalSync,
            Bundle queryArgs,
            InstanceId instanceId,
            @Nullable CancellationSignal cancellationSignal)
            throws RequestObsoleteException, UnableToAcquireLockException, WorkCancelledException {
        final Uri uri = getMediaUri(authority);
        final List<String> expectedHonoredArgs = new ArrayList<>();
        if (isIncrementalSync) {
            expectedHonoredArgs.add(EXTRA_SYNC_GENERATION);
        }

        Log.i(TAG, "Executing SyncAdd. isLocal: " + isLocal + ". authority: " + authority);

        String resumeKey =
                getPrefsKey(isLocal, PREFS_KEY_OPERATION_MEDIA_ADD_PREFIX + PREFS_KEY_RESUME);

        Trace.beginSection(traceSectionName("executeSyncAdd", isLocal));
        try {
            int syncedItems = executePagedSync(
                    uri,
                    expectedMediaCollectionId,
                    expectedHonoredArgs,
                    queryArgs,
                    resumeKey,
                    OPERATION_ADD_MEDIA,
                    authority,
                    isLocal,
                    cancellationSignal);
            NonUiEventLogger.logPickerAddMediaSyncCompletion(instanceId, MY_UID, authority,
                    syncedItems);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Queries the provider to sync media from the given albumId into the picker database.
     *
     * @param authority Provider's authority
     * @param isLocal Whether this is the local provider or not
     * @param albumId the Id of the album to sync
     * @param queryArgs Query arguments to pass in query.
     * @param instanceId Metrics related Picker session instance Id.
     * @param cancellationSignal CancellationSignal used to abort the sync.
     * @throws RequestObsoleteException When the sync is interrupted due to the provider
     *     changing.
     */
    private void executeSyncAddAlbum(
            String authority,
            boolean isLocal,
            String albumId,
            Bundle queryArgs,
            InstanceId instanceId,
            @Nullable CancellationSignal cancellationSignal)
            throws RequestObsoleteException, UnableToAcquireLockException, WorkCancelledException {
        final Uri uri = getMediaUri(authority);

        Log.i(TAG, "Executing SyncAddAlbum. "
                + "isLocal: " + isLocal + ". authority: " + authority + ". albumId: " + albumId);
        String resumeKey =
                getPrefsKey(isLocal, PREFS_KEY_OPERATION_ALBUM_ADD_PREFIX + PREFS_KEY_RESUME);

        Trace.beginSection(traceSectionName("executeSyncAddAlbum", isLocal));
        try {

            // We don't need to validate the mediaCollectionId for album_media sync since it's
            // always a full sync
            int syncedItems =
                    executePagedSync(
                            uri, /* mediaCollectionId */
                            null,
                            List.of(EXTRA_ALBUM_ID),
                            queryArgs,
                            resumeKey,
                            OPERATION_ADD_ALBUM,
                            authority,
                            isLocal,
                            albumId,
                            /*cancellationSignal=*/ cancellationSignal);
            NonUiEventLogger.logPickerAddAlbumMediaSyncCompletion(instanceId, MY_UID, authority,
                    syncedItems);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Queries the provider and syncs removed media with the picker database.
     *
     * @param authority Provider's authority
     * @param isLocal Whether this is the local provider or not
     * @param mediaCollectionId The last synced media collection id
     * @param queryArgs Query arguments to pass in query.
     * @param instanceId Metrics related Picker session instance Id.
     * @param cancellationSignal CancellationSignal used to abort the sync.
     * @throws RequestObsoleteException When the sync is interrupted due to the provider
     *     changing.
     */
    private void executeSyncRemove(
            String authority,
            boolean isLocal,
            String mediaCollectionId,
            Bundle queryArgs,
            InstanceId instanceId,
            @Nullable CancellationSignal cancellationSignal)
            throws RequestObsoleteException, UnableToAcquireLockException, WorkCancelledException {
        final Uri uri = getDeletedMediaUri(authority);

        Log.i(TAG, "Executing SyncRemove. isLocal: " + isLocal + ". authority: " + authority);
        String resumeKey =
                getPrefsKey(isLocal, PREFS_KEY_OPERATION_MEDIA_REMOVE_PREFIX + PREFS_KEY_RESUME);

        Trace.beginSection(traceSectionName("executeSyncRemove", isLocal));
        try {
            int syncedItems =
                    executePagedSync(
                            uri,
                            mediaCollectionId,
                            List.of(EXTRA_SYNC_GENERATION),
                            queryArgs,
                            resumeKey,
                            OPERATION_REMOVE_MEDIA,
                            authority,
                            isLocal,
                            cancellationSignal);
            NonUiEventLogger.logPickerRemoveMediaSyncCompletion(instanceId, MY_UID, authority,
                    syncedItems);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Persist cloud provider info and send a sync request to the background thread.
     */
    private void persistCloudProviderInfo(@NonNull CloudProviderInfo info, boolean shouldUnset) {
        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .lock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            setCurrentCloudProviderInfo(info);

            final String authority = info.authority;
            final SharedPreferences.Editor editor = mUserPrefs.edit();
            final boolean isCloudProviderInfoNotEmpty = !info.isEmpty();

            if (isCloudProviderInfoNotEmpty) {
                editor.putString(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY, authority);
            } else if (shouldUnset) {
                editor.putString(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY,
                        PREFS_VALUE_CLOUD_PROVIDER_UNSET);
            } else {
                editor.remove(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY);
            }

            editor.apply();

            if (SdkLevel.isAtLeastT()) {
                try {
                    StorageManager sm = mContext.getSystemService(StorageManager.class);
                    sm.setCloudMediaProvider(authority);
                } catch (SecurityException e) {
                    // When run as part of the unit tests, the notification fails because only the
                    // MediaProvider uid can notify
                    Log.w(TAG, "Failed to notify the system of cloud provider update to: "
                            + authority);
                }
            }

            Log.d(TAG, "Updated cloud provider to: " + authority);

            try {
                resetCachedMediaCollectionInfo(info.authority, /* isLocal */ false);
            } catch (UnableToAcquireLockException e) {
                Log.wtf(TAG, "CLOUD_PROVIDER_LOCK is already held by this thread.");
            }

            sendPickerUiRefreshNotification(/* isInitPending */ true);

            // We need this to trigger a sync from the UI
            PickerNotificationSender.notifyAvailableProvidersChange(mContext);
            updateLatestKnownCollectionInfoLocked(false, null);
        }
    }

    /**
     * Send Picker UI content observers a notification that a refresh is required.
     * @param isInitPending when true, appends the URI path segment
     *  {@link com.android.providers.media.PickerUriResolver.INIT_PATH} to the notification URI
     *  to indicate that the UI that the cached picker data might be stale.
     *  When a request notification is being sent from the sync path, set isInitPending as false to
     *  prevent sending refresh notification in a loop.
     */
    private void sendPickerUiRefreshNotification(boolean isInitPending) {
        final ContentResolver contentResolver = mContext.getContentResolver();
        if (contentResolver != null) {
            final Uri.Builder builder = REFRESH_UI_PICKER_INTERNAL_OBSERVABLE_URI.buildUpon();
            if (isInitPending) {
                builder.appendPath(INIT_PATH);
            }
            final Uri refreshUri = builder.build();
            contentResolver.notifyChange(refreshUri, null);
        } else {
            Log.d(TAG, "Couldn't notify the Picker UI to refresh");
        }
    }

    /**
     * Clears the persisted cloud provider authority and sets the state to default (NOT_SET).
     */
    @VisibleForTesting
    void clearPersistedCloudProviderAuthority() {
        Log.d(TAG, "Setting the cloud provider state to default (NOT_SET) by clearing the "
                + "persisted cloud provider authority");
        mUserPrefs.edit().remove(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY).apply();
    }

    /**
     * Commit the latest media collection info when a sync operation is completed.
     */
    public boolean cacheMediaCollectionInfo(@Nullable String authority, boolean isLocal,
            @Nullable Bundle bundle) throws UnableToAcquireLockException {
        if (authority == null) {
            Log.d(TAG, "Ignoring cache media info for null authority with bundle: " + bundle);
            return true;
        }

        Trace.beginSection(traceSectionName("cacheMediaCollectionInfo", isLocal));

        try {
            if (isLocal) {
                cacheMediaCollectionInfoInternal(isLocal, bundle);
                return true;
            } else {
                try (CloseableReentrantLock ignored = mPickerSyncLockManager
                        .tryLock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
                    // Check if the media collection info belongs to the current cloud provider
                    // authority.
                    if (Objects.equals(authority, mCloudProviderInfo.authority)) {
                        cacheMediaCollectionInfoInternal(isLocal, bundle);
                        return true;
                    } else {
                        Log.e(TAG, "Do not cache collection info for "
                                + authority + " because cloud provider changed to "
                                + mCloudProviderInfo.authority);
                        return false;
                    }
                }
            }
        } finally {
            Trace.endSection();
        }
    }

    private void cacheMediaCollectionInfoInternal(boolean isLocal,
            @Nullable Bundle bundle) {
        final SharedPreferences.Editor editor = mSyncPrefs.edit();
        if (bundle == null) {
            editor.remove(getPrefsKey(isLocal, MEDIA_COLLECTION_ID));
            editor.remove(getPrefsKey(isLocal, LAST_MEDIA_SYNC_GENERATION));
            // Clear any resume keys for page tokens.
            editor.remove(
                    getPrefsKey(isLocal, PREFS_KEY_OPERATION_MEDIA_ADD_PREFIX + PREFS_KEY_RESUME));
            editor.remove(
                    getPrefsKey(isLocal, PREFS_KEY_OPERATION_ALBUM_ADD_PREFIX + PREFS_KEY_RESUME));
            editor.remove(
                    getPrefsKey(
                            isLocal, PREFS_KEY_OPERATION_MEDIA_REMOVE_PREFIX + PREFS_KEY_RESUME));
        } else {
            final String collectionId = bundle.getString(MEDIA_COLLECTION_ID);
            final long generation = bundle.getLong(LAST_MEDIA_SYNC_GENERATION);

            editor.putString(getPrefsKey(isLocal, MEDIA_COLLECTION_ID), collectionId);
            editor.putLong(getPrefsKey(isLocal, LAST_MEDIA_SYNC_GENERATION), generation);
        }
        editor.apply();
    }

    /**
     * Adds the given token to the saved sync preferences.
     *
     * @param token The token to remember. A null value will clear the preference.
     * @param resumeKey The operation's key in sync preferences.
     */
    private void rememberNextPageToken(@Nullable String token, String resumeKey)
            throws UnableToAcquireLockException {

        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .tryLock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            final SharedPreferences.Editor editor = mSyncPrefs.edit();
            if (token == null) {
                Log.d(TAG, String.format("Clearing next page token for key: %s", resumeKey));
                editor.remove(resumeKey);
            } else {
                Log.d(
                        TAG,
                        String.format("Saving next page token: %s for key: %s", token, resumeKey));
                editor.putString(resumeKey, token);
            }
            editor.apply();
        }
    }

    /**
     * Fetches the next page token given a resume key. Returns null if no NextPage token was saved.
     *
     * @param resumeKey The operation's resume key.
     * @return The PageToken to resume from, or {@code null} if there is no operation to resume.
     */
    @Nullable
    private String getPageTokenFromResumeKey(String resumeKey) throws UnableToAcquireLockException {
        try (CloseableReentrantLock ignored = mPickerSyncLockManager
                .tryLock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            return mSyncPrefs.getString(resumeKey, /* defValue= */ null);
        }
    }

    private boolean resetCachedMediaCollectionInfo(@Nullable String authority, boolean isLocal)
            throws UnableToAcquireLockException {
        return cacheMediaCollectionInfo(authority, isLocal, /* bundle */ null);
    }

    private Bundle getCachedMediaCollectionInfo(boolean isLocal) {
        final Bundle bundle = new Bundle();

        final String collectionId = mSyncPrefs.getString(
                getPrefsKey(isLocal, MEDIA_COLLECTION_ID), /* default */ null);
        final long generation = mSyncPrefs.getLong(
                getPrefsKey(isLocal, LAST_MEDIA_SYNC_GENERATION), DEFAULT_GENERATION);

        bundle.putString(MEDIA_COLLECTION_ID, collectionId);
        bundle.putLong(LAST_MEDIA_SYNC_GENERATION, generation);

        return bundle;
    }

    @NonNull
    private Bundle getLatestMediaCollectionInfo(String authority) {
        final InstanceId instanceId = NonUiEventLogger.generateInstanceId();
        NonUiEventLogger.logPickerGetMediaCollectionInfoStart(instanceId, MY_UID, authority);
        try {
            Bundle result = mContext.getContentResolver().call(getMediaCollectionInfoUri(authority),
                    CloudMediaProviderContract.METHOD_GET_MEDIA_COLLECTION_INFO, /* arg */ null,
                    /* extras */ new Bundle());
            return (result == null) ? (new Bundle()) : result;
        } finally {
            NonUiEventLogger.logPickerGetMediaCollectionInfoEnd(instanceId, MY_UID, authority);
        }
    }

    private Bundle getDefaultGenerationCollectionInfo(@NonNull Bundle latestCollectionInfo) {
        final Bundle bundle = new Bundle();
        final String collectionId = latestCollectionInfo.getString(MEDIA_COLLECTION_ID);
        bundle.putString(MEDIA_COLLECTION_ID, collectionId);
        bundle.putLong(LAST_MEDIA_SYNC_GENERATION, DEFAULT_GENERATION);
        return bundle;
    }

    /**
     * Checks if full sync is pending for the given CMP.
     *
     * @param authority Authority of the CMP that uniquely identifies it.
     * @param isLocal true of the authority belongs to the local provider, else false.
     * @return true if full sync is pending for the CMP, else false.
     * @throws RequestObsoleteException if the input authority is different than the authority of
     * the current cloud provider.
     */
    public boolean isFullSyncPending(@NonNull String authority, boolean isLocal)
            throws RequestObsoleteException {
        final ProviderCollectionInfo latestCollectionInfo = isLocal
                ? getLocalProviderLatestCollectionInfo()
                : getCloudProviderLatestCollectionInfo();

        if (!authority.equals(latestCollectionInfo.getAuthority())) {
            throw new RequestObsoleteException(
                    "Authority has changed to " + latestCollectionInfo.getAuthority());
        }

        final Bundle cachedPreviousCollectionInfo = getCachedMediaCollectionInfo(isLocal);
        final String cachedPreviousCollectionId =
                cachedPreviousCollectionInfo.getString(MEDIA_COLLECTION_ID);
        final long cachedPreviousGeneration =
                cachedPreviousCollectionInfo.getLong(LAST_MEDIA_SYNC_GENERATION);

        return isFullSyncRequired(
                latestCollectionInfo.getCollectionId(),
                cachedPreviousCollectionId,
                cachedPreviousGeneration);
    }

    @NonNull
    private SyncRequestParams getSyncRequestParams(@Nullable String authority,
            boolean isLocal) throws RequestObsoleteException, UnableToAcquireLockException {
        if (isLocal) {
            return getSyncRequestParamsLocked(authority, isLocal);
        } else {
            // Ensure that we are fetching sync request params for the current cloud provider.
            try (CloseableReentrantLock ignored = mPickerSyncLockManager
                    .tryLock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
                if (Objects.equals(mCloudProviderInfo.authority, authority)) {
                    return getSyncRequestParamsLocked(authority, isLocal);
                } else {
                    throw new RequestObsoleteException("Attempt to fetch sync request params for an"
                            + " unknown cloud provider. Current provider: "
                            + mCloudProviderInfo.authority + " Requested provider: " + authority);
                }
            }
        }
    }

    @NonNull
    private SyncRequestParams getSyncRequestParamsLocked(@Nullable String authority,
            boolean isLocal) {
        Log.d(TAG, "getSyncRequestParams() " + (isLocal ? "LOCAL" : "CLOUD")
                + ", auth=" + authority);
        if (DEBUG) {
            Log.v(TAG, "Thread=" + Thread.currentThread() + "; Stacktrace:", new Throwable());
        }

        final SyncRequestParams result;
        if (authority == null) {
            // Only cloud authority can be null
            result = SyncRequestParams.forResetMedia();
        } else {
            final Bundle cachedMediaCollectionInfo = getCachedMediaCollectionInfo(isLocal);
            final Bundle latestMediaCollectionInfo = getLatestMediaCollectionInfo(authority);

            final String latestCollectionId =
                    latestMediaCollectionInfo.getString(MEDIA_COLLECTION_ID);
            final long latestGeneration =
                    latestMediaCollectionInfo.getLong(LAST_MEDIA_SYNC_GENERATION);
            Log.d(TAG, "   Latest ID/Gen=" + latestCollectionId + "/" + latestGeneration);

            final String cachedCollectionId =
                    cachedMediaCollectionInfo.getString(MEDIA_COLLECTION_ID);
            final long cachedGeneration =
                    cachedMediaCollectionInfo.getLong(LAST_MEDIA_SYNC_GENERATION);
            Log.d(TAG, "   Cached ID/Gen=" + cachedCollectionId + "/" + cachedGeneration);

            if (TextUtils.isEmpty(latestCollectionId) || latestGeneration < 0) {
                throw new IllegalStateException("Unexpected Latest Media Collection Info: "
                        + "ID/Gen=" + latestCollectionId + "/" + latestGeneration);
            }

            if (isFullSyncWithResetRequired(latestCollectionId, cachedCollectionId)) {
                result = SyncRequestParams.forFullMediaWithReset(latestMediaCollectionInfo);

                // Update collection info cache.
                final String latestAccountName =
                        latestMediaCollectionInfo.getString(ACCOUNT_NAME);
                final Intent latestAccountConfigurationIntent =
                        getAccountConfigurationIntent(latestMediaCollectionInfo);
                final ProviderCollectionInfo latestCollectionInfo =
                        new ProviderCollectionInfo(authority, latestCollectionId, latestAccountName,
                                latestAccountConfigurationIntent);
                updateLatestKnownCollectionInfoLocked(isLocal, latestCollectionInfo);
            } else if (isFullSyncWithoutResetRequired(
                    latestCollectionId, cachedCollectionId, cachedGeneration)) {
                result = SyncRequestParams.forFullMedia(latestMediaCollectionInfo);
            } else if (cachedGeneration == latestGeneration) {
                result = SyncRequestParams.forNone();
            } else {
                result = SyncRequestParams.forIncremental(
                        cachedGeneration, latestMediaCollectionInfo);
            }
        }
        Log.d(TAG, "   RESULT=" + result);
        return result;
    }

    /**
     * @param latestCollectionId The latest collection id of the CMP library.
     * @param cachedCollectionId The last collection id Picker DB was synced with, either fully
     *                           or partially.
     * @param cachedGenerationId The last generation id Picker DB was synced with.
     * @return true if a full sync is pending, else false.
     */
    private boolean isFullSyncRequired(
            @Nullable String latestCollectionId,
            @Nullable String cachedCollectionId,
            long cachedGenerationId) {
        return isFullSyncWithResetRequired(latestCollectionId, cachedCollectionId)
                || isFullSyncWithoutResetRequired(latestCollectionId, cachedCollectionId,
                cachedGenerationId);
    }

    /**
     * @param latestCollectionId The latest collection id of the CMP library.
     * @param cachedCollectionId The last collection id Picker DB was synced with, either fully
     *                           or partially.
     * @return true if a full sync with reset is pending, else false.
     */
    private boolean isFullSyncWithResetRequired(
            @Nullable String latestCollectionId,
            @Nullable String cachedCollectionId) {
        return !Objects.equals(latestCollectionId, cachedCollectionId);
    }

    /**
     * @param latestCollectionId The latest collection id of the CMP library.
     * @param cachedCollectionId The last collection id Picker DB was synced with, either fully
     *                           or partially.
     * @param cachedGenerationId The last generation id Picker DB was synced with.
     * @return true if a resumable full sync is pending, else false.
     */
    private boolean isFullSyncWithoutResetRequired(
            @Nullable String latestCollectionId,
            @Nullable String cachedCollectionId,
            long cachedGenerationId) {
        return Objects.equals(latestCollectionId, cachedCollectionId)
                && cachedGenerationId == DEFAULT_GENERATION;
    }

    private void updateLatestKnownCollectionInfoLocked(
            boolean isLocal,
            @Nullable ProviderCollectionInfo latestCollectionInfo) {
        if (isLocal) {
            mLatestLocalProviderCollectionInfo = latestCollectionInfo;
        } else {
            mLatestCloudProviderCollectionInfo = latestCollectionInfo;
        }
    }

    /**
     * Generates a key for shared preferences by appending the specified key
     * to the prefix corresponding to the local or cloud provider.
     *
     * @param isLocal {@code true} to use the local provider prefix,
     * {@code false} to use the cloud provider prefix.
     * @param key the specific key to append to the provider's prefix.
     * @return a complete key to used to query shared preferences.
     */
    public static String getPrefsKey(boolean isLocal, String key) {
        return (isLocal ? PREFS_KEY_LOCAL_PREFIX : PREFS_KEY_CLOUD_PREFIX) + key;
    }

    private Cursor query(Uri uri, Bundle extras) {
        return mContext.getContentResolver().query(uri, /* projection */ null, extras,
                /* cancellationSignal */ null);
    }

    /**
     * Creates a matching {@link PickerDbFacade.DbWriteOperation} for the given
     * {@link OperationType}.
     *
     * @param op {@link OperationType} Which type of paged operation to begin.
     * @param authority The authority string of the sync provider.
     * @param albumId An {@link Nullable} AlbumId for album related operations.
     * @throws IllegalArgumentException When an unexpected op type is encountered.
     */
    private PickerDbFacade.DbWriteOperation beginPagedOperation(
            @OperationType int op, String authority, @Nullable String albumId)
            throws IllegalArgumentException {
        switch (op) {
            case OPERATION_ADD_MEDIA:
                return mDbFacade.beginAddMediaOperation(authority);
            case OPERATION_ADD_ALBUM:
                Objects.requireNonNull(
                        albumId, "Cannot begin an AddAlbum operation without albumId");
                return mDbFacade.beginAddAlbumMediaOperation(authority, albumId);
            case OPERATION_REMOVE_MEDIA:
                return mDbFacade.beginRemoveMediaOperation(authority);
            default:
                throw new IllegalArgumentException(
                        "Cannot begin a paged operation without an expected operation type.");
        }
    }

    /**
     * Executes a page-by-page sync from the provider.
     *
     * @param uri The uri to query for a cursor.
     * @param expectedMediaCollectionId The expected media collection id.
     * @param expectedHonoredArgs The arguments that are expected to be present in cursors fetched
     *     from the provider.
     * @param queryArgs Any query arguments that are to be passed to the provider when fetching the
     *     cursor.
     * @param resumeKey The resumable operation key. This is used to check for previously failed
     *     operations so they can be resumed at the last successful page, and also to save progress
     *     between pages.
     * @param op The DbWriteOperation type. {@link OperationType}
     * @param authority The authority string of the provider to sync with.
     * @param cancellationSignal CancellationSignal used to abort the sync.
     * @throws RequestObsoleteException When the sync is interrupted due to the provider
     *     changing.
     * @return the total number of rows synced.
     */
    private int executePagedSync(
            Uri uri,
            String expectedMediaCollectionId,
            List<String> expectedHonoredArgs,
            Bundle queryArgs,
            @Nullable String resumeKey,
            @OperationType int op,
            String authority,
            Boolean isLocal,
            @Nullable CancellationSignal cancellationSignal)
            throws RequestObsoleteException, UnableToAcquireLockException, WorkCancelledException {
        return executePagedSync(
                uri,
                expectedMediaCollectionId,
                expectedHonoredArgs,
                queryArgs,
                resumeKey,
                op,
                authority,
                isLocal,
                /* albumId=*/ null,
                cancellationSignal);
    }

    /**
     * Executes a page-by-page sync from the provider.
     *
     * @param uri The uri to query for a cursor.
     * @param expectedMediaCollectionId The expected media collection id.
     * @param expectedHonoredArgs The arguments that are expected to be present in cursors fetched
     *     from the provider.
     * @param queryArgs Any query arguments that are to be passed to the provider when fetching the
     *     cursor.
     * @param resumeKey The resumable operation key. This is used to check for previously failed
     *     operations so they can be resumed at the last successful page, and also to save progress
     *     between pages.
     * @param op The DbWriteOperation type. {@link OperationType}
     * @param authority The authority string of the provider to sync with.
     * @param albumId A {@link Nullable} albumId for album related operations.
     * @param cancellationSignal CancellationSignal used to abort the sync.
     * @throws RequestObsoleteException When the sync is interrupted due to the provider
     *     changing.
     * @return the total number of rows synced.
     */
    private int executePagedSync(
            Uri uri,
            String expectedMediaCollectionId,
            List<String> expectedHonoredArgs,
            Bundle queryArgs,
            @Nullable String resumeKey,
            @OperationType int op,
            String authority,
            Boolean isLocal,
            @Nullable String albumId,
            @Nullable CancellationSignal cancellationSignal)
            throws RequestObsoleteException, UnableToAcquireLockException, WorkCancelledException {
        Trace.beginSection(traceSectionName("executePagedSync"));

        try {
            int totalRowcount = 0;
            // Set to check the uniqueness of tokens across pages.
            Set<String> tokens = new ArraySet<>();

            String nextPageToken = getPageTokenFromResumeKey(resumeKey);
            if (nextPageToken != null) {
                Log.i(
                        TAG,
                        String.format(
                                "Resumable operation found for %s, resuming with page token %s",
                                resumeKey, nextPageToken));
            }

            do {
                // At the top of each loop check to see if we've received a CancellationSignal
                // to stop the paged sync.
                if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                    throw new WorkCancelledException(
                            "Aborting sync: cancellationSignal was received");
                }

                String updateDateTakenMs = null;
                if (nextPageToken != null) {
                    queryArgs.putString(EXTRA_PAGE_TOKEN, nextPageToken);
                }

                try (Cursor cursor = query(uri, queryArgs)) {
                    nextPageToken =
                            validateCursor(
                                    cursor, expectedMediaCollectionId, expectedHonoredArgs, tokens);

                    try (PickerDbFacade.DbWriteOperation operation =
                            beginPagedOperation(op, authority, albumId)) {
                        int writeCount = operation.execute(cursor);

                        if (!isLocal) {
                            // Ensure the cloud provider hasn't change out from underneath the
                            // running sync. If it has, we need to stop syncing.
                            String currentCloudProvider = getCloudProviderWithTimeout();
                            if (TextUtils.isEmpty(currentCloudProvider)
                                    || !currentCloudProvider.equals(authority)) {

                                throw new RequestObsoleteException(
                                        String.format(
                                                "Aborting sync: the CloudProvider seems to have"
                                                        + " changed mid-sync. Old: %s Current: %s",
                                                authority, currentCloudProvider));
                            }
                        }

                        operation.setSuccess();
                        totalRowcount += writeCount;

                        if (cursor.getCount() > 0) {
                            // Before the cursor is closed pull the date taken ms for the first row.
                            updateDateTakenMs = getFirstDateTakenMsInCursor(cursor);

                            // If the cursor count is not null and the date taken field is not
                            // present in the cursor, fallback on the operation to provide the date
                            // taken.
                            if (updateDateTakenMs == null) {
                                updateDateTakenMs = getFirstDateTakenMsFromOperation(operation);
                            }
                        }
                    }
                } catch (IllegalArgumentException ex) {
                    Log.e(TAG, String.format("Failed to open DbWriteOperation for op: %d", op), ex);
                    return -1;
                }

                // Keep track of the next page token in case this operation crashes and is
                // later resumed.
                rememberNextPageToken(nextPageToken, resumeKey);

                // Emit notification that new data has arrived in the database.
                if (updateDateTakenMs != null) {
                    Uri notification = buildNotificationUri(op, albumId, updateDateTakenMs);

                    if (notification != null) {
                        mContext.getContentResolver()
                                .notifyChange(/* itemUri= */ notification, /* observer= */ null);
                    }
                }

                // Only send a media update notification if the media table is getting updated.
                if (albumId == null) {
                    PickerNotificationSender.notifyMediaChange(mContext);
                } else {
                    PickerNotificationSender.notifyAlbumMediaChange(mContext, authority, albumId);
                }
            } while (nextPageToken != null);

            Log.i(
                    TAG,
                    "Paged sync successful. QueryArgs: "
                            + queryArgs
                            + " Total Rows: "
                            + totalRowcount);
            return totalRowcount;
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Extracts the {@link MediaColumns.DATE_TAKEN_MILLIS} from the first row in the cursor.
     *
     * @param cursor The cursor to read from.
     * @return Either the column value if it exists, or {@code null} if it doesn't.
     */
    @Nullable
    private String getFirstDateTakenMsInCursor(Cursor cursor) {
        if (cursor.moveToFirst()) {
            return getCursorString(cursor, MediaColumns.DATE_TAKEN_MILLIS);
        }
        return null;
    }

    /**
     * Extracts the first row's date taken from the operation. Note that all functions may not
     * implement this method.
     */
    private String getFirstDateTakenMsFromOperation(PickerDbFacade.DbWriteOperation op) {
        final long firstDateTakenMillis = op.getFirstDateTakenMillis();

        return firstDateTakenMillis == Long.MIN_VALUE
                ? null
                : Long.toString(firstDateTakenMillis);
    }

    /**
     * Assembles a ContentObserver notification uri for the given operation.
     *
     * @param op {@link OperationType} the operation to notify has completed.
     * @param albumId An optional album id if this is an album based operation.
     * @param dateTakenMs The notification data; the {@link MediaColumns.DATE_TAKEN_MILLIS} of the
     *     first row updated.
     * @return the assembled notification uri.
     */
    @Nullable
    private Uri buildNotificationUri(
            @NonNull @OperationType int op,
            @Nullable String albumId,
            @Nullable String dateTakenMs) {

        Objects.requireNonNull(
                dateTakenMs, "Cannot notify subscribers without a date taken timestamp.");

        // base: content://media/picker_internal/
        Uri.Builder builder = PICKER_INTERNAL_URI.buildUpon().appendPath(UPDATE);

        switch (op) {
            case OPERATION_ADD_MEDIA:
                // content://media/picker_internal/update/media
                builder.appendPath(MEDIA);
                break;
            case OPERATION_ADD_ALBUM:
                // content://media/picker_internal/update/album_content/${albumId}
                builder.appendPath(ALBUM_CONTENT);
                builder.appendPath(albumId);
                break;
            case OPERATION_REMOVE_MEDIA:
                if (albumId != null) {
                    // content://media/picker_internal/update/album_content/${albumId}
                    builder.appendPath(ALBUM_CONTENT);
                    builder.appendPath(albumId);
                } else {
                    // content://media/picker_internal/update/media
                    builder.appendPath(MEDIA);
                }
                break;
            default:
                Log.w(
                        TAG,
                        String.format(
                                "Requested operation (%d) is not supported for notifications.",
                                op));
                return null;
        }

        builder.appendPath(dateTakenMs);
        return builder.build();
    }

    /**
     * Get the default {@link CloudProviderInfo} at {@link PickerSyncController} construction
     */
    @VisibleForTesting
    CloudProviderInfo getDefaultCloudProviderInfo(@Nullable String lastProvider) {
        final List<CloudProviderInfo> providers = getAvailableCloudProviders();

        if (providers.size() == 1) {
            Log.i(TAG, "Only 1 cloud provider found, hence " + providers.get(0).authority
                    + " is the default");
            return providers.get(0);
        } else {
            Log.i(TAG, "Found " + providers.size() + " available Cloud Media Providers.");
        }

        if (lastProvider != null) {
            for (CloudProviderInfo provider : providers) {
                if (Objects.equals(provider.authority, lastProvider)) {
                    return provider;
                }
            }
        }

        final String defaultProviderPkg = mConfigStore.getDefaultCloudProviderPackage();
        if (defaultProviderPkg != null) {
            Log.i(TAG, "Default Cloud-Media-Provider package is " + defaultProviderPkg);

            for (CloudProviderInfo provider : providers) {
                if (provider.matches(defaultProviderPkg)) {
                    return provider;
                }
            }
        } else {
            Log.i(TAG, "Default Cloud-Media-Provider is not set.");
        }

        // No default set or default not installed
        return CloudProviderInfo.EMPTY;
    }

    private static String traceSectionName(@NonNull String method) {
        return "PSC." + method;
    }

    private static String traceSectionName(@NonNull String method, boolean isLocal) {
        return traceSectionName(method)
                + "[" + (isLocal ? "local" : "cloud") + ']';
    }

    private static String validateCursor(Cursor cursor, String expectedMediaCollectionId,
            List<String> expectedHonoredArgs, Set<String> usedPageTokens) {
        final Bundle bundle = cursor.getExtras();

        if (bundle == null) {
            throw new IllegalStateException("Unable to verify the media collection id");
        }

        final String mediaCollectionId = bundle.getString(EXTRA_MEDIA_COLLECTION_ID);
        final String pageToken = bundle.getString(EXTRA_PAGE_TOKEN);
        List<String> honoredArgs = bundle.getStringArrayList(EXTRA_HONORED_ARGS);
        if (honoredArgs == null) {
            honoredArgs = new ArrayList<>();
        }

        if (expectedMediaCollectionId != null
                && !expectedMediaCollectionId.equals(mediaCollectionId)) {
            throw new IllegalStateException("Mismatched media collection id. Expected: "
                    + expectedMediaCollectionId + ". Found: " + mediaCollectionId);
        }

        if (!honoredArgs.containsAll(expectedHonoredArgs)) {
            throw new IllegalStateException("Unspecified honored args. Expected: "
                    + Arrays.toString(expectedHonoredArgs.toArray())
                    + ". Found: " + Arrays.toString(honoredArgs.toArray()));
        }

        if (usedPageTokens.contains(pageToken)) {
            throw new IllegalStateException("Found repeated page token: " + pageToken);
        } else {
            usedPageTokens.add(pageToken);
        }

        return pageToken;
    }

    private static class SyncRequestParams {
        static final SyncRequestParams SYNC_REQUEST_NONE = new SyncRequestParams(SYNC_TYPE_NONE);
        static final SyncRequestParams SYNC_REQUEST_MEDIA_RESET =
                new SyncRequestParams(SYNC_TYPE_MEDIA_RESET);

        final int syncType;
        // Only valid for SYNC_TYPE_INCREMENTAL
        final long syncGeneration;
        // Only valid for SYNC_TYPE_[INCREMENTAL|FULL]
        final Bundle latestMediaCollectionInfo;
        // Only valid for sync triggered by opening photopicker activity.
        // Not valid for proactive syncs.
        final int mPageSize;

        SyncRequestParams(@SyncType int syncType) {
            this(syncType, /* syncGeneration */ 0, /* latestMediaCollectionInfo */ null,
                    /*pageSize */ PAGE_SIZE);
        }

        SyncRequestParams(@SyncType int syncType, long syncGeneration,
                Bundle latestMediaCollectionInfo, int pageSize) {
            this.syncType = syncType;
            this.syncGeneration = syncGeneration;
            this.latestMediaCollectionInfo = latestMediaCollectionInfo;
            this.mPageSize = pageSize;
        }

        String getMediaCollectionId() {
            return latestMediaCollectionInfo.getString(MEDIA_COLLECTION_ID);
        }

        static SyncRequestParams forNone() {
            return SYNC_REQUEST_NONE;
        }

        static SyncRequestParams forResetMedia() {
            return SYNC_REQUEST_MEDIA_RESET;
        }

        static SyncRequestParams forFullMediaWithReset(@NonNull Bundle latestMediaCollectionInfo) {
            return new SyncRequestParams(SYNC_TYPE_MEDIA_FULL_WITH_RESET, /* generation */ 0,
                    latestMediaCollectionInfo, /*pageSize */ PAGE_SIZE);
        }

        static SyncRequestParams forFullMedia(@NonNull Bundle latestMediaCollectionInfo) {
            return new SyncRequestParams(SYNC_TYPE_MEDIA_FULL, /* generation */ 0,
                    latestMediaCollectionInfo, /*pageSize */ PAGE_SIZE);
        }

        static SyncRequestParams forIncremental(long generation, Bundle latestMediaCollectionInfo) {
            return new SyncRequestParams(SYNC_TYPE_MEDIA_INCREMENTAL, generation,
                    latestMediaCollectionInfo, /*pageSize */ PAGE_SIZE);
        }

        @Override
        public String toString() {
            return "SyncRequestParams{type=" + syncTypeToString(syncType)
                    + ", gen=" + syncGeneration + ", latest=" + latestMediaCollectionInfo
                    + ", pageSize=" + mPageSize + '}';
        }
    }

    private static String syncTypeToString(@SyncType int syncType) {
        switch (syncType) {
            case SYNC_TYPE_NONE:
                return "NONE";
            case SYNC_TYPE_MEDIA_INCREMENTAL:
                return "MEDIA_INCREMENTAL";
            case SYNC_TYPE_MEDIA_FULL:
                return "MEDIA_FULL";
            case SYNC_TYPE_MEDIA_RESET:
                return "MEDIA_RESET";
            case SYNC_TYPE_MEDIA_FULL_WITH_RESET:
                return "MEDIA_FULL_WITH_RESET";
            default:
                return "Unknown";
        }
    }

    private static boolean isCloudProviderUnset(@Nullable String lastProviderAuthority) {
        return Objects.equals(lastProviderAuthority, PREFS_VALUE_CLOUD_PROVIDER_UNSET);
    }

    /**
     * Print the {@link PickerSyncController} state into the given stream.
     */
    public void dump(PrintWriter writer) {
        writer.println("Picker sync controller state:");

        writer.println("  mLocalProvider=" + getLocalProvider());
        writer.println("  mCloudProviderInfo=" + getCurrentCloudProviderInfo());
        writer.println("  allAvailableCloudProviders="
                + CloudProviderUtils.getAllAvailableCloudProviders(mContext, mConfigStore));

        writer.println("  cachedAuthority="
                + mUserPrefs.getString(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY, /* defValue */ null));
        writer.println("  cachedLocalMediaCollectionInfo="
                + getCachedMediaCollectionInfo(/* isLocal */ true));
        writer.println("  cachedCloudMediaCollectionInfo="
                + getCachedMediaCollectionInfo(/* isLocal */ false));
    }

    /**
     * Returns the associated Picker DB instance.
     */
    public PickerDbFacade getDbFacade() {
        return mDbFacade;
    }

    /**
     * Returns true when all the following conditions are true:
     * 1. Current cloud provider is not null.
     * 2. Current cloud provider is present in the given providers list.
     * 3. Database has currently enabled cloud provider queries.
     * 4. The given provider is equal to the current provider.
     */
    public boolean shouldQueryCloudMedia(
            @NonNull List<String> providers,
            @Nullable String cloudProvider) {
        return cloudProvider != null
                && providers.contains(cloudProvider)
                && shouldQueryCloudMedia(cloudProvider);
    }

    /**
     * Returns true when all the following conditions are true:
     * 1. Current cloud provider is not null.
     * 2. Database has currently enabled cloud provider queries.
     */
    public boolean shouldQueryCloudMedia(
            @Nullable String cloudProvider) {
        try (CloseableReentrantLock ignored =
                     mPickerSyncLockManager.tryLock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            return cloudProvider != null
                    && cloudProvider.equals(getCloudProviderWithTimeout())
                    && cloudProvider.equals(mDbFacade.getCloudProvider());
        } catch (UnableToAcquireLockException e) {
            Log.e(TAG, "Could not check if cloud media should be queried", e);
            return false;
        }
    }

    /**
     * Returns true when all the following conditions are true:
     * 1. Input cloud provider is not null.
     * 2. Input cloud provider is present in the given providers list.
     * 3. Input cloud provider is also the current cloud provider.
     * 4. Search feature is enabled for the given cloud provider.
     * Otherwise returns false.
     */
    public boolean shouldQueryCloudMediaForSearch(
            @NonNull Set<String> providers,
            @Nullable String cloudProvider) {
        try (CloseableReentrantLock ignored =
                     mPickerSyncLockManager.tryLock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            return cloudProvider != null
                    && providers.contains(cloudProvider)
                    && cloudProvider.equals(getCloudProviderWithTimeout())
                    && getSearchState().isCloudSearchEnabled(mContext, cloudProvider);
        } catch (UnableToAcquireLockException e) {
            Log.e(TAG, "Could not check if cloud media should be queried", e);
            return false;
        }
    }

    /**
     * Returns true when all the following conditions are true:
     * 1. Current local provider is not null.
     * 2. Current local provider is present in the given providers list.
     * 3. Search feature is enabled for the current local provider.
     * Otherwise returns false.
     */
    public boolean shouldQueryLocalMediaForSearch(
            @NonNull Set<String> providers) {
        final String localProvider = getLocalProvider();
        return localProvider != null
                && providers.contains(localProvider)
                && getSearchState().isLocalSearchEnabled();
    }

    /**
     * @param providers List of providers for the current request
     * @return Returns whether the local sync is possible
     * Returns true if all of the following are true:
     *  -The retrieved local provider is not null
     *  -The input list of providers contains the current provider
     *  -The CMP implements categories API
     *  Otherwise, we get false
     */
    public boolean shouldQueryLocalMediaSets(@NonNull Set<String> providers) {
        Objects.requireNonNull(providers);
        final String localProvider = getLocalProvider();
        return localProvider != null
                && providers.contains(localProvider)
                && getCategoriesState().areCategoriesEnabled(mContext, localProvider);
    }

    /**
     * @param providers Set of providers for the current request
     * @param cloudProvider The cloudAuthority to query for
     * @return Returns whether the local sync is possible
     * Returns true if all of the following are true:
     *  -The given cloud provider is not null
     *  -The input list of providers contains the current cloud provider
     *  -Input cloud provider is the same as the current cloud provider
     *  -The CMP implements categories API
     *  Otherwise, we get false
     */
    public boolean shouldQueryCloudMediaSets(
            @NonNull Set<String> providers,
            @Nullable String cloudProvider) {
        Objects.requireNonNull(providers);
        try (CloseableReentrantLock ignored =
                     mPickerSyncLockManager.tryLock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
            return cloudProvider != null
                    && providers.contains(cloudProvider)
                    && cloudProvider.equals(getCloudProviderWithTimeout())
                    && getCategoriesState().areCategoriesEnabled(mContext, cloudProvider);
        } catch (UnableToAcquireLockException e) {
            Log.e(TAG, "Could not check if cloud media sets are to be queried", e);
            return false;
        }
    }

    @NonNull
    public CategoriesState getCategoriesState() {
        return mCategoriesState;
    }

    /**
     * Disable cloud queries if the new collection id received from the cloud provider in the media
     * event notification is different than the cached value.
     */
    public void handleMediaEventNotification(Boolean localOnly, @NonNull String authority,
            @Nullable String newCollectionId) {
        if (!localOnly && newCollectionId != null) {
            try (CloseableReentrantLock ignored = mPickerSyncLockManager
                    .tryLock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
                final String currentCloudProvider = getCloudProviderWithTimeout();
                if (authority.equals(currentCloudProvider) && !newCollectionId
                        .equals(mLatestCloudProviderCollectionInfo.getCollectionId())) {
                    disablePickerCloudMediaQueries(/* isLocal */ false);
                }
            } catch (UnableToAcquireLockException e) {
                Log.e(TAG, "Could not handle media event notification", e);
            }
        }
    }

    /**
     * Executes a sync for grants from the external database to the picker database.
     *
     * This should only be called when the picker is in MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP
     * action. It requires a valid packageUid and mimeTypes with which the picker was invoked to
     * ensure that the sync only happens for the items that:
     * <li>match the mimeTypes</li>
     * <li>are granted to the package and userId corresponding to the provided packageUid</li>
     *
     * It fetches the rows from media_grants table in the external.db that matches the criteria and
     * inserts them in the media_grants table in picker.db
     */
    public void executeGrantsSync(
            boolean shouldSyncGrants, int packageUid,
            String[] mimeTypes) {
        // empty the grants table.
        executeClearAllGrants(packageUid);

        // sync all grants into the table
        if (shouldSyncGrants) {
            final ContentResolver resolver = mContext.getContentResolver();
            try (ContentProviderClient client = resolver.acquireContentProviderClient(AUTHORITY)) {
                assert client != null;
                final Bundle extras = new Bundle();
                extras.putInt(Intent.EXTRA_UID, packageUid);
                extras.putStringArray(EXTRA_MEDIA_GRANTS_MIME_TYPES, mimeTypes);
                try (Cursor c = client.query(Uri.parse(MEDIA_GRANTS_URI_PATH),
                        /* projection= */ null,
                        /* queryArgs= */ extras,
                        null)) {
                    Trace.beginSection(traceSectionName(
                            "executeGrantsSync", /* isLocal */ true));
                    try (PickerDbFacade.DbWriteOperation operation =
                                 mDbFacade.beginInsertGrantsOperation()) {
                        int grantsInsertedCount = operation.execute(c);
                        operation.setSuccess();
                        Log.i(TAG, "Successfully executed grants sync operation operation."
                                + " Result count: " + grantsInsertedCount);
                    } finally {
                        Trace.endSection();
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Remote exception received while fetching grants. " +  e.getMessage());
            }
        }
    }

    /**
     * Before a sync for grants is initiated, this method is used to clear any stale grants that
     * exists in the database.
     *
     * This should only be called when the picker is in MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP
     * action. It requires a valid packageUid with which the picker was invoked to
     * ensure that all the rows that represents the items granted to the package and userId
     * corresponding to the provided packageUid are cleared from the media_grants table in picker.db
     */
    private void executeClearAllGrants(int packageUid) {
        Trace.beginSection(traceSectionName("executeClearAllGrants", /* isLocal */ true));
        int userId = uidToUserId(packageUid);
        String[] packageNames = getPackageNameFromUid(mContext, packageUid);

        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginClearGrantsOperation(packageNames, userId)) {
            final int clearedGrantsCount = operation.execute(/* cursor */ null);
            operation.setSuccess();

            Log.i(TAG, "Successfully executed clear grants operation."
                    + " Result count: " + clearedGrantsCount);
        } catch (SQLException e) {
            Log.e(TAG, "Unable to clear grants for this session: " + e.getMessage());
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Returns an Array of packageNames corresponding to the input package uid.
     */
    public static String[] getPackageNameFromUid(Context context, int callingPackageUid) {
        final PackageManager pm = context.getPackageManager();
        return pm.getPackagesForUid(callingPackageUid);
    }

    /**
     * Generates and returns userId from the input package uid.
     */
    public static int uidToUserId(int uid) {
        // Get the userId from packageUid as the initiator could be a cloned app, which
        // accesses Media via MP of its parent user and Binder's callingUid reflects
        // the latter.
        return uid / PER_USER_RANGE;
    }
}
