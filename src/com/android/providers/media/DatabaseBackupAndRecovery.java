/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.providers.media;

import static com.android.providers.media.DatabaseHelper.DATA_MEDIA_XATTR_DIRECTORY_PATH;
import static com.android.providers.media.DatabaseHelper.EXTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX;
import static com.android.providers.media.DatabaseHelper.EXTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX;
import static com.android.providers.media.DatabaseHelper.INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX;
import static com.android.providers.media.DatabaseHelper.INTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__STATUS__BACKUP_MISSING;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__STATUS__FUSE_DAEMON_TIMEOUT;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__STATUS__GET_BACKUP_DATA_FAILURE;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__STATUS__OTHER_ERROR;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__STATUS__SUCCESS;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__STATUS__VOLUME_NOT_ATTACHED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__EXTERNAL_PRIMARY;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__INTERNAL;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__PUBLIC;
import static com.android.providers.media.util.Logging.TAG;
import static com.android.providers.media.flags.Flags.enableStableUrisForExternalPrimaryVolume;
import static com.android.providers.media.flags.Flags.enableStableUrisForPublicVolume;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.android.providers.media.dao.FileRow;
import com.android.providers.media.fuse.FuseDaemon;
import com.android.providers.media.stableuris.dao.BackupIdRow;
import com.android.providers.media.util.StringUtils;

import com.google.common.base.Strings;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * To ensure that the ids of MediaStore database uris are stable and reliable.
 */
public class DatabaseBackupAndRecovery {

    private static final String LOWER_FS_RECOVERY_DIRECTORY_PATH =
            "/data/media/" + UserHandle.myUserId() + "/.transforms/recovery";

    /**
     * Path for storing owner id to owner package identifier relation and vice versa.
     * Lower file system path is used as upper file system does not support xattrs.
     */
    private static final String OWNER_RELATION_LOWER_FS_BACKUP_PATH =
            "/data/media/" + UserHandle.myUserId() + "/.transforms/recovery/leveldb-ownership";

    private static final String INTERNAL_VOLUME_LOWER_FS_BACKUP_PATH =
            LOWER_FS_RECOVERY_DIRECTORY_PATH + "/leveldb-internal";

    private static final String EXTERNAL_PRIMARY_VOLUME_LOWER_FS_BACKUP_PATH =
            LOWER_FS_RECOVERY_DIRECTORY_PATH + "/leveldb-external_primary";

    /**
     * Every LevelDB table name starts with this prefix.
     */
    private static final String LEVEL_DB_PREFIX = "leveldb-";

    private static final String OWNERSHIP_TABLE_NAME = LEVEL_DB_PREFIX + "ownership";

    /**
     * Frequency at which next value of owner id is backed up in the external storage.
     */
    private static final int NEXT_OWNER_ID_BACKUP_FREQUENCY = 50;

    /**
     * Start value used for next owner id.
     */
    private static final int NEXT_OWNER_ID_DEFAULT_VALUE = 0;

    /**
     * Key name of xattr used to set next owner id on ownership DB.
     */
    private static final String NEXT_OWNER_ID_XATTR_KEY = "user.nextownerid";

    /**
     * Key name of xattr used to store last modified generation number.
     */
    private static final String LAST_BACKEDUP_GENERATION_XATTR_KEY = "user.lastbackedgeneration";

    /**
     * Key name of xattr used to store a public volume recovery flag.
     */
    private static final String PUBLIC_VOLUME_RECOVERY_FLAG_XATTR_KEY
            = "user.publicvolumerecoveryflag";

    /**
     * External primary storage root path for given user.
     */
    private static final String EXTERNAL_PRIMARY_ROOT_PATH =
            "/storage/emulated/" + UserHandle.myUserId();

    /**
     * Array of columns backed up in external storage.
     */
    private static final String[] QUERY_COLUMNS = new String[]{
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.IS_FAVORITE,
            MediaStore.Files.FileColumns.IS_PENDING,
            MediaStore.Files.FileColumns.IS_TRASHED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns._USER_ID,
            MediaStore.Files.FileColumns.DATE_EXPIRES,
            MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME,
            MediaStore.Files.FileColumns.GENERATION_MODIFIED,
            MediaStore.Files.FileColumns.VOLUME_NAME
    };

    /**
     * Wait time of 20 seconds in millis.
     */
    private static final long WAIT_TIME_20_SECONDS_IN_MILLIS = 20000;

    /**
     * Number of records to read from leveldb in a JNI call.
     */
    protected static final int LEVEL_DB_READ_LIMIT = 100;


    /**
     * Re-entrant lock to ensure sequential update to leveldb by background threads.
     */
    private final ReentrantLock mLevelDbUpdateLock = new ReentrantLock();

    /**
     * Stores cached value of next owner id. This helps in improving performance by backing up next
     * row id less frequently in the external storage.
     */
    private AtomicInteger mNextOwnerId;

    /**
     * Stores value of next backup of owner id.
     */
    private AtomicInteger mNextOwnerIdBackup;
    private final ConfigStore mConfigStore;
    private final VolumeCache mVolumeCache;
    private Set<String> mSetupCompleteVolumes = ConcurrentHashMap.newKeySet();

    // Flag only used to enable/disable feature for testing
    private boolean mIsStableUriEnabledForInternal = false;

    // Flag only used to enable/disable feature for testing
    private boolean mIsStableUriEnabledForExternal = false;

    // Flag only used to enable/disable feature for testing
    private boolean mIsStableUrisEnabledForPublic = false;

    private static Map<String, String> sOwnerIdRelationMap;

    public static final String STABLE_URI_INTERNAL_PROPERTY =
            "persist.sys.fuse.backup.internal_db_backup";

    private static boolean STABLE_URI_INTERNAL_PROPERTY_VALUE = true;

    public static final String STABLE_URI_EXTERNAL_PROPERTY =
            "persist.sys.fuse.backup.external_volume_backup";

    private static boolean STABLE_URI_EXTERNAL_PROPERTY_VALUE = true;

    public static final String STABLE_URI_PUBLIC_PROPERTY =
            "persist.sys.fuse.backup.public_db_backup";

    private static boolean STABLE_URI_PUBLIC_PROPERTY_VALUE = true;

    DatabaseBackupAndRecovery(ConfigStore configStore, VolumeCache volumeCache) {
        mConfigStore = configStore;
        mVolumeCache = volumeCache;
    }

    /**
     * Returns true if migration and recovery code flow for stable uris is enabled for given volume.
     */
    boolean isStableUrisEnabled(String volumeName) {
        // Check if flags are enabled for test for internal volume
        if (MediaStore.VOLUME_INTERNAL.equalsIgnoreCase(volumeName)
                && mIsStableUriEnabledForInternal) {
            return true;
        }
        // Check if flags are enabled for test for external primary volume
        if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equalsIgnoreCase(volumeName)
                && mIsStableUriEnabledForExternal) {
            return true;
        }

        // Check if flags are enabled for test for public volume
        if (!MediaStore.VOLUME_INTERNAL.equalsIgnoreCase(volumeName)
                && !MediaStore.VOLUME_EXTERNAL_PRIMARY.equalsIgnoreCase(volumeName)
                && mIsStableUrisEnabledForPublic) {
            return true;
        }

        // Feature is disabled for below S due to vold mount issues.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }

        switch (volumeName) {
            case MediaStore.VOLUME_INTERNAL:
                return mIsStableUriEnabledForInternal
                        || mConfigStore.isStableUrisForInternalVolumeEnabled()
                        || SystemProperties.getBoolean(STABLE_URI_INTERNAL_PROPERTY,
                        /* defaultValue */ STABLE_URI_INTERNAL_PROPERTY_VALUE);
            case MediaStore.VOLUME_EXTERNAL_PRIMARY:
                return mIsStableUriEnabledForExternal
                        || mConfigStore.isStableUrisForExternalVolumeEnabled()
                        || enableStableUrisForExternalPrimaryVolume()
                        || SystemProperties.getBoolean(STABLE_URI_EXTERNAL_PROPERTY,
                        /* defaultValue */ STABLE_URI_EXTERNAL_PROPERTY_VALUE);
            default:
                // public volume
                return mIsStableUrisEnabledForPublic
                        || mConfigStore.isStableUrisForPublicVolumeEnabled()
                        || enableStableUrisForPublicVolume()
                        || SystemProperties.getBoolean(STABLE_URI_PUBLIC_PROPERTY,
                        /* defaultValue */ STABLE_URI_PUBLIC_PROPERTY_VALUE);
        }
    }

    /**
     * On device boot, leveldb setup is done as part of attachVolume call for primary external.
     * Also, on device config flag change, we check if flag is enabled, if yes, we proceed to
     * setup(no-op if connection already exists). So, we setup backup and recovery for internal
     * volume on Media mount signal of EXTERNAL_PRIMARY.
     */
    synchronized void setupVolumeDbBackupAndRecovery(String volumeName) {
        // Since internal volume does not have any fuse daemon thread, leveldb instance
        // for internal volume is created by fuse daemon thread of EXTERNAL_PRIMARY.
        if (MediaStore.VOLUME_INTERNAL.equalsIgnoreCase(volumeName)) {
            // Set backup only for external primary for now.
            return;
        }
        // Do not create leveldb instance if stable uris is not enabled for internal volume.
        if (!isStableUrisEnabled(MediaStore.VOLUME_INTERNAL)) {
            // Return if we are not supporting backup for internal volume
            return;
        }

        if (mSetupCompleteVolumes.contains(volumeName)) {
            // Return if setup is already done
            return;
        }

        final long startTime = SystemClock.elapsedRealtime();
        int vol = MediaStore.VOLUME_EXTERNAL_PRIMARY.equalsIgnoreCase(volumeName)
                ? MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__EXTERNAL_PRIMARY
                : MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__PUBLIC;
        try {
            if (!new File(LOWER_FS_RECOVERY_DIRECTORY_PATH).exists()) {
                new File(LOWER_FS_RECOVERY_DIRECTORY_PATH).mkdirs();
                Log.v(TAG, "Created recovery directory:" + LOWER_FS_RECOVERY_DIRECTORY_PATH);
            }
            FuseDaemon fuseDaemonExternalPrimary = getFuseDaemonForFileWithWait(new File(
                    DatabaseBackupAndRecovery.EXTERNAL_PRIMARY_ROOT_PATH));
            Log.d(TAG, "Received db backup Fuse Daemon for: " + volumeName);
            if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equalsIgnoreCase(volumeName) && (
                    isStableUrisEnabled(MediaStore.VOLUME_INTERNAL) || isStableUrisEnabled(
                            MediaStore.VOLUME_EXTERNAL_PRIMARY))) {
                // Setup internal and external volumes
                MediaProviderStatsLog.write(
                        MediaProviderStatsLog.BACKUP_SETUP_STATUS_REPORTED,
                        MediaProviderStatsLog.BACKUP_SETUP_STATUS_REPORTED__STATUS__ATTEMPTED, vol);
                fuseDaemonExternalPrimary.setupVolumeDbBackup();
                mSetupCompleteVolumes.add(volumeName);
                MediaProviderStatsLog.write(
                        MediaProviderStatsLog.BACKUP_SETUP_STATUS_REPORTED,
                        MediaProviderStatsLog.BACKUP_SETUP_STATUS_REPORTED__STATUS__SUCCESS, vol);
            } else if (isStableUrisEnabled(volumeName)) {
                // Setup public volume
                FuseDaemon fuseDaemonPublicVolume = getFuseDaemonForPath(
                        getFuseFilePathFromVolumeName(volumeName));
                MediaProviderStatsLog.write(
                        MediaProviderStatsLog.BACKUP_SETUP_STATUS_REPORTED,
                        MediaProviderStatsLog.BACKUP_SETUP_STATUS_REPORTED__STATUS__ATTEMPTED, vol);
                fuseDaemonPublicVolume.setupPublicVolumeDbBackup(volumeName);
                mSetupCompleteVolumes.add(volumeName);
                MediaProviderStatsLog.write(
                        MediaProviderStatsLog.BACKUP_SETUP_STATUS_REPORTED,
                        MediaProviderStatsLog.BACKUP_SETUP_STATUS_REPORTED__STATUS__SUCCESS, vol);
            } else {
                return;
            }
        } catch (Exception e) {
            MediaProviderStatsLog.write(
                    MediaProviderStatsLog.BACKUP_SETUP_STATUS_REPORTED,
                    MediaProviderStatsLog.BACKUP_SETUP_STATUS_REPORTED__STATUS__FAILURE, vol);
            Log.e(TAG, "Failure in setting up backup and recovery for volume: " + volumeName, e);
            return;
        } finally {
            Log.i(TAG, "Backup and recovery setup time taken in milliseconds:" + (
                    SystemClock.elapsedRealtime() - startTime));
        }
        Log.i(TAG, "Successfully set up backup and recovery for volume: " + volumeName);
    }

    /**
     * Backs up databases to external storage to ensure stable URIs.
     */
    void backupDatabases(DatabaseHelper internalDatabaseHelper,
            DatabaseHelper externalDatabaseHelper, CancellationSignal signal) {
        mLevelDbUpdateLock.lock();
        try {
            setupVolumeDbBackupAndRecovery(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Log.i(TAG, "Triggering database backup");
            backupInternalDatabase(internalDatabaseHelper, signal);
            backupExternalDatabase(externalDatabaseHelper,
                    MediaStore.VOLUME_EXTERNAL_PRIMARY, signal);

            for (MediaVolume mediaVolume : mVolumeCache.getExternalVolumes()) {
                if (mediaVolume.isPublicVolume()) {
                    setupVolumeDbBackupAndRecovery(mediaVolume.getName());
                    backupExternalDatabase(externalDatabaseHelper, mediaVolume.getName(), signal);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failure in backing up databases", e);
        } finally {
            mLevelDbUpdateLock.unlock();
        }
    }

    Optional<BackupIdRow> readDataFromBackup(String volumeName, String filePath) {
        if (!isStableUrisEnabled(volumeName)) {
            return Optional.empty();
        }

        try {
            final String data = getFuseDaemonForPath(getFuseFilePathFromVolumeName(volumeName))
                    .readBackedUpData(filePath);
            if (data == null || data.isEmpty()) {
                Log.w(TAG, "No backup found for path: " + filePath);
                return Optional.empty();
            }

            return Optional.of(BackupIdRow.deserialize(data));
        } catch (Exception e) {
            Log.e(TAG, "Failure in getting backed up data for filePath: " + filePath, e);
            return Optional.empty();
        }
    }

    private void backupInternalDatabase(DatabaseHelper internalDbHelper,
            CancellationSignal signal) {
        if (!isStableUrisEnabled(MediaStore.VOLUME_INTERNAL)
                || internalDbHelper.isDatabaseRecovering()) {
            return;
        }

        if (!mSetupCompleteVolumes.contains(MediaStore.VOLUME_EXTERNAL_PRIMARY)) {
            Log.w(TAG,
                "Setup is not present for backup of internal and external primary volume.");
            return;
        }

        FuseDaemon fuseDaemon;
        try {
            fuseDaemon = getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH);
        } catch (FileNotFoundException e) {
            Log.e(TAG,
                    "Fuse Daemon not found for primary external storage, skipping backing up of "
                            + "internal database.",
                    e);
            return;
        }

        internalDbHelper.runWithTransaction((db) -> {
            try (Cursor c = db.query(true, "files", QUERY_COLUMNS, null, null, null, null, null,
                    null, signal)) {
                while (c.moveToNext()) {
                    backupDataValues(fuseDaemon, c);
                }
                Log.d(TAG, String.format(Locale.ROOT,
                        "Backed up %d rows of internal database to external storage on idle "
                                + "maintenance.",
                        c.getCount()));
            } catch (Exception e) {
                Log.e(TAG, "Failure in backing up internal database to external storage.", e);
            }
            return null;
        });
    }

    private void backupExternalDatabase(DatabaseHelper externalDbHelper,
            String volumeName, CancellationSignal signal) {
        if (!isStableUrisEnabled(volumeName)
                || externalDbHelper.isDatabaseRecovering()) {
            return;
        }

        if (!mSetupCompleteVolumes.contains(volumeName)) {
            return;
        }

        FuseDaemon fuseDaemonExternalPrimary;
        try {
            fuseDaemonExternalPrimary = getFuseDaemonForFileWithWait(
                    new File(EXTERNAL_PRIMARY_ROOT_PATH));
        } catch (Exception e) {
            Log.e(TAG,
                    "Error occurred while retrieving the Fuse Daemon for the external primary, "
                            + "skipping backing up of "
                            + volumeName, e);
            return;
        }
        FuseDaemon fuseDaemonPublicVolume;
        if (!isInternalOrExternalPrimary(volumeName)) {
            try {
                fuseDaemonPublicVolume = getFuseDaemonForFileWithWait(new File(
                        getFuseFilePathFromVolumeName(volumeName)));
            } catch (Exception e) {
                Log.e(TAG,
                        "Error occurred while retrieving the Fuse Daemon for "
                                + getFuseFilePathFromVolumeName(volumeName)
                                + ", skipping backing up of " + volumeName,
                        e);
                return;
            }
        } else {
            fuseDaemonPublicVolume = null;
        }

        final String backupPath =
                LOWER_FS_RECOVERY_DIRECTORY_PATH + "/" + LEVEL_DB_PREFIX + volumeName;
        long lastBackedGenerationNumber = getLastBackedGenerationNumber(backupPath);

        final String generationClause = MediaStore.Files.FileColumns.GENERATION_MODIFIED + " >= "
                + lastBackedGenerationNumber;
        final String volumeClause = MediaStore.Files.FileColumns.VOLUME_NAME + " = '"
                + volumeName + "'";
        final String selectionClause = generationClause + " AND " + volumeClause;

        externalDbHelper.runWithTransaction((db) -> {
            long maxGeneration = lastBackedGenerationNumber;
            Log.d(TAG, "Started to back up " + volumeName
                    + ", maxGeneration:" + maxGeneration);
            try (Cursor c = db.query(true, "files", QUERY_COLUMNS, selectionClause, null, null,
                    null, MediaStore.MediaColumns.GENERATION_MODIFIED + " ASC", null, signal)) {
                while (c.moveToNext()) {
                    if (signal != null && signal.isCanceled()) {
                        Log.i(TAG, "Received a cancellation signal during the DB "
                                + "backup process");
                        break;
                    }
                    if (isInternalOrExternalPrimary(volumeName)) {
                        backupDataValues(fuseDaemonExternalPrimary, c);
                    } else {
                        // public volume
                        backupDataValues(fuseDaemonExternalPrimary, fuseDaemonPublicVolume, c);
                    }
                    maxGeneration = Math.max(maxGeneration, c.getLong(9));
                }
                setXattr(backupPath, LAST_BACKEDUP_GENERATION_XATTR_KEY,
                        String.valueOf(maxGeneration - 1));
                Log.d(TAG, String.format(Locale.ROOT,
                        "Backed up %d rows of " + volumeName + " to external storage on idle "
                                + "maintenance.",
                        c.getCount()));
            } catch (Exception e) {
                Log.e(TAG, "Failure in backing up " + volumeName + " to external storage.", e);
                return null;
            }
            return null;
        });
    }

    private void backupDataValues(FuseDaemon fuseDaemon, Cursor c) throws IOException {
        backupDataValues(fuseDaemon, null, c);
    }

    private void backupDataValues(FuseDaemon externalPrimaryFuseDaemon,
            FuseDaemon publicVolumeFuseDaemon, Cursor c) throws IOException {
        final long id = c.getLong(0);
        final String data = c.getString(1);
        final boolean isFavorite = c.getInt(2) != 0;
        final boolean isPending = c.getInt(3) != 0;
        final boolean isTrashed = c.getInt(4) != 0;
        final int mediaType = c.getInt(5);
        final int userId = c.getInt(6);
        final String dateExpires = c.getString(7);
        final String ownerPackageName = c.getString(8);
        final String volumeName = c.getString(10);
        BackupIdRow backupIdRow = createBackupIdRow(externalPrimaryFuseDaemon, id, mediaType,
                isFavorite, isPending, isTrashed, userId, dateExpires, ownerPackageName);
        if (isInternalOrExternalPrimary(volumeName)) {
            externalPrimaryFuseDaemon.backupVolumeDbData(volumeName, data,
                    BackupIdRow.serialize(backupIdRow));
        } else {
            // public volume
            publicVolumeFuseDaemon.backupVolumeDbData(volumeName, data,
                    BackupIdRow.serialize(backupIdRow));
        }
    }

    void deleteBackupForVolume(String volumeName) {
        File dbFilePath = new File(
                String.format(Locale.ROOT, "%s/%s.db", LOWER_FS_RECOVERY_DIRECTORY_PATH,
                        LEVEL_DB_PREFIX + volumeName));
        if (dbFilePath.exists()) {
            dbFilePath.delete();
        }
    }

    String[] readBackedUpFilePaths(String volumeName, String lastReadValue, int limit)
            throws IOException, UnsupportedOperationException {
        if (!isStableUrisEnabled(volumeName)) {
            throw new UnsupportedOperationException("Stable Uris are not enabled");
        }

        return getFuseDaemonForPath(getFuseFilePathFromVolumeName(volumeName))
                .readBackedUpFilePaths(volumeName, lastReadValue, limit);
    }

    void updateNextRowIdXattr(DatabaseHelper helper, long id) {
        if (helper.isInternal()) {
            updateNextRowIdForInternal(helper, id);
            return;
        }

        if (!helper.isNextRowIdBackupEnabled()) {
            return;
        }

        Optional<Long> nextRowIdBackupOptional = helper.getNextRowId();
        if (!nextRowIdBackupOptional.isPresent()) {
            throw new RuntimeException(
                    String.format(Locale.ROOT, "Cannot find next row id xattr for %s.",
                            helper.getDatabaseName()));
        }

        if (id >= nextRowIdBackupOptional.get()) {
            helper.backupNextRowId(id);
        }
    }

    private long getLastBackedGenerationNumber(String backupPath) {
        // Read last backed up generation number
        Optional<Long> lastBackedUpGenNum = getXattrOfLongValue(
                backupPath, LAST_BACKEDUP_GENERATION_XATTR_KEY);
        long lastBackedGenerationNumber = lastBackedUpGenNum.isPresent()
                ? lastBackedUpGenNum.get() : 0;
        if (lastBackedGenerationNumber > 0) {
            Log.i(TAG, "Last backed up generation number for " + backupPath + " is "
                    + lastBackedGenerationNumber);
        }
        return lastBackedGenerationNumber;
    }

    @NonNull
    private FuseDaemon getFuseDaemonForPath(@NonNull String path)
            throws FileNotFoundException {
        return MediaProvider.getFuseDaemonForFile(new File(path), mVolumeCache);
    }

    void updateNextRowIdAndSetDirty(@NonNull DatabaseHelper helper,
            @NonNull FileRow oldRow, @NonNull FileRow newRow) {
        updateNextRowIdXattr(helper, newRow.getId());
        markBackupAsDirty(helper, oldRow);
    }

    /**
     * Backs up DB data in external storage to recover in case of DB rollback.
     */
    void backupVolumeDbData(DatabaseHelper databaseHelper, FileRow insertedRow) {
        if (!isBackupUpdateAllowed(databaseHelper, insertedRow.getVolumeName())) {
            return;
        }

        mLevelDbUpdateLock.lock();
        try {
            FuseDaemon fuseDaemonExternalPrimary = getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH);
            final BackupIdRow value = createBackupIdRow(fuseDaemonExternalPrimary, insertedRow);
            if (isInternalOrExternalPrimary(insertedRow.getVolumeName())) {
                fuseDaemonExternalPrimary.backupVolumeDbData(insertedRow.getVolumeName(),
                        insertedRow.getPath(), BackupIdRow.serialize(value));
            } else {
                // public volume
                final FuseDaemon fuseDaemonPublicVolume = getFuseDaemonForPath(
                        getFuseFilePathFromVolumeName(insertedRow.getVolumeName()));
                fuseDaemonPublicVolume.backupVolumeDbData(insertedRow.getVolumeName(),
                        insertedRow.getPath(), BackupIdRow.serialize(value));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failure in backing up data to external storage", e);
        } finally {
            mLevelDbUpdateLock.unlock();
        }
    }

    private BackupIdRow createBackupIdRow(FuseDaemon fuseDaemon, FileRow insertedRow)
            throws IOException {
        return createBackupIdRow(fuseDaemon, insertedRow.getId(), insertedRow.getMediaType(),
                insertedRow.isFavorite(), insertedRow.isPending(), insertedRow.isTrashed(),
                insertedRow.getUserId(), insertedRow.getDateExpires(),
                insertedRow.getOwnerPackageName());
    }

    private BackupIdRow createBackupIdRow(FuseDaemon fuseDaemon, long id, int mediaType,
            boolean isFavorite,
            boolean isPending, boolean isTrashed, int userId, String dateExpires,
            String ownerPackageName) throws IOException {
        BackupIdRow.Builder builder = BackupIdRow.newBuilder(id);
        builder.setMediaType(mediaType);
        builder.setIsFavorite(isFavorite ? 1 : 0);
        builder.setIsPending(isPending ? 1 : 0);
        builder.setIsTrashed(isTrashed ? 1 : 0);
        builder.setUserId(userId);
        builder.setDateExpires(dateExpires);
        // We set owner package id instead of owner package name in the backup. When an
        // application is uninstalled, all media rows corresponding to it will be orphaned and
        // would have owner package name as null. This should not change if application is
        // installed again. Therefore, we are storing owner id instead of owner package name. On
        // package uninstallation, we delete the owner id relation from the backup. All rows
        // recovered for orphaned owner ids will have package name as null. Since we also need to
        // support cloned apps, we are storing a combination of owner package name and user id to
        // uniquely identify a package.
        builder.setOwnerPackagedId(getOwnerPackageId(fuseDaemon, ownerPackageName, userId));
        return builder.setIsDirty(false).build();
    }


    private synchronized int getOwnerPackageId(FuseDaemon fuseDaemon,
            String ownerPackageName, int userId) throws IOException {
        // In synchronized block to avoid use of same owner id for multiple owner package relations
        if (Strings.isNullOrEmpty(ownerPackageName) || ownerPackageName.equalsIgnoreCase("null")) {
            // We store -1 in the backup if owner package name is null.
            return -1;
        }

        // Create identifier of format "owner_pkg_name::user_id". Tightly coupling owner package
        // name and user id helps in handling app cloning scenarios.
        String ownerPackageIdentifier = createOwnerPackageIdentifier(ownerPackageName, userId);
        // Read any existing entry for given owner package name and user id
        String ownerId = fuseDaemon.readFromOwnershipBackup(ownerPackageIdentifier);
        if (!ownerId.trim().isEmpty()) {
            // Use existing owner id if found and is positive
            int val = Integer.parseInt(ownerId);
            if (val >= 0) {
                return val;
            }
        }

        int nextOwnerId = getAndIncrementNextOwnerId();
        fuseDaemon.createOwnerIdRelation(String.valueOf(nextOwnerId), ownerPackageIdentifier);
        Log.v(TAG, "Created relation b/w " + nextOwnerId + " and " + ownerPackageIdentifier);
        return nextOwnerId;
    }

    private String createOwnerPackageIdentifier(String ownerPackageName, int userId) {
        return ownerPackageName.trim().concat("::").concat(String.valueOf(userId));
    }

    private Pair<String, Integer> getPackageNameAndUserId(String ownerPackageIdentifier) {
        if (ownerPackageIdentifier.trim().isEmpty()) {
            return Pair.create(null, null);
        }

        String[] arr = ownerPackageIdentifier.trim().split("::");
        return Pair.create(arr[0], Integer.valueOf(arr[1]));
    }

    private int getAndIncrementNextOwnerId() {
        if (mNextOwnerId == null) {
            Optional<Integer> nextOwnerIdOptional = getXattrOfIntegerValue(
                    OWNER_RELATION_LOWER_FS_BACKUP_PATH,
                    NEXT_OWNER_ID_XATTR_KEY);
            mNextOwnerId = nextOwnerIdOptional.map(AtomicInteger::new).orElseGet(
                    () -> new AtomicInteger(NEXT_OWNER_ID_DEFAULT_VALUE));
            mNextOwnerIdBackup = new AtomicInteger(mNextOwnerId.get());
        }
        if (mNextOwnerId.get() >= mNextOwnerIdBackup.get()) {
            int nextBackup = mNextOwnerId.get() + NEXT_OWNER_ID_BACKUP_FREQUENCY;
            updateNextOwnerId(nextBackup);
            mNextOwnerIdBackup = new AtomicInteger(nextBackup);
        }
        int returnValue = mNextOwnerId.get();
        mNextOwnerId.set(returnValue + 1);
        return returnValue;
    }

    private void updateNextOwnerId(int val) {
        setXattr(OWNER_RELATION_LOWER_FS_BACKUP_PATH, NEXT_OWNER_ID_XATTR_KEY, String.valueOf(val));
        Log.d(TAG, "Updated next owner id to: " + val);
    }

    void removeOwnerIdToPackageRelation(String packageName, int userId) {
        if (Strings.isNullOrEmpty(packageName) || packageName.equalsIgnoreCase("null")
                || !isStableUrisEnabled(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                || !new File(OWNER_RELATION_LOWER_FS_BACKUP_PATH).exists()
                || !mSetupCompleteVolumes.contains(MediaStore.VOLUME_EXTERNAL_PRIMARY)) {
            return;
        }

        try {
            FuseDaemon fuseDaemon = getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH);
            String ownerPackageIdentifier = createOwnerPackageIdentifier(packageName, userId);
            String ownerId = fuseDaemon.readFromOwnershipBackup(ownerPackageIdentifier);

            fuseDaemon.removeOwnerIdRelation(ownerId, ownerPackageIdentifier);
        } catch (Exception e) {
            Log.e(TAG, "Failure in removing owner id to package relation", e);
        }
    }

    /**
     * Deletes backed up data(needed for recovery) from external storage.
     */
    void deleteFromDbBackup(DatabaseHelper databaseHelper, FileRow deletedRow) {
        if (!isBackupUpdateAllowed(databaseHelper, deletedRow.getVolumeName())) {
            return;
        }

        String deletedFilePath = deletedRow.getPath();
        if (deletedFilePath == null) {
            return;
        }

        mLevelDbUpdateLock.lock();
        try {
            getFuseDaemonForPath(getFuseFilePathFromVolumeName(deletedRow.getVolumeName()))
                    .deleteDbBackup(deletedFilePath);
        } catch (IOException e) {
            Log.w(TAG, "Failure in deleting backup data for key: " + deletedFilePath, e);
        } finally {
            mLevelDbUpdateLock.unlock();
        }
    }

    private boolean isBackupUpdateAllowed(DatabaseHelper databaseHelper, String volumeName) {
        // Backup only if stable uris is enabled, db is not recovering and backup setup is complete.
        return isStableUrisEnabled(volumeName) && !databaseHelper.isDatabaseRecovering()
                && mSetupCompleteVolumes.contains(volumeName);
    }

    private boolean isInternalOrExternalPrimary(String volumeName) {
        if (Strings.isNullOrEmpty(volumeName)) {
            // This should never happen
            Log.e(TAG, "Volume name is " + volumeName + ", treating it as non public volume");
            return true;
        }
        return MediaStore.VOLUME_INTERNAL.equalsIgnoreCase(volumeName)
                || MediaStore.VOLUME_EXTERNAL_PRIMARY.equalsIgnoreCase(volumeName);
    }

    private void updateNextRowIdForInternal(DatabaseHelper helper, long id) {
        if (!isStableUrisEnabled(MediaStore.VOLUME_INTERNAL)) {
            return;
        }

        Optional<Long> nextRowIdBackupOptional = helper.getNextRowId();

        if (!nextRowIdBackupOptional.isPresent()) {
            return;
        }

        if (id >= nextRowIdBackupOptional.get()) {
            helper.backupNextRowId(id);
        }
    }

    private void markBackupAsDirty(DatabaseHelper databaseHelper, FileRow updatedRow) {
        if (!isBackupUpdateAllowed(databaseHelper, updatedRow.getVolumeName())) {
            return;
        }

        final String updatedFilePath = updatedRow.getPath();
        try {
            getFuseDaemonForPath(getFuseFilePathFromVolumeName(updatedRow.getVolumeName()))
                    .backupVolumeDbData(
                            updatedRow.getVolumeName(),
                            updatedFilePath,
                            BackupIdRow.serialize(BackupIdRow.newBuilder(updatedRow.getId())
                                    .setIsDirty(true).build()));
        } catch (IOException e) {
            Log.e(TAG, "Failure in marking data as dirty to external storage for path:"
                    + updatedFilePath, e);
        }
    }

    /**
     * Reads value corresponding to given key from xattr on given path.
     */
    static Optional<String> getXattr(String path, String key) {
        try {
            return Optional.of(Arrays.toString(Os.getxattr(path, key)));
        } catch (Exception e) {
            Log.w(TAG, String.format(Locale.ROOT,
                    "Exception encountered while reading xattr:%s from path:%s.", key, path));
            return Optional.empty();
        }
    }

    /**
     * Reads long value corresponding to given key from xattr on given path.
     */
    private static Optional<Long> getXattrOfLongValue(String path, String key) {
        try {
            return Optional.of(Long.parseLong(new String(Os.getxattr(path, key))));
        } catch (Exception e) {
            Log.w(TAG, String.format(Locale.ROOT,
                    "Exception encountered while reading xattr:%s from path:%s.", key, path));
            return Optional.empty();
        }
    }

    /**
     * Reads integer value corresponding to given key from xattr on given path.
     */
    static Optional<Integer> getXattrOfIntegerValue(String path, String key) {
        try {
            return Optional.of(Integer.parseInt(new String(Os.getxattr(path, key))));
        } catch (Exception e) {
            Log.w(TAG, String.format(Locale.ROOT,
                    "Exception encountered while reading xattr:%s from path:%s.", key, path));
            return Optional.empty();
        }
    }

    /**
     * Sets key and value as xattr on given path.
     */
    static boolean setXattr(String path, String key, String value) {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(new File(path),
                ParcelFileDescriptor.MODE_READ_ONLY)) {
            // Map id value to xattr key
            Os.setxattr(path, key, value.getBytes(), 0);
            Os.fsync(pfd.getFileDescriptor());
            Log.d(TAG, String.format("xattr set to %s for key:%s on path: %s.", value, key, path));
            return true;
        } catch (Exception e) {
            Log.e(TAG, String.format(Locale.ROOT, "Failed to set xattr:%s to %s for path: %s.", key,
                    value, path), e);
            return false;
        }
    }

    /**
     * Deletes xattr with given key on given path. Becomes a no-op when xattr is not present.
     */
    static boolean removeXattr(String path, String key) {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(new File(path),
                ParcelFileDescriptor.MODE_READ_ONLY)) {
            Os.removexattr(path, key);
            Os.fsync(pfd.getFileDescriptor());
            Log.d(TAG, String.format("xattr key:%s removed on path: %s.", key, path));
            return true;
        } catch (Exception e) {
            if (e instanceof ErrnoException) {
                ErrnoException exception = (ErrnoException) e;
                if (exception.errno == OsConstants.ENODATA) {
                    Log.w(TAG, String.format(Locale.ROOT,
                            "xattr:%s is not removed as it is not found on path: %s.", key, path));
                    return true;
                }
            }

            Log.e(TAG, String.format(Locale.ROOT, "Failed to remove xattr:%s for path: %s.", key,
                    path), e);
            return false;
        }
    }

    /**
     * Lists xattrs of given path.
     */
    static List<String> listXattr(String path) {
        try {
            return Arrays.asList(Os.listxattr(path));
        } catch (Exception e) {
            Log.e(TAG, "Exception in reading xattrs on path: " + path, e);
            return new ArrayList<>();
        }
    }

    private boolean insertDataInDatabase(SQLiteDatabase db, BackupIdRow row, String filePath,
            String volumeName) {
        final ContentValues values = createValuesFromFileRow(row, filePath, volumeName);
        return db.insertWithOnConflict("files", null, values,
                SQLiteDatabase.CONFLICT_REPLACE) != -1;
    }

    private ContentValues createValuesFromFileRow(BackupIdRow row, String filePath,
            String volumeName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Files.FileColumns._ID, row.getId());
        values.put(MediaStore.Files.FileColumns.IS_FAVORITE, row.getIsFavorite());
        values.put(MediaStore.Files.FileColumns.IS_PENDING, row.getIsPending());
        values.put(MediaStore.Files.FileColumns.IS_TRASHED, row.getIsTrashed());
        values.put(MediaStore.Files.FileColumns.DATA, filePath);
        values.put(MediaStore.Files.FileColumns.VOLUME_NAME, volumeName);
        values.put(MediaStore.Files.FileColumns._USER_ID, row.getUserId());
        values.put(MediaStore.Files.FileColumns.MEDIA_TYPE, row.getMediaType());
        if (!StringUtils.isNullOrEmpty(row.getDateExpires())) {
            values.put(MediaStore.Files.FileColumns.DATE_EXPIRES,
                    Long.valueOf(row.getDateExpires()));
        }
        if (row.getOwnerPackageId() >= 0) {
            Pair<String, Integer> ownerPackageNameAndUidPair = getOwnerPackageNameAndUidPair(
                    row.getOwnerPackageId());
            if (ownerPackageNameAndUidPair.first != null) {
                values.put(MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME,
                        ownerPackageNameAndUidPair.first);
            }
            if (ownerPackageNameAndUidPair.second != null) {
                values.put(MediaStore.Files.FileColumns._USER_ID,
                        ownerPackageNameAndUidPair.second);
            }
        }

        return values;
    }

    private Pair<String, Integer> getOwnerPackageNameAndUidPair(int ownerPackageId) {
        if (sOwnerIdRelationMap == null) {
            try {
                sOwnerIdRelationMap = readOwnerIdRelationsFromLevelDb();
                Log.v(TAG, "Cached owner id map");
            } catch (IOException e) {
                Log.e(TAG, "Failure in reading owner details for owner id:" + ownerPackageId, e);
                return Pair.create(null, null);
            }
        }

        if (sOwnerIdRelationMap.containsKey(String.valueOf(ownerPackageId))) {
            return getPackageNameAndUserId(sOwnerIdRelationMap.get(String.valueOf(ownerPackageId)));
        }

        return Pair.create(null, null);
    }

    private Map<String, String> readOwnerIdRelationsFromLevelDb() throws IOException {
        return getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH).readOwnerIdRelations();
    }

    String readOwnerPackageName(String ownerId) throws IOException {
        Map<String, String> ownerIdRelationMap = readOwnerIdRelationsFromLevelDb();
        if (ownerIdRelationMap.containsKey(String.valueOf(ownerId))) {
            return getPackageNameAndUserId(ownerIdRelationMap.get(ownerId)).first;
        }

        return null;
    }

    void markPublicVolumesRecovery() {
        try {
            File recoveryDir = new File(LOWER_FS_RECOVERY_DIRECTORY_PATH);
            for (File levelDbFile : recoveryDir.listFiles()) {
                if (!(LEVEL_DB_PREFIX + MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        .equalsIgnoreCase(levelDbFile.getName())
                        && !(LEVEL_DB_PREFIX + MediaStore.VOLUME_INTERNAL)
                        .equalsIgnoreCase(levelDbFile.getName())
                        && !(OWNERSHIP_TABLE_NAME
                        .equalsIgnoreCase(levelDbFile.getName()))) {
                    setXattr(levelDbFile.getAbsolutePath(), PUBLIC_VOLUME_RECOVERY_FLAG_XATTR_KEY,
                            String.valueOf(true));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception while marking public volumes for recovery", e);
        }
    }

    boolean isPublicVolumeMarkedForRecovery(String volumeName) {
        String filePath = LOWER_FS_RECOVERY_DIRECTORY_PATH + "/" + LEVEL_DB_PREFIX
                + volumeName.toLowerCase(Locale.ROOT);
        Optional<String> flag = getXattr(filePath, PUBLIC_VOLUME_RECOVERY_FLAG_XATTR_KEY);
        Log.d(TAG, "Public volume is " + (flag.isPresent() ? "" : "not ")
                + "marked for recovery, volume: " + volumeName);
        return flag.isPresent();
    }

    void removePublicVolumeRecoveryFlag(String volumeName) {
        String filePath = LOWER_FS_RECOVERY_DIRECTORY_PATH + "/" + LEVEL_DB_PREFIX
                + volumeName.toLowerCase(Locale.ROOT);
        removeXattr(filePath, PUBLIC_VOLUME_RECOVERY_FLAG_XATTR_KEY);
    }

    void recoverData(SQLiteDatabase db, String volumeName) throws Exception {
        long rowsRecovered = 0, dirtyRowsCount = 0, insertionFailuresCount = 0,
                totalLevelDbRows = 0;
        final long startTime = SystemClock.elapsedRealtime();
        try {
            final String fuseFilePath = getFuseFilePathFromVolumeName(volumeName);
            // Wait for external primary to be attached as we use same thread for internal volume.
            // Maximum wait for 20s
            getFuseDaemonForFileWithWait(new File(fuseFilePath));
            if (!isBackupPresent(volumeName)) {
                throw new FileNotFoundException("Backup file not found for " + volumeName);
            }

            Log.d(TAG, "Backup is present for " + volumeName);
            try {
                waitForVolumeToBeAttached(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Volume not attached in given time. Cannot recover data.", e);
            }

            String[] backedUpFilePaths;
            String lastReadValue = "";
            while (true) {
                backedUpFilePaths = readBackedUpFilePaths(volumeName, lastReadValue,
                        LEVEL_DB_READ_LIMIT);
                if (backedUpFilePaths == null || backedUpFilePaths.length == 0) {
                    break;
                }
                totalLevelDbRows += backedUpFilePaths.length;

                // Reset cached owner id relation map
                sOwnerIdRelationMap = null;
                for (String filePath : backedUpFilePaths) {
                    Optional<BackupIdRow> fileRow = readDataFromBackup(volumeName, filePath);
                    if (fileRow.isPresent()) {
                        if (fileRow.get().getIsDirty()) {
                            dirtyRowsCount++;
                            continue;
                        }

                        if (insertDataInDatabase(db, fileRow.get(), filePath, volumeName)) {
                            rowsRecovered++;
                        } else {
                            insertionFailuresCount++;
                        }
                    }
                }

                // Read less rows than expected
                if (backedUpFilePaths.length < LEVEL_DB_READ_LIMIT) {
                    break;
                }
                lastReadValue = backedUpFilePaths[backedUpFilePaths.length - 1];
            }
            long recoveryTime = SystemClock.elapsedRealtime() - startTime;
            publishRecoveryMetric(volumeName, recoveryTime, rowsRecovered, dirtyRowsCount,
                    totalLevelDbRows, insertionFailuresCount,
                    MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__STATUS__SUCCESS);
            Log.i(TAG, String.format(Locale.ROOT, "%d rows recovered for volume: %s."
                            + " Total rows in levelDB: %d.", rowsRecovered, volumeName,
                    totalLevelDbRows));
            Log.i(TAG, String.format(Locale.ROOT, "Recovery time: %d ms", recoveryTime));
        } catch (TimeoutException e) {
            long recoveryTime = SystemClock.elapsedRealtime() - startTime;
            publishRecoveryMetric(volumeName, recoveryTime, rowsRecovered, dirtyRowsCount,
                    totalLevelDbRows, insertionFailuresCount,
                    MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__STATUS__FUSE_DAEMON_TIMEOUT);
            throw e;
        } catch (FileNotFoundException e) {
            long recoveryTime = SystemClock.elapsedRealtime() - startTime;
            publishRecoveryMetric(volumeName, recoveryTime, rowsRecovered, dirtyRowsCount,
                    totalLevelDbRows, insertionFailuresCount,
                    MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__STATUS__BACKUP_MISSING);
            throw e;
        } catch (IOException e) {
            long recoveryTime = SystemClock.elapsedRealtime() - startTime;
            publishRecoveryMetric(volumeName, recoveryTime, rowsRecovered, dirtyRowsCount,
                    totalLevelDbRows, insertionFailuresCount,
                    MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__STATUS__GET_BACKUP_DATA_FAILURE);
            throw e;
        } catch (IllegalStateException e) {
            long recoveryTime = SystemClock.elapsedRealtime() - startTime;
            publishRecoveryMetric(volumeName, recoveryTime, rowsRecovered, dirtyRowsCount,
                    totalLevelDbRows, insertionFailuresCount,
                    MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__STATUS__VOLUME_NOT_ATTACHED);
            throw e;
        } catch (Exception e) {
            long recoveryTime = SystemClock.elapsedRealtime() - startTime;
            publishRecoveryMetric(volumeName, recoveryTime, rowsRecovered, dirtyRowsCount,
                    totalLevelDbRows, insertionFailuresCount,
                    MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__STATUS__OTHER_ERROR);
            throw e;
        }
    }

    void resetLastBackedUpGenerationNumber(String volumeName) {
        // Resetting generation number
        setXattr(LOWER_FS_RECOVERY_DIRECTORY_PATH + "/" + LEVEL_DB_PREFIX + volumeName,
                LAST_BACKEDUP_GENERATION_XATTR_KEY, String.valueOf(0));
        Log.v(TAG, "Leveldb Last backed generation number reset done to 0 for " + volumeName);
    }

    boolean isBackupPresent(String volumeName) {
        if (MediaStore.VOLUME_INTERNAL.equalsIgnoreCase(volumeName)) {
            return new File(INTERNAL_VOLUME_LOWER_FS_BACKUP_PATH).exists();
        } else if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equalsIgnoreCase(volumeName)) {
            return new File(EXTERNAL_PRIMARY_VOLUME_LOWER_FS_BACKUP_PATH).exists();
        } else if (!Strings.isNullOrEmpty(volumeName)) {
            return new File(LOWER_FS_RECOVERY_DIRECTORY_PATH + "/leveldb-" + volumeName)
                    .exists();
        }

        return false;
    }

    void waitForVolumeToBeAttached(String volumeName) throws TimeoutException {
        long time = 0;
        // Wait of 10 seconds
        long waitTimeInMilliseconds = 10000;
        // Poll every 100 milliseconds
        long pollTime = 100;
        while (time <= waitTimeInMilliseconds) {
            if (mSetupCompleteVolumes.contains(volumeName)) {
                Log.i(TAG, "Found " + volumeName + " volume attached.");
                return;
            }

            SystemClock.sleep(pollTime);
            time += pollTime;
        }
        throw new TimeoutException("Timed out waiting for " + volumeName + " setup");
    }

    FuseDaemon getFuseDaemonForFileWithWait(File fuseFilePath)
            throws FileNotFoundException, TimeoutException {
        pollForExternalStorageMountedState();
        return MediaProvider.getFuseDaemonForFileWithWait(fuseFilePath, mVolumeCache,
                WAIT_TIME_20_SECONDS_IN_MILLIS);
    }

    void setStableUrisGlobalFlag(String volumeName, boolean isEnabled) {
        if (MediaStore.VOLUME_INTERNAL.equalsIgnoreCase(volumeName)) {
            mIsStableUriEnabledForInternal = isEnabled;
        } else if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equalsIgnoreCase(volumeName)) {
            mIsStableUriEnabledForExternal = isEnabled;
        } else {
            mIsStableUrisEnabledForPublic = isEnabled;
        }
    }

    private int getVolumeNameForStatsLog(String volumeName) {
        if (volumeName.equalsIgnoreCase(MediaStore.VOLUME_INTERNAL)) {
            return MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__INTERNAL;
        } else if (volumeName.equalsIgnoreCase(MediaStore.VOLUME_EXTERNAL_PRIMARY)) {
            return MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__EXTERNAL_PRIMARY;
        }

        return MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__PUBLIC;
    }

    private static String getFuseFilePathFromVolumeName(String volumeName) {
        if (Strings.isNullOrEmpty(volumeName)) {
            // Returning EXTERNAL_PRIMARY_ROOT_PATH to avoid any regressions
            Log.e(TAG, "Trying to get a Fuse Daemon for volume name = " + volumeName);
            return EXTERNAL_PRIMARY_ROOT_PATH;
        }
        switch (volumeName) {
            case MediaStore.VOLUME_INTERNAL:
            case MediaStore.VOLUME_EXTERNAL_PRIMARY:
                return EXTERNAL_PRIMARY_ROOT_PATH;
            default:
                return "/storage/" + volumeName.toUpperCase(Locale.ROOT);
        }
    }

    /**
     * Returns list of backed up files from external storage.
     */
    List<File> getBackupFiles() {
        return Arrays.asList(new File(LOWER_FS_RECOVERY_DIRECTORY_PATH).listFiles());
    }

    /**
     * Updates backup in external storage to the latest values. Deletes backup of old file path if
     * file path has changed.
     */
    void updateBackup(DatabaseHelper helper, FileRow oldRow, FileRow newRow) {
        if (!isBackupUpdateAllowed(helper, newRow.getVolumeName())) {
            return;
        }

        final FuseDaemon fuseDaemonExternalPrimary;
        try {
            fuseDaemonExternalPrimary = getFuseDaemonForFileWithWait(
                    new File(EXTERNAL_PRIMARY_ROOT_PATH));
        } catch (Exception e) {
            Log.e(TAG,
                    "Fuse Daemon not found for primary external storage, skipping update of "
                            + newRow.getPath(), e);
            return;
        }

        final FuseDaemon fuseDaemonPublicVolume;
        if (!isInternalOrExternalPrimary(newRow.getVolumeName())) {
            try {
                fuseDaemonPublicVolume = getFuseDaemonForFileWithWait(new File(
                        getFuseFilePathFromVolumeName(newRow.getVolumeName())));
            } catch (Exception e) {
                Log.e(TAG,
                        "Error occurred while retrieving the Fuse Daemon for "
                                + getFuseFilePathFromVolumeName(newRow.getVolumeName())
                                + ", skipping update of " + newRow.getPath(),
                        e);
                return;
            }
        } else {
            fuseDaemonPublicVolume = null;
        }

        mLevelDbUpdateLock.lock();
        try {
            helper.runWithTransaction((db) -> {
                try (Cursor c = db.query(true, "files", QUERY_COLUMNS, "_id=?",
                        new String[]{String.valueOf(newRow.getId())}, null, null, null,
                        null, null)) {
                    if (c.moveToFirst()) {
                        backupDataValues(fuseDaemonExternalPrimary, fuseDaemonPublicVolume, c);
                        String newPath = c.getString(1);
                        if (oldRow.getPath() != null && !oldRow.getPath().equalsIgnoreCase(
                                newPath)) {
                            // If file path has changed, update leveldb backup to delete old path.
                            deleteFromDbBackup(helper, oldRow);
                            Log.v(TAG, "Deleted backup of old file path: "
                                    + oldRow.getPath());
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failure in updating row in external storage backup.", e);
                }
                return null;
            });
        } catch (Exception e) {
            Log.e(TAG, "Failure in updating row in external storage backup.", e);
        } finally {
            mLevelDbUpdateLock.unlock();
        }
    }

    /**
     * Removes database recovery data for given user id. This is done when a user is removed.
     */
    void removeRecoveryDataForUserId(int removedUserId) {
        String removeduserIdString = String.valueOf(removedUserId);
        removeXattr(DATA_MEDIA_XATTR_DIRECTORY_PATH,
                INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX.concat(
                        removeduserIdString));
        removeXattr(DATA_MEDIA_XATTR_DIRECTORY_PATH,
                EXTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX.concat(
                        removeduserIdString));
        removeXattr(DATA_MEDIA_XATTR_DIRECTORY_PATH,
                INTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX.concat(removeduserIdString));
        removeXattr(DATA_MEDIA_XATTR_DIRECTORY_PATH,
                EXTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX.concat(removeduserIdString));
        Log.v(TAG, "Removed recovery data for user id: " + removedUserId);
    }

    /**
     * Removes database recovery data for obsolete user id. It accepts list of valid/active users
     * and removes the recovery data for ones not present in this list.
     * This is done during an idle maintenance.
     */
    void removeRecoveryDataExceptValidUsers(List<String> validUsers) {
        List<String> xattrList = listXattr(DATA_MEDIA_XATTR_DIRECTORY_PATH);
        Log.i(TAG, "Xattr list is " + xattrList);
        if (xattrList.isEmpty()) {
            return;
        }

        Log.i(TAG, "Valid users list is " + validUsers);
        List<String> invalidUsers = getInvalidUsersList(xattrList, validUsers);
        Log.i(TAG, "Invalid users list is " + invalidUsers);
        for (String userIdToBeRemoved : invalidUsers) {
            if (userIdToBeRemoved != null && !userIdToBeRemoved.trim().isEmpty()) {
                removeRecoveryDataForUserId(Integer.parseInt(userIdToBeRemoved));
            }
        }
    }

    private void publishRecoveryMetric(String volumeName, long recoveryTime, long rowsRecovered,
            long dirtyRowsCount, long totalLevelDbRows, long insertionFailureCount, int status) {
        MediaProviderStatsLog.write(
                MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED,
                getVolumeNameForStatsLog(volumeName), recoveryTime, rowsRecovered,
                dirtyRowsCount, totalLevelDbRows, insertionFailureCount, status);
    }

    static List<String> getInvalidUsersList(List<String> recoveryData,
            List<String> validUsers) {
        Set<String> presentUserIdsAsXattr = new HashSet<>();
        for (String xattr : recoveryData) {
            if (xattr.startsWith(INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX)) {
                presentUserIdsAsXattr.add(
                        xattr.substring(INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX.length()));
            } else if (xattr.startsWith(EXTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX)) {
                presentUserIdsAsXattr.add(
                        xattr.substring(EXTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX.length()));
            } else if (xattr.startsWith(INTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX)) {
                presentUserIdsAsXattr.add(
                        xattr.substring(INTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX.length()));
            } else if (xattr.startsWith(EXTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX)) {
                presentUserIdsAsXattr.add(
                        xattr.substring(EXTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX.length()));
            }
        }
        // Remove valid users
        validUsers.forEach(presentUserIdsAsXattr::remove);
        return presentUserIdsAsXattr.stream().collect(Collectors.toList());
    }

    private static void pollForExternalStorageMountedState() throws TimeoutException {
        final File target = Environment.getExternalStorageDirectory();
        for (int i = 0; i < WAIT_TIME_20_SECONDS_IN_MILLIS / 100; i++) {
            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(target))) {
                return;
            }
            Log.v(TAG, "Waiting for external storage...");
            SystemClock.sleep(100);
        }
        throw new TimeoutException("Timed out while waiting for ExternalStorageState "
                + "to be MEDIA_MOUNTED");
    }

    /**
     * Performs actions to be taken on volume unmount.
     * @param volumeName name of volume which is detached
     */
    public void onDetachVolume(String volumeName) {
        if (mSetupCompleteVolumes.contains(volumeName)) {
            mSetupCompleteVolumes.remove(volumeName);
            Log.v(TAG,
                    "Removed leveldb connections from in memory setup cache for volume:"
                            + volumeName);
        }
    }
}
