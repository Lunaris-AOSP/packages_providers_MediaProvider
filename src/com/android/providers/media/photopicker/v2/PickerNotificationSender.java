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

package com.android.providers.media.photopicker.v2;

import static com.android.providers.media.photopicker.v2.PickerUriResolverV2.ALBUM_PATH_SEGMENT;
import static com.android.providers.media.photopicker.v2.PickerUriResolverV2.AVAILABLE_PROVIDERS_PATH_SEGMENT;
import static com.android.providers.media.photopicker.v2.PickerUriResolverV2.MEDIA_PATH_SEGMENT;
import static com.android.providers.media.photopicker.v2.PickerUriResolverV2.MEDIA_SETS_PATH_SEGMENT;
import static com.android.providers.media.photopicker.v2.PickerUriResolverV2.MEDIA_SET_CONTENTS_PATH_SEGMENT;
import static com.android.providers.media.photopicker.v2.PickerUriResolverV2.PICKER_INTERNAL_PATH_SEGMENT;
import static com.android.providers.media.photopicker.v2.PickerUriResolverV2.PICKER_V2_PATH_SEGMENT;
import static com.android.providers.media.photopicker.v2.PickerUriResolverV2.SEARCH_RESULT_MEDIA_PATH_SEGMENT;
import static com.android.providers.media.photopicker.v2.PickerUriResolverV2.UPDATE_PATH_SEGMENT;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import com.android.providers.media.photopicker.PickerSyncController;

public class PickerNotificationSender {
    private static final String TAG = "PickerNotificationSender";

    /**
     * Flag for {@link #notifyChange(Uri, ContentObserver, int)} to indicate that this notification
     * should not be subject to any delays when dispatching to apps running in the background.
     * Using this flag may negatively impact system health and performance, and should be used
     * sparingly.
     */
    public static final int NOTIFY_NO_DELAY = 1 << 15;

    private static final Uri AVAILABLE_PROVIDERS_UPDATE_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(MediaStore.AUTHORITY)
            .appendPath(PICKER_INTERNAL_PATH_SEGMENT)
            .appendPath(PICKER_V2_PATH_SEGMENT)
            .appendPath(AVAILABLE_PROVIDERS_PATH_SEGMENT)
            .appendPath(UPDATE_PATH_SEGMENT)
            .build();

    private static final Uri MEDIA_UPDATE_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(MediaStore.AUTHORITY)
            .appendPath(PICKER_INTERNAL_PATH_SEGMENT)
            .appendPath(PICKER_V2_PATH_SEGMENT)
            .appendPath(MEDIA_PATH_SEGMENT)
            .appendPath(UPDATE_PATH_SEGMENT)
            .build();

    private static final Uri ALBUM_UPDATE_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(MediaStore.AUTHORITY)
            .appendPath(PICKER_INTERNAL_PATH_SEGMENT)
            .appendPath(PICKER_V2_PATH_SEGMENT)
            .appendPath(ALBUM_PATH_SEGMENT)
            .appendPath(UPDATE_PATH_SEGMENT)
            .build();

    private static final Uri SEARCH_RESULTS_UPDATE_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(MediaStore.AUTHORITY)
            .appendPath(PICKER_INTERNAL_PATH_SEGMENT)
            .appendPath(PICKER_V2_PATH_SEGMENT)
            .appendPath(SEARCH_RESULT_MEDIA_PATH_SEGMENT)
            .appendPath(UPDATE_PATH_SEGMENT)
            .build();

    private static final Uri MEDIA_SETS_UPDATE_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(MediaStore.AUTHORITY)
            .appendPath(PICKER_INTERNAL_PATH_SEGMENT)
            .appendPath(PICKER_V2_PATH_SEGMENT)
            .appendPath(MEDIA_SETS_PATH_SEGMENT)
            .appendPath(UPDATE_PATH_SEGMENT)
            .build();

    private static final Uri MEDIA_SET_CONTENT_UPDATE_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(MediaStore.AUTHORITY)
            .appendPath(PICKER_INTERNAL_PATH_SEGMENT)
            .appendPath(PICKER_V2_PATH_SEGMENT)
            .appendPath(MEDIA_SET_CONTENTS_PATH_SEGMENT)
            .appendPath(UPDATE_PATH_SEGMENT)
            .build();

    /**
     * Send media update notification to the registered {@link android.database.ContentObserver}-s.
     * @param context The application context.
     */
    public static void notifyAvailableProvidersChange(@NonNull Context context) {
        Log.d(TAG, "Sending a notification for available providers update");
        context.getContentResolver().notifyChange(
                AVAILABLE_PROVIDERS_UPDATE_URI, /* observer= */ null, NOTIFY_NO_DELAY);
    }

    /**
     * Send media update notification to the registered {@link android.database.ContentObserver}-s.
     * @param context The application context.
     */
    public static void notifyMediaChange(@NonNull Context context) {
        Log.d(TAG, "Sending a notification for media update");
        context.getContentResolver().notifyChange(MEDIA_UPDATE_URI, /* observer= */ null);
        notifyMergedAlbumMediaChange(context, PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY);
    }

    /**
     * Send album media update notification to the registered
     * {@link android.database.ContentObserver}-s.
     * @param context The application context.
     * @param albumAuthority authority of the updated album
     * @param albumId ID of the updated album
     */
    public static void notifyAlbumMediaChange(
            @NonNull Context context,
            @NonNull String albumAuthority,
            @NonNull String albumId) {
        Log.d(TAG, "Sending a notification for album media update " + albumId);
        context.getContentResolver().notifyChange(
                getAlbumMediaUpdateUri(albumAuthority, albumId),
                /* observer= */ null);
    }

    /**
     * Send album media update notification to the registered
     * {@link android.database.ContentObserver}-s for all merged album updates.
     * @param context The application context.
     * @param localAuthority authority of the local provider.
     */
    public static void notifyMergedAlbumMediaChange(
            @NonNull Context context,
            @NonNull String localAuthority) {
        for (String mergedAlbumId: PickerDataLayerV2.MERGED_ALBUMS) {
            Log.d(TAG, "Sending a notification for merged album media update " + mergedAlbumId);

            // By default, always keep merged album authority as local.
            notifyAlbumMediaChange(context, localAuthority, mergedAlbumId);
        }
    }

    /**
     * Send search results update notification to the registered
     * {@link android.database.ContentObserver}-s.
     * @param context The application context.
     * @param searchRequestId Search request ID corresponding for which the search results
     *                        have updated.
     */
    public static void notifySearchResultsChange(
            @NonNull Context context,
            int searchRequestId) {
        Log.d(TAG, "Sending a notification for search results update " + searchRequestId);
        context.getContentResolver().notifyChange(
                getSearchResultsUpdateUri(searchRequestId),
                /* observer= */ null);
    }

    /**
     * Send media sets update notification to the registered
     * {@link android.database.ContentObserver}-s.
     * @param context The application context.
     * @param categoryId Category ID for which the media sets have updated.
     */
    public static void notifyMediaSetsChange(
            @NonNull Context context,
            @NonNull String categoryId) {
        requireNonNull(context);
        requireNonNull(categoryId);
        Log.d(TAG, "Sending notification for media sets update for the "
                + "given categoryId " + categoryId);
        context.getContentResolver().notifyChange(
                getMediaSetsUpdateUri(categoryId), /* observer */ null
        );
    }

    private static Uri getMediaSetsUpdateUri(@NonNull String categoryId) {
        return MEDIA_SETS_UPDATE_URI
                .buildUpon()
                .appendPath(categoryId)
                .build();
    }

    /**
     * Send media set content update notification to the registered
     * {@link android.database.ContentObserver}-s.
     * @param context The application context.
     * @param mediaSetId MediaSet ID for which the media content has updated.
     */
    public static void notifyMediaSetContentChange(
            @NonNull Context context, @NonNull String mediaSetId) {
        requireNonNull(context);
        requireNonNull(mediaSetId);
        Log.d(TAG, "Sending notification for media set content update for the "
                + "given mediaSet " + mediaSetId);
        context.getContentResolver().notifyChange(
                getMediaSetContentUpdateUri(mediaSetId), /* observer */ null
        );
    }

    private static Uri getAlbumMediaUpdateUri(
            @NonNull String albumAuthority,
            @NonNull String albumId) {
        return ALBUM_UPDATE_URI
                .buildUpon()
                .appendPath(requireNonNull(albumAuthority))
                .appendPath(requireNonNull(albumId))
                .build();
    }

    private static Uri getSearchResultsUpdateUri(int searchRequestId) {
        return SEARCH_RESULTS_UPDATE_URI
                .buildUpon()
                .appendPath(Integer.toString(searchRequestId))
                .build();
    }

    private static Uri getMediaSetContentUpdateUri(@NonNull String mediaSetId) {
        return MEDIA_SET_CONTENT_UPDATE_URI
                .buildUpon()
                .appendPath(mediaSetId)
                .build();
    }
}
