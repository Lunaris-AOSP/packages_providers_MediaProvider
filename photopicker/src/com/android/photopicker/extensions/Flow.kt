/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.photopicker.extensions

import androidx.paging.PagingData
import androidx.paging.insertSeparators
import androidx.paging.map
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.data.model.CategoryType
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * An extension function to prepare a flow of [PagingData<Media>] to be provided to the [MediaGrid]
 * composable, by wrapping all of the [Media] objects in a [MediaGridItem.MediaItem].
 *
 * @return A [PagingData<MediaGridItem.MediaItem] that can be processed further, or provided to the
 *   [MediaGrid].
 */
fun Flow<PagingData<Media>>.toMediaGridItemFromMedia(): Flow<PagingData<MediaGridItem.MediaItem>> {
    return this.map { pagingData -> pagingData.map { MediaGridItem.MediaItem(it) } }
}

/**
 * An extension function to prepare a flow of [PagingData<Album>] to be provided to the [MediaGrid]
 * composable, by wrapping all of the [Album] objects in a [MediaGridItem].
 *
 * @return A [PagingData<MediaGridItem>] that can be processed further, or provided to the
 *   [MediaGrid].
 */
fun Flow<PagingData<Group.Album>>.toMediaGridItemFromAlbum(): Flow<PagingData<MediaGridItem>> {
    return this.map { pagingData -> pagingData.map { MediaGridItem.AlbumItem(it) } }
}

/**
 * An extension function to prepare a flow of [PagingData<MediaSet>] to be provided to the
 * [MediaGrid] composable, by wrapping all of the [MediaSet] objects in a [MediaGridItem].
 *
 * @return A [PagingData<MediaGridItem>] that can be processed further, or provided to the
 *   [MediaGrid].
 */
fun Flow<PagingData<Group.MediaSet>>.toMediaGridItemFromMediaSet():
    Flow<PagingData<MediaGridItem>> {
    return this.map { pagingData -> pagingData.map { MediaGridItem.MediaSetItem(it) } }
}

/**
 * An extension function to prepare a flow of [PagingData<MediaSet>] for People & Pets category to
 * be provided to the [MediaGrid] composable, by wrapping all of the [MediaSet] objects in a
 * [MediaGridItem].
 *
 * @return A [PagingData<MediaGridItem>] that can be processed further, or provided to the
 *   [MediaGrid].
 */
fun Flow<PagingData<Group.MediaSet>>.toMediaGridItemFromPeopleMediaSet():
    Flow<PagingData<MediaGridItem>> {
    return this.map { pagingData -> pagingData.map { MediaGridItem.PersonMediaSetItem(it) } }
}

/**
 * An extension function to prepare a flow of [PagingData<Category>] to be provided to the
 * [MediaGrid] composable, by wrapping all of the [Category] objects in a [MediaGridItem].
 *
 * @return A [PagingData<MediaGridItem>] that can be processed further, or provided to the
 *   [MediaGrid].
 */
fun Flow<PagingData<Group>>.toMediaGridItemFromCategory(
    category: Group.Category? = null
): Flow<PagingData<MediaGridItem>> {
    return this.map { pagingData ->
        pagingData.map { group ->
            when (group) {
                is Group.MediaSet -> {
                    if (category != null && category.categoryType == CategoryType.PEOPLE_AND_PETS) {
                        MediaGridItem.PersonMediaSetItem(group)
                    } else {
                        MediaGridItem.MediaSetItem(group)
                    }
                }
                is Group.Category -> MediaGridItem.CategoryItem(group)
                is Group.Album -> MediaGridItem.AlbumItem(group)
            }
        }
    }
}

/**
 * An extension function which accepts a flow of [PagingData<MediaGridItem.MediaItem>] (the actual
 * [Media] grid representation wrappers) and processes them inserting month separators in between
 * items that have different month.
 *
 * @return A [PagingData<MediaGridItem>] that can be processed further, or provided to the
 *   [MediaGrid].
 *
 * TODO(b/323830434): Update logic for separators after 4th row when UX finalizes. Note: This does
 *   not include a separator for the first month of data.
 */
fun Flow<PagingData<MediaGridItem.MediaItem>>.insertMonthSeparators(
    recentsCellCount: Int = Int.MIN_VALUE
): Flow<PagingData<MediaGridItem>> {
    return this.map {
        it.insertSeparators { before, after ->
            val afterIndex = after?.media?.index ?: Int.MAX_VALUE

            // If this is the first or last item in the list, no separators are required.
            // If the item index is populated and it is part of the recents section,
            // don't add separators.
            if (after == null || before == null || afterIndex <= recentsCellCount) {
                return@insertSeparators null
            }

            // ZoneOffset.UTC is used here because all timestamps are expected to be millisecionds
            // since epoch in UTC. See [CloudMediaProviderContract#MediaColumns.DATE_TAKEN_MILLIS]
            val beforeLocalDateTime =
                LocalDateTime.ofEpochSecond((before.media.getTimestamp() / 1000), 0, ZoneOffset.UTC)
            val afterLocalDateTime =
                LocalDateTime.ofEpochSecond((after.media.getTimestamp() / 1000), 0, ZoneOffset.UTC)

            // Always add a separator after the recents section.
            if (
                beforeLocalDateTime.getMonth() != afterLocalDateTime.getMonth() ||
                    afterIndex == (recentsCellCount + 1)
            ) {
                val format =
                    // If the current calendar year is different from the items year, append the
                    // year to to the month string.
                    if (afterLocalDateTime.getYear() != LocalDateTime.now().getYear()) "MMMM yyyy"

                    // The year is the same, so just use the month's name.
                    else "MMMM"

                // The months are different, so insert a separator between [before] and [after]
                // by returning it here.
                MediaGridItem.SeparatorItem(
                    afterLocalDateTime.format(DateTimeFormatter.ofPattern(format))
                )
            } else {
                // Both Media have the same month, so no separator needed between the two.
                null
            }
        }
    }
}

/**
 * An extension function which filters all the available user profiles based on whether a profile is
 * hidden or not.
 *
 * @return A list of all the user profiles available to the photopicker
 */
fun Flow<UserStatus>.getUserProfilesVisibleToPhotopicker(): Flow<List<UserProfile>> {
    return this.map {
        it.allProfiles.filterNot {
            it.disabledReasons.contains(UserProfile.DisabledReason.QUIET_MODE_DO_NOT_SHOW)
        }
    }
}
