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

import static android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE;
import static android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE;

import static com.android.providers.media.photopicker.v2.sqlite.PickerMediaDatabaseUtil.addNextPageKey;
import static com.android.providers.media.photopicker.v2.sqlite.PickerMediaDatabaseUtil.addPrevPageKey;

import static java.util.Objects.requireNonNull;

import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.util.exceptions.RequestObsoleteException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Convenience class for running Picker Search Results related sql queries.
 */
public class SearchResultsDatabaseUtil {
    private static final String TAG = "SearchResultsDatabaseUtil";

    /**
     * Utility method that extracts ContentValues in a format that can be inserted in the
     * search_result_table.
     *
     * @param searchRequestId Identifier for a search request.
     * @param cursor Cursor received from a CloudMediaProvider with the projection
     *               {@link CloudMediaProviderContract.MediaColumns}
     * @param isLocal true if the received cursor came from the local provider, otherwise false.
     * @return a list of ContentValues that can be inserted in the search_result_media table.
     */
    @NonNull
    public static List<ContentValues> extractContentValuesList(
            int searchRequestId, @NonNull Cursor cursor, boolean isLocal
    ) {
        final List<ContentValues> contentValuesList = new ArrayList<>(cursor.getCount());
        if (cursor.moveToFirst()) {
            do {
                contentValuesList.add(extractContentValues(searchRequestId, cursor, isLocal));
            } while (cursor.moveToNext());
        }
        return contentValuesList;
    }

    @NonNull
    private static ContentValues extractContentValues(
            int searchRequestId,
            @NonNull Cursor cursor,
            boolean isLocal) {
        final ContentValues contentValues = new ContentValues();

        final String id = cursor.getString(cursor.getColumnIndexOrThrow(
                CloudMediaProviderContract.MediaColumns.ID));
        final String rawMediaStoreUri = cursor.getString(cursor.getColumnIndexOrThrow(
                CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI));
        final Uri mediaStoreUri = rawMediaStoreUri == null ? null : Uri.parse(rawMediaStoreUri);
        final String extractedLocalId = mediaStoreUri == null ? null
                : String.valueOf(ContentUris.parseId(mediaStoreUri));

        final String localId = isLocal ? id : extractedLocalId;
        final String cloudId = isLocal ? null : id;

        contentValues.put(
                PickerSQLConstants.SearchResultMediaTableColumns.SEARCH_REQUEST_ID.getColumnName(),
                searchRequestId
        );
        contentValues.put(
                PickerSQLConstants.SearchResultMediaTableColumns.LOCAL_ID.getColumnName(),
                localId
        );
        contentValues.put(
                PickerSQLConstants.SearchResultMediaTableColumns.CLOUD_ID.getColumnName(),
                cloudId
        );

        return contentValues;
    }

    /**
     * Saved the search results media items received from CMP in the database as a temporary cache.
     *
     * @param database SQLite database object that holds DB connection(s) and provides a wrapper
     *                 for executing DB queries.
     * @param authority Authority of the CMP that is the source of search results media.
     * @param contentValuesList List of ContentValues that contain the search results media.
     *                          Each ContentValue in the list represents a media item.
     * @return The number of items inserted in the DB.
     * @throws RuntimeException if no items could be inserted in the database due to an unexpected
     * exception.
     */
    public static int cacheSearchResults(
            @NonNull SQLiteDatabase database,
            @NonNull String authority,
            @Nullable List<ContentValues> contentValuesList,
            @Nullable CancellationSignal cancellationSignal) {
        requireNonNull(database);
        requireNonNull(authority);

        if (contentValuesList == null || contentValuesList.isEmpty()) {
            Log.e(TAG, "Cursor is either null or empty. Nothing to do.");
            return 0;
        }

        final boolean isLocal = PickerSyncController.getInstanceOrThrow()
                .getLocalProvider()
                .equals(authority);

        try {
            // Start a transaction with EXCLUSIVE lock.
            database.beginTransaction();

            // Number of rows inserted or replaced
            int numberOfRowsInserted = 0;
            for (ContentValues contentValues : contentValuesList) {
                try {
                    // Prefer media received from local provider over cloud provider to avoid
                    // joining with media table on cloud_id when not required.
                    final int conflictResolutionStrategy = isLocal
                            ? CONFLICT_REPLACE
                            : CONFLICT_IGNORE;
                    final long rowID = database.insertWithOnConflict(
                            PickerSQLConstants.Table.SEARCH_RESULT_MEDIA.name(),
                            null,
                            contentValues,
                            conflictResolutionStrategy
                    );

                    if (rowID == -1) {
                        Log.d(TAG, "Did not insert row in the search results media table"
                                + " due to IGNORE conflict resolution strategy " + contentValues);
                    } else {
                        numberOfRowsInserted++;
                    }
                } catch (SQLException e) {
                    // Skip the row that could not be inserted.
                    Log.e(TAG, "Could not insert row in the search results media table "
                            + contentValues, e);
                }
            }

            if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                throw new RequestObsoleteException(
                        "cacheSearchResults operation has been cancelled.");
            }

            // Mark transaction as successful so that it gets committed after it ends.
            if (database.inTransaction()) {
                database.setTransactionSuccessful();
            }

            Log.d(TAG, "Number of search results cached: " + numberOfRowsInserted);
            return numberOfRowsInserted;
        } catch (RequestObsoleteException e) {
            // Do not mark transaction as successful so that it gets roll-backed. after it ends.
            throw new RuntimeException("Could not insert items in the DB because "
                    + "the operation has been cancelled.", e);
        } catch (RuntimeException e) {
            // Do not mark transaction as successful so that it gets roll-backed. after it ends.
            throw new RuntimeException("Could not insert items in the DB", e);
        } finally {
            // Mark transaction as ended. The inserted items will either be committed if the
            // transaction has been set as successful, or roll-backed otherwise.
            if (database.inTransaction()) {
                database.endTransaction();
            }
        }
    }

    /**
     * Query media from the database and prepare a cursor in response.
     *
     * To get search media, we'll fetch media IDs for a corresponding search request ID from the
     * search_result_media table and then enrich it with media metadata from the media table using
     * sql joins.
     *
     * We need to make multiple queries to prepare a response for the media query.
     * {@link android.database.sqlite.SQLiteQueryBuilder} currently does not support the creation of
     * a transaction in {@code DEFERRED} mode. This is why we'll perform the read queries in
     * {@code IMMEDIATE} mode instead.
     *
     * @param syncController Instance of the PickerSyncController singleton.
     * @param query The MediaQuery object instance that tells us about the media query args.
     * @param localAuthority The effective local authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would be null.
     * @param cloudAuthority The effective cloud authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would
     *                       be null.
     * @return The cursor with the album media query results.
     */
    @NonNull
    public static Cursor querySearchMedia(
            @NonNull PickerSyncController syncController,
            @NonNull SearchMediaQuery query,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority
    ) {
        final SQLiteDatabase database = syncController.getDbFacade().getDatabase();

        try {
            database.beginTransactionNonExclusive();
            Cursor pageData = database.rawQuery(
                    getSearchMediaPageQuery(
                            query,
                            database,
                            query.getTableWithRequiredJoins(
                                    database, localAuthority, cloudAuthority,
                                    /* reverseOrder */ false)
                    ),
                    /* selectionArgs */ null
            );

            Bundle extraArgs = new Bundle();
            Cursor nextPageKeyCursor = database.rawQuery(
                    getSearchMediaNextPageKeyQuery(
                            query,
                            database,
                            query.getTableWithRequiredJoins(
                                    database, localAuthority, cloudAuthority,
                                    /* reverseOrder */ false)
                    ),
                    /* selectionArgs */ null
            );
            addNextPageKey(extraArgs, nextPageKeyCursor);

            Cursor prevPageKeyCursor = database.rawQuery(
                    getSearchMediaPreviousPageQuery(
                            query,
                            database,
                            query.getTableWithRequiredJoins(
                                    database, localAuthority, cloudAuthority,
                                    /* reverseOrder */ true)
                    ),
                    /* selectionArgs */ null
            );
            addPrevPageKey(extraArgs, prevPageKeyCursor);

            if (database.inTransaction()) {
                database.setTransactionSuccessful();
            }

            pageData.setExtras(extraArgs);
            Log.i(TAG, "Returning " + pageData.getCount() + " media metadata");
            return pageData;
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch media", e);
        } finally {
            if (database.inTransaction()) {
                database.endTransaction();
            }
        }
    }

    /**
     * Builds and returns the SQL query to get the page contents for the search results from
     * Picker DB. To get the media items, we need to query the search_result_media table
     * and join with media table.
     */
    public static String getSearchMediaPageQuery(
            @NonNull SearchMediaQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull String table) {
        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(table)
                .setProjection(List.of(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName(),
                        PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName(),
                        PickerSQLConstants.MediaResponse
                                .AUTHORITY.getProjectedName(),
                        PickerSQLConstants.MediaResponse.MEDIA_SOURCE.getProjectedName(),
                        PickerSQLConstants.MediaResponse.WRAPPED_URI.getProjectedName(),
                        PickerSQLConstants.MediaResponse
                                .UNWRAPPED_URI.getProjectedName(),
                        PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName(),
                        PickerSQLConstants.MediaResponse.SIZE_IN_BYTES.getProjectedName(),
                        PickerSQLConstants.MediaResponse.MIME_TYPE.getProjectedName(),
                        PickerSQLConstants.MediaResponse.STANDARD_MIME_TYPE.getProjectedName(),
                        PickerSQLConstants.MediaResponse.DURATION_MS.getProjectedName(),
                        PickerSQLConstants.MediaResponse.IS_PRE_GRANTED.getProjectedName()
                ))
                .setSortOrder(
                        String.format(
                                Locale.ROOT,
                                "%s DESC, %s DESC",
                                PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName(),
                                PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName()
                        )
                )
                .setLimit(query.getPageSize());

        return queryBuilder.buildQuery();
    }

    /**
     * Builds and returns the SQL query to get the next page's first row for the search results
     * query.
     */
    @Nullable
    public static String getSearchMediaNextPageKeyQuery(
            @NonNull SearchMediaQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull String table) {
        if (query.getPageSize() == Integer.MAX_VALUE) {
            return null;
        }

        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(table)
                .setProjection(List.of(
                        PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName(),
                        PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName()
                ))
                .setSortOrder(
                        String.format(
                                Locale.ROOT,
                                "%s DESC, %s DESC",
                                PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName(),
                                PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName()
                        )
                )
                .setLimit(1)
                .setOffset(query.getPageSize());

        return queryBuilder.buildQuery();
    }

    /**
     * Builds and returns the SQL query to get the previous page contents for the search results
     * from the previous page.
     *
     * We fetch the whole page and not just one key because it is possible that the previous page
     * is smaller than the page size. So, we get the whole page and only use the last row item to
     * get the previous page key.
     */
    public static String getSearchMediaPreviousPageQuery(
            @NonNull SearchMediaQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull String table) {
        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(table)
                .setProjection(List.of(
                        PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName(),
                        PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName()
                )).setSortOrder(
                        String.format(
                                Locale.ROOT,
                                "%s ASC, %s ASC",
                                PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName(),
                                PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName()
                        )
                ).setLimit(query.getPageSize());

        return queryBuilder.buildQuery();
    }

    /**
     * Deletes all the obsolete search results from the database.
     *
     * @param database SQLiteDatabase object that contains the database connection.
     * @param searchRequestIds List of search request ids that identify the rows that need to be
     *                         deleted.
     * @param isLocal This is true when the local sync results info needs to clear,
     *                otherwise it is false.
     * @return The number of items that were deleted.
     */
    public static int clearObsoleteSearchResults(
            @NonNull SQLiteDatabase database,
            @NonNull List<Integer> searchRequestIds,
            boolean isLocal) {
        requireNonNull(database);
        requireNonNull(searchRequestIds);
        if (searchRequestIds.isEmpty()) {
            Log.d(TAG, "No search request ids received for clearing search results");
            return 0;
        }

        final String whereClause;

        if (isLocal) {
            whereClause = String.format(
                    Locale.ROOT,
                    "%s IN ('%s') AND %s IS NULL",
                    PickerSQLConstants.SearchResultMediaTableColumns
                            .SEARCH_REQUEST_ID.getColumnName(),
                    searchRequestIds.stream().map(Object::toString)
                            .collect(Collectors.joining("','")),
                    PickerSQLConstants.SearchResultMediaTableColumns.CLOUD_ID.getColumnName());
        } else {
            whereClause = String.format(
                    Locale.ROOT,
                    "%s IN ('%s') AND %s IS NOT NULL",
                    PickerSQLConstants.SearchResultMediaTableColumns
                            .SEARCH_REQUEST_ID.getColumnName(),
                    searchRequestIds.stream().map(Object::toString)
                            .collect(Collectors.joining("','")),
                    PickerSQLConstants.SearchResultMediaTableColumns.CLOUD_ID.getColumnName());
        }

        final int deletedSearchResultsCount = database.delete(
                PickerSQLConstants.Table.SEARCH_RESULT_MEDIA.name(),
                whereClause,
                /* whereArgs */ null);
        Log.d(TAG, "Deleted number of search results: " + deletedSearchResultsCount);
        return deletedSearchResultsCount;
    }

    /**
     * Clears all cached search results from the database.
     *
     * @param database SQLiteDatabase object that contains the database connection.
     * @return The number of items that were updated.
     */
    public static int clearAllSearchResults(@NonNull SQLiteDatabase database) {
        requireNonNull(database);

        int searchResultsDeletionCount =
                database.delete(
                        PickerSQLConstants.Table.SEARCH_RESULT_MEDIA.name(),
                        /* whereClause */ null,
                        /* whereArgs */ null);

        Log.d(TAG, String.format(
                Locale.ROOT,
                "Deleted %s rows in search results table",
                searchResultsDeletionCount));

        return searchResultsDeletionCount;
    }
}
