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

package com.android.providers.media.photopicker.v2.sqlite;

import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Utility class to query media items from media_in_media_sets_table table and media table in
 * Picker DB.
 */
public class MediaInMediaSetsQuery {

    @Nullable
    private final String mIntentAction;
    @NonNull
    private final List<String> mProviders;
    protected final int mPageSize;
    @NonNull
    final MediaInMediaSetsLocalSubQuery mLocalMediaSubQuery;
    @NonNull
    final MediaInMediaSetsCloudSubQuery mCloudMediaSubquery;


    public MediaInMediaSetsQuery(Bundle queryArgs, @NonNull Long mediaPickerSetId) {
        Objects.requireNonNull(mediaPickerSetId);
        mIntentAction = queryArgs.getString("intent_action");
        mProviders = new ArrayList<>(
                Objects.requireNonNull(queryArgs.getStringArrayList("providers")));
        mPageSize = queryArgs.getInt("page_size", Integer.MAX_VALUE);

        mLocalMediaSubQuery = new MediaInMediaSetsLocalSubQuery(queryArgs, mediaPickerSetId);
        mCloudMediaSubquery = new MediaInMediaSetsCloudSubQuery(queryArgs, mediaPickerSetId);
    }

    /**
     * @param database SQLiteDatabase wrapper for Picker DB
     * @param localAuthority authority of the local provider if it should be queried,
     *                       otherwise null.
     * @param cloudAuthority authority of the cloud provider if it should be queried,
     *                       otherwise null.
     * @param reverseOrder true when the sort order needs to be reversed.
     * @return A string that contains the table clause of the sql query after joining the
     * media table and media_in_media_sets table.
     */
    public String getTableWithRequiredJoins(@NonNull SQLiteDatabase database,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority,
            boolean reverseOrder) {

        final MediaProjection mediaProjection = new MediaProjection(
                localAuthority,
                cloudAuthority,
                mIntentAction,
                PickerSQLConstants.Table.MEDIA
        );

        final String localMediaRawQuery = getSubQuery(
                database,
                mLocalMediaSubQuery,
                localAuthority,
                cloudAuthority,
                mediaProjection,
                reverseOrder
        );
        final String cloudMediaRawQuery = getSubQuery(
                database,
                mCloudMediaSubquery,
                localAuthority,
                cloudAuthority,
                mediaProjection,
                reverseOrder
        );
        return String.format(
                Locale.ROOT,
                "( %s UNION ALL %s )",
                localMediaRawQuery,
                cloudMediaRawQuery);
    }

    private String getSubQuery(
            @NonNull SQLiteDatabase database,
            @NonNull MediaInMediaSetsSubQuery mediaInMediaSetSubQuery,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority,
            @NonNull MediaProjection mediaProjection,
            boolean reverseOrder) {
        final SelectSQLiteQueryBuilder subQueryBuilder =
                new SelectSQLiteQueryBuilder(database);
        subQueryBuilder
                .setTables(mediaInMediaSetSubQuery.getTableWithRequiredJoins())
                .setProjection(mediaProjection.getAll());
        mediaInMediaSetSubQuery.addWhereClause(
                subQueryBuilder,
                PickerSQLConstants.Table.MEDIA,
                localAuthority,
                cloudAuthority,
                reverseOrder
        );
        return subQueryBuilder.buildQuery();
    }

    @Nullable
    public String getIntentAction() {
        return mIntentAction;
    }

    @NonNull
    public List<String> getProviders() {
        return mProviders;
    }

    public int getPageSize() {
        return mPageSize;
    }
}
