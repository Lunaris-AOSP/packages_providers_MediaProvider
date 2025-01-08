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

package com.android.photopicker.features.categorygrid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken.CATEGORY_GRID
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.selection.SelectionModifiedResult.FAILURE_SELECTION_LIMIT_EXCEEDED
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.CategoryType
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.insertMonthSeparators
import com.android.photopicker.extensions.toMediaGridItemFromCategory
import com.android.photopicker.extensions.toMediaGridItemFromMedia
import com.android.photopicker.extensions.toMediaGridItemFromMediaSet
import com.android.photopicker.extensions.toMediaGridItemFromPeopleMediaSet
import com.android.photopicker.features.categorygrid.data.CategoryDataService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The view model for the primary album grid.
 *
 * This view model collects the data from [DataService] and caches it in its scope so that loaded
 * data is saved between navigations so that the composable can maintain list positions when
 * navigating back and forth between routes.
 */
@HiltViewModel
class CategoryGridViewModel
@Inject
constructor(
    private val scopeOverride: CoroutineScope?,
    private val selection: Selection<Media>,
    private val categoryDataService: CategoryDataService,
    private val dataService: DataService,
    private val events: Events,
) : ViewModel() {
    // Check if a scope override was injected before using the default [viewModelScope]
    private val scope: CoroutineScope =
        if (scopeOverride == null) {
            this.viewModelScope
        } else {
            scopeOverride
        }

    // Request Media in batches of 50 items
    private val CATEGORY_GRID_PAGE_SIZE = 50

    // Keep up to 10 pages loaded in memory before unloading pages.
    private val CATEGORY_GRID_MAX_ITEMS_IN_MEMORY = CATEGORY_GRID_PAGE_SIZE * 10

    /**
     * Returns [PagingData] of type [MediaGridItem] as a [Flow] containing media for the album
     * represented by [albumId].
     */
    fun getAlbumMedia(album: Group.Album): Flow<PagingData<MediaGridItem>> {
        val pagerForAlbumMedia =
            Pager(
                PagingConfig(
                    pageSize = CATEGORY_GRID_PAGE_SIZE,
                    maxSize = CATEGORY_GRID_MAX_ITEMS_IN_MEMORY,
                )
            ) {
                // pagingSource
                dataService.albumMediaPagingSource(album)
            }

        /** Export the data from the pager and prepare it for use in the [AlbumMediaGrid] */
        val albumMedia =
            pagerForAlbumMedia.flow
                .toMediaGridItemFromMedia()
                .insertMonthSeparators()
                // After the load and transformations, cache the data in the viewModelScope.
                // This ensures that the list position and state will be remembered by the MediaGrid
                // when navigating back to the AlbumGrid route.
                .cachedIn(scope)

        return albumMedia
    }

    /**
     * Returns [PagingData] of type [MediaGridItem] as a [Flow] containing data for user's albums.
     */
    fun getCategoriesAndAlbums(category: Group.Category? = null): Flow<PagingData<MediaGridItem>> {
        val pagerForCategories =
            Pager(
                PagingConfig(
                    pageSize = CATEGORY_GRID_PAGE_SIZE,
                    maxSize = CATEGORY_GRID_MAX_ITEMS_IN_MEMORY,
                )
            ) {
                when {
                    category != null -> {
                        categoryDataService.getCategories(parentCategory = category)
                    }
                    else -> {
                        categoryDataService.getCategories()
                    }
                }
            }

        /** Export the data from the pager and prepare it for use in the [CategoryGrid] */
        val group =
            pagerForCategories.flow
                .toMediaGridItemFromCategory(category)
                // After the load and transformations, cache the data in the viewModelScope.
                // This ensures that the list position and state will be remembered by the MediaGrid
                // when navigating back to the AlbumGrid route.
                .cachedIn(scope)
        return group
    }

    /**
     * Returns [PagingData] of type [MediaGridItem] as a [Flow] containing media for the mediaset
     * represented by [mediaset].
     */
    fun getMediaSetContent(mediaset: Group.MediaSet): Flow<PagingData<MediaGridItem>> {
        val pagerForMediaSetContents =
            Pager(
                PagingConfig(
                    pageSize = CATEGORY_GRID_PAGE_SIZE,
                    maxSize = CATEGORY_GRID_MAX_ITEMS_IN_MEMORY,
                )
            ) {
                categoryDataService.getMediaSetContents(mediaset)
            }

        return pagerForMediaSetContents.flow
            .toMediaGridItemFromMedia()
            .insertMonthSeparators()
            // After the load and transformations, cache the data in the viewModelScope.
            // This ensures that the list position and state will be remembered by the MediaGrid
            // when navigating back to the AlbumGrid route.
            .cachedIn(scope)
    }

    /**
     * Returns [PagingData] of type [MediaGridItem] as a [Flow] containing media for the category
     * represented by [categoryId].
     */
    fun getMediaSets(category: Group.Category): Flow<PagingData<MediaGridItem>> {
        val pagerForMediaSets =
            Pager(
                PagingConfig(
                    pageSize = CATEGORY_GRID_PAGE_SIZE,
                    maxSize = CATEGORY_GRID_MAX_ITEMS_IN_MEMORY,
                )
            ) {
                categoryDataService.getMediaSets(category)
            }

        /** Export the data from the pager and prepare it for use in the [CategoryGrid] */
        val mediaSets =
            if (category.categoryType == CategoryType.PEOPLE_AND_PETS) {
                pagerForMediaSets.flow
                    .toMediaGridItemFromPeopleMediaSet()
                    // data should always be cached after all transformations are applied
                    .cachedIn(scope)
            } else {
                pagerForMediaSets.flow
                    .toMediaGridItemFromMediaSet()
                    // data should always be cached after all transformations are applied
                    .cachedIn(scope)
            }
        return mediaSets
    }

    /**
     * Click handler that is called when items in the grid are clicked. Selection updates are made
     * in the viewModelScope to ensure they aren't cancelled if the user navigates away from the
     * AlbumMediaGrid composable.
     */
    fun handleAlbumMediaGridItemSelection(
        item: Media,
        selectionLimitExceededMessage: String,
        album: Group.Album,
    ) {
        // Update the selectable values in the received media item.
        val updatedMediaItem =
            Media.withSelectable(item, /* selectionSource */ Telemetry.MediaLocation.ALBUM, album)
        scope.launch {
            val result = selection.toggle(updatedMediaItem)
            if (result == FAILURE_SELECTION_LIMIT_EXCEEDED) {
                events.dispatch(
                    Event.ShowSnackbarMessage(CATEGORY_GRID.token, selectionLimitExceededMessage)
                )
            }
        }
    }

    fun handleMediaSetItemSelection(item: Media, selectionLimitExceededMessage: String) {
        // Update the selectable values in the received media item.
        val updatedMediaItem =
            Media.withSelectable(item, /* selectionSource */ Telemetry.MediaLocation.ALBUM, null)
        scope.launch {
            val result = selection.toggle(updatedMediaItem)
            if (result == FAILURE_SELECTION_LIMIT_EXCEEDED) {
                events.dispatch(
                    Event.ShowSnackbarMessage(CATEGORY_GRID.token, selectionLimitExceededMessage)
                )
            }
        }
    }
}
