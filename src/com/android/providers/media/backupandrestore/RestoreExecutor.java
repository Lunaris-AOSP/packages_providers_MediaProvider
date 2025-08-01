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

package com.android.providers.media.backupandrestore;

import static android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY;

import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.FIELD_SEPARATOR;
import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.KEY_VALUE_SEPARATOR;
import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.RESTORE_DIRECTORY_NAME;
import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.isBackupAndRestoreSupported;
import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.isRestoringFromRecentBackup;

import android.content.ContentValues;
import android.content.Context;

import com.android.providers.media.leveldb.LevelDBInstance;
import com.android.providers.media.leveldb.LevelDBManager;
import com.android.providers.media.leveldb.LevelDBResult;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Class containing implementation details for restoring files table data from restored leveldb
 * file.
 */
public final class RestoreExecutor {

    private final LevelDBInstance mLevelDBInstance;

    private RestoreExecutor(LevelDBInstance levelDBInstance) {
        mLevelDBInstance = levelDBInstance;
    }

    /**
     * Retrieves metadata for a file from leveldb if it is backed up.
     *
     * @param filePath The path of the file for which metadata is requested.
     * @param context The context to check if backup and restore functionality is supported.
     * @return An {@link Optional} containing the metadata as {@link ContentValues} if the file is
     * backed up or {@link Optional#empty()} if backup is not supported or the file is not backed up
     */
    public Optional<ContentValues> getMetadataForFileIfBackedUp(String filePath, Context context) {
        if (!isBackupAndRestoreSupported(context)) {
            return Optional.empty();
        }

        if (mLevelDBInstance == null) {
            return Optional.empty();
        }

        LevelDBResult levelDBResult = mLevelDBInstance.query(filePath);
        if (!levelDBResult.isSuccess()) {
            return Optional.empty();
        }

        String value = levelDBResult.getValue();
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }

        Map<String, String> keyValueMap = deSerialiseValueString(value);
        ContentValues contentValues = new ContentValues();
        for (String key : keyValueMap.keySet()) {
            contentValues.put(key, keyValueMap.get(key));
        }
        return Optional.of(contentValues);
    }

    private Map<String, String> deSerialiseValueString(String valueString) {
        String[] values = valueString.split(FIELD_SEPARATOR);
        Map<String, String> map = new HashMap<>();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }

            String[] keyValue = value.split(KEY_VALUE_SEPARATOR, 2);
            map.put(BackupAndRestoreUtils.sIdToColumnBiMap.get(keyValue[0]), keyValue[1]);
        }

        return map;
    }

    public static Optional<RestoreExecutor> getRestoreExecutor(Context context) {
        if (!isBackupAndRestoreSupported(context)) {
            return Optional.empty();
        }

        if (!isRestoringFromRecentBackup(context)) {
            return Optional.empty();
        }

        File restoredFilePath = new File(getRestoredFilePath(context));
        if (!restoredFilePath.exists()) {
            return Optional.empty();
        }

        LevelDBInstance levelDBInstance = LevelDBManager.getInstance(getRestoredFilePath(context));
        if (levelDBInstance == null) {
            return Optional.empty();
        }

        return Optional.of(new RestoreExecutor(levelDBInstance));
    }

    private static String getRestoredFilePath(Context context) {
        return context.getFilesDir().getAbsolutePath() + "/" + RESTORE_DIRECTORY_NAME + "/"
                + VOLUME_EXTERNAL_PRIMARY + "/";
    }
}
