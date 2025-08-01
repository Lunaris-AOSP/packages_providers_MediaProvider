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

package com.android.providers.media.photopicker.v2.model;

import static android.provider.MediaStore.MY_USER_ID;

import static com.android.providers.media.photopicker.v2.PickerDataLayerV2.PINNED_ALBUMS_ORDER;

import static java.util.Objects.requireNonNull;

import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.v2.PickerDataLayerV2;
import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;

/**
 * A wrapper for Albums cursor to map a value from the cursor received from CMP to the value in the
 * projected value in the response.
 */
public class AlbumsCursorWrapper extends CursorWrapper {
    private static final String TAG = "AlbumsCursorWrapper";
    // This media ID  points to a ghost/unavailable media item.
    public static final String EMPTY_MEDIA_ID = "";

    @NonNull final String mCoverAuthority;
    @Nullable final String mLocalAuthority;

    public AlbumsCursorWrapper(
            @NonNull Cursor cursor,
            @NonNull String authority,
            @Nullable String localAuthority) {
        super(requireNonNull(cursor));
        mCoverAuthority = requireNonNull(authority);
        mLocalAuthority = localAuthority;
    }

    @Override
    public int getColumnCount() {
        return PickerSQLConstants.AlbumResponse.values().length;
    }

    @Override
    public int getColumnIndex(String columnName) {
        try {
            return getColumnIndexOrThrow(columnName);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Column not present in cursor." + e);
            return -1;
        }
    }

    @Override
    public int getColumnIndexOrThrow(String columnName) throws IllegalArgumentException {
        return PickerSQLConstants.mapColumnNameToAlbumResponseColumn(columnName).ordinal();
    }

    @Override
    public String getColumnName(int columnIndex) {
        return PickerSQLConstants.AlbumResponse.values()[columnIndex].getColumnName();
    }

    @Override
    public String[] getColumnNames() {
        String[] columnNames = new String[PickerSQLConstants.AlbumResponse.values().length];
        for (int iterator = 0;
                iterator < PickerSQLConstants.AlbumResponse.values().length;
                iterator++) {
            columnNames[iterator] = PickerSQLConstants.AlbumResponse.values()[iterator]
                    .getColumnName();
        }
        return columnNames;
    }

    @Override
    public long getLong(int columnIndex) {
        return Long.parseLong(getString(columnIndex));
    }

    @Override
    public int getInt(int columnIndex) {
        return Integer.parseInt(getString(columnIndex));
    }

    @Override
    public String getString(int columnIndex) {
        final String columnName = getColumnName(columnIndex);
        final PickerSQLConstants.AlbumResponse albumResponse =
                PickerSQLConstants.mapColumnNameToAlbumResponseColumn(columnName);
        final String albumId = getWrappedCursor().getString(
                getWrappedCursor().getColumnIndexOrThrow(
                        PickerSQLConstants.AlbumResponse.ALBUM_ID.getColumnName()));

        switch (albumResponse) {
            case AUTHORITY:
                if (PickerDataLayerV2.MERGED_ALBUMS.contains(albumId)) {
                    // By default, always keep merged album authority as local.
                    return mLocalAuthority;
                }
                return mCoverAuthority;

            case UNWRAPPED_COVER_URI:
                final String mediaId = getMediaIdFromWrappedCursor();

                if (EMPTY_MEDIA_ID.equals(mediaId)) {
                    return Uri.EMPTY.toString();
                } else {
                    return PickerUriResolver
                            .getMediaUri(getEncodedUserAuthority(mCoverAuthority))
                            .buildUpon()
                            .appendPath(mediaId)
                            .build()
                            .toString();
                }

            case PICKER_ID:
                if (PINNED_ALBUMS_ORDER.contains(albumId)) {
                    return Integer.toString(
                            Integer.MAX_VALUE - PINNED_ALBUMS_ORDER.indexOf(columnName)
                    );
                } else {
                    return Integer.toString(getMediaIdFromWrappedCursor().hashCode());
                }

            case COVER_MEDIA_SOURCE:
                if (mCoverAuthority.equals(mLocalAuthority)) {
                    return MediaSource.LOCAL.toString();
                } else {
                    return MediaSource.REMOTE.toString();
                }

            case ALBUM_ID:
                return albumId;

            case DATE_TAKEN:
                if (PINNED_ALBUMS_ORDER.contains(albumId)) {
                    return Long.toString(Long.MAX_VALUE);
                }
                // Fall through to return the wrapped cursor value as it is.
            case ALBUM_NAME:
            default:
                // These values must be present in the cursor received from CMP. Note that this
                // works because the column names in the returned cursor is the same as the column
                // name received from the CMP.
                return getWrappedCursor().getString(
                        getWrappedCursor().getColumnIndexOrThrow(columnName)
                );
        }
    }

    @Override
    public int getType(int columnIndex) {
        final String columnName = getColumnName(columnIndex);
        final PickerSQLConstants.AlbumResponse albumResponse =
                PickerSQLConstants.mapColumnNameToAlbumResponseColumn(columnName);


        switch (albumResponse) {
            case AUTHORITY:
            case UNWRAPPED_COVER_URI:
            case COVER_MEDIA_SOURCE:
                return FIELD_TYPE_STRING;

            case PICKER_ID:
                return FIELD_TYPE_INTEGER;

            case DATE_TAKEN:
            case ALBUM_ID:
            case ALBUM_NAME:
            default:
                // These values must be present in the cursor received from CMP. Note that this
                // works because the column names in the returned cursor is the same as the column
                // name received from the CMP.
                return getWrappedCursor().getType(
                        getWrappedCursor().getColumnIndexOrThrow(columnName)
                );
        }
    }

    /**
     * Extract and return the cover media id from the wrapped cursor.
     */
    private String getMediaIdFromWrappedCursor() {
        final String mediaId = getWrappedCursor().getString(
                getWrappedCursor().getColumnIndexOrThrow(
                        CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID)
        );
        requireNonNull(mediaId);
        return mediaId;
    }

    private String getEncodedUserAuthority(String authority) {
        return MY_USER_ID + "@" + authority;
    }
}
