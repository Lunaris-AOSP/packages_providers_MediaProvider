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

package com.android.photopicker.features.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.android.photopicker.core.Background
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.selection.SelectionModifiedResult
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.insertMonthSeparators
import com.android.photopicker.extensions.toMediaGridItemFromMedia
import com.android.photopicker.features.search.data.SearchDataService
import com.android.photopicker.features.search.model.SearchSuggestion
import com.android.photopicker.features.search.model.SearchSuggestionType
import com.android.photopicker.features.search.model.UserSearchStateInfo
import com.google.common.annotations.VisibleForTesting
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for handling search functionality.
 *
 * This ViewModel manages the search state, including the search term, suggestions, and whether a
 * search was triggered by a query or a suggestion.
 *
 * @param scopeOverride An optional CoroutineScope to be used instead of the default viewModelScope.
 * @param backgroundDispatcher A CoroutineDispatcher for running background tasks. This dispatcher
 *   is marked as `internal` and can be accessed by other classes within the same module.
 * @param searchDataService The service for fetching search suggestions.
 */
@HiltViewModel
class SearchViewModel
@Inject
constructor(
    private val scopeOverride: CoroutineScope?,
    @Background val backgroundDispatcher: CoroutineDispatcher,
    private val searchDataService: SearchDataService,
    private val dataService: DataService,
    private val selection: Selection<Media>,
    private val events: Events,
    private val configurationManager: ConfigurationManager,
) : ViewModel() {

    companion object {
        private const val SEARCH_RESULT_GRID_PAGE_SIZE = 50
        private const val SEARCH_RESULT_GRID_MAX_ITEMS_IN_MEMORY = SEARCH_RESULT_GRID_PAGE_SIZE * 10
        const val ZERO_STATE_SEARCH_QUERY = ""
        const val HISTORY_SUGGESTION_MAX_LIMIT = 3
        const val FACE_SUGGESTION_MAX_LIMIT = 6
        const val ALL_SUGGESTION_MAX_LIMIT = 6
    }

    // Check if a scope override was injected before using the default [viewModelScope]
    private val scope: CoroutineScope = scopeOverride ?: this.viewModelScope

    private var searchJob: SearchJob? = null

    /**
     * Represents the current state of the search.
     *
     * It can be one of the following:
     * - **Inactive:** The initial state where no search is active.
     * - **Active.QuerySearch:** A search is active with a user-entered query.
     * - **Active.SuggestionSearch:** A search is active using a selected suggestion.
     */
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Inactive)
    val searchState: StateFlow<SearchState> = _searchState

    /**
     * Holds the current state of search suggestions list.
     *
     * This `StateFlow` emits updates whenever the list of search suggestions changes. It provides
     * various types of suggestions (e.g., history, face, others).
     */
    private val _searchSuggestions = MutableStateFlow(SearchSuggestions())
    val searchSuggestions: StateFlow<SearchSuggestions> = _searchSuggestions

    /**
     * Holds the value of the current profile's search enabled state
     *
     * This `StateFlow` emits updates whenever the search enabled state of a profile changes.
     */
    val userSearchStateInfo: StateFlow<UserSearchStateInfo> = searchDataService.userSearchStateInfo

    private val suggestionCache = SearchSuggestionCache()

    init {
        fetchSuggestions(ZERO_STATE_SEARCH_QUERY)
        // Listen to available provider changes and clear search suggestions cache.
        scope.launch(backgroundDispatcher) {
            dataService.availableProviders.collect {
                suggestionCache.clearSuggestions()
                fetchSuggestions(ZERO_STATE_SEARCH_QUERY)
            }
        }
    }

    /**
     * Updates the search term and fetches new suggestions.
     *
     * This function cancels any current search job and starts search as a new job.
     *
     * @param query The new search term.
     */
    fun fetchSuggestions(query: String) {
        searchJob?.cancel()

        val cachedSuggestion: Collection<SearchSuggestion>? = suggestionCache.getSuggestions(query)
        when (cachedSuggestion != null) {
            true -> {
                synchronized(cachedSuggestion) {
                    val transformedSuggestions =
                        getTransformedSuggestions(cachedSuggestion, query.isEmpty())
                    _searchSuggestions.update { transformedSuggestions }
                }
            }

            else -> {
                searchJob = SearchJob(scope)
                searchJob?.let { job ->
                    job.startSearch() {
                        withContext(backgroundDispatcher) {
                            val newSuggestions =
                                searchDataService.getSearchSuggestions(
                                    query,
                                    cancellationSignal = job.cancellationSignal,
                                )
                            val transformedSuggestions: SearchSuggestions =
                                getTransformedSuggestions(newSuggestions, query.isEmpty())
                            suggestionCache.addSuggestions(query, transformedSuggestions)
                            _searchSuggestions.update { transformedSuggestions }
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns [PagingData] of type [MediaGridItem] as a [Flow] containing search result for a
     * search suggestion or query based on search state.
     */
    fun getSearchResults(): Flow<PagingData<MediaGridItem>> {
        val currentSearchState = _searchState.value
        val pagerForSearchResult =
            Pager(
                PagingConfig(
                    pageSize = SEARCH_RESULT_GRID_PAGE_SIZE,
                    maxSize = SEARCH_RESULT_GRID_MAX_ITEMS_IN_MEMORY,
                )
            ) {
                when (currentSearchState) {
                    is SearchState.Active.SuggestionSearch -> {
                        scope.launch {
                            events.dispatch(
                                Event.ReportPhotopickerSearchInfo(
                                    FeatureToken.SEARCH.token,
                                    configurationManager.configuration.value.sessionId,
                                    Telemetry.SearchMethod.SUGGESTED_SEARCHES,
                                )
                            )
                        }
                        searchDataService.getSearchResults(
                            suggestion = currentSearchState.suggestion
                        )
                    }
                    is SearchState.Active.QuerySearch -> {
                        scope.launch {
                            events.dispatch(
                                Event.ReportPhotopickerSearchInfo(
                                    FeatureToken.SEARCH.token,
                                    configurationManager.configuration.value.sessionId,
                                    Telemetry.SearchMethod.SEARCH_QUERY,
                                )
                            )
                        }
                        searchDataService.getSearchResults(searchText = currentSearchState.query)
                    }
                    is SearchState.Inactive -> {
                        throw IllegalStateException("Cannot create Pager in inactive search state.")
                    }
                }
            }

        /** Export the data from the pager and prepare it for use in the [MediaGridItem] */
        return pagerForSearchResult.flow
            .toMediaGridItemFromMedia()
            .insertMonthSeparators()
            // After the load and transformations, cache the data in the viewModelScope.
            // This ensures that the list position and state will be remembered by the
            // MediaGrid when navigating back to the SearchResult route.
            .cachedIn(scope)
    }

    /** Sets Inactive search state where no search result is active */
    fun clearSearch() {
        _searchState.value = SearchState.Inactive
    }

    /**
     * Initiates a search based on a selected search suggestion.
     *
     * @param suggestion The `SearchSuggestion` selected by the user.
     */
    fun performSearch(suggestion: SearchSuggestion) {
        _searchState.value = SearchState.Active.SuggestionSearch(suggestion)
        suggestionCache.updateHistorySuggestion(suggestion)
    }

    /**
     * Initiates a search based on a user-provided query string.
     *
     * @param query The search query entered by the user.
     */
    fun performSearch(query: String) {
        _searchState.value = SearchState.Active.QuerySearch(query)
        suggestionCache.updateHistorySuggestion(query)
    }

    /**
     * Click handler that is called when items in the grid are clicked. Selection updates are made
     * in the viewModelScope to ensure they aren't canceled if the user navigates away from the
     * PhotoGrid composable.
     */
    fun handleGridItemSelection(item: Media, selectionLimitExceededMessage: String) {
        val updatedMediaItem =
            Media.withSelectable(
                item,
                /* selectionSource */ Telemetry.MediaLocation.SEARCH_GRID,
                /* album */ null,
            )
        scope.launch {
            val result = selection.toggle(updatedMediaItem)
            if (result == SelectionModifiedResult.FAILURE_SELECTION_LIMIT_EXCEEDED) {
                scope.launch {
                    events.dispatch(
                        Event.ShowSnackbarMessage(
                            FeatureToken.SEARCH.token,
                            selectionLimitExceededMessage,
                        )
                    )
                }
            }
        }
    }

    /**
     * Method that updates the list for each type of suggestion from the suggestions result and
     * returns a trimmed list of search suggestions to show on UI
     *
     * @param suggestions The original collection of ordered `SearchSuggestion` objects.
     * @param isZeroSearchState A boolean value indicating if the search query is empty.
     * @return [SearchSuggestions] object with different types of suggestions that need to be
     *   displayed on the UI.
     */
    private fun getTransformedSuggestions(
        suggestions: Collection<SearchSuggestion>,
        isZeroSearchState: Boolean,
    ): SearchSuggestions {
        val history = mutableListOf<SearchSuggestion>()
        val face = mutableListOf<SearchSuggestion>()
        val other = mutableListOf<SearchSuggestion>()

        for (suggestion in suggestions) {
            when (suggestion.type) {
                SearchSuggestionType.HISTORY ->
                    if (history.size < HISTORY_SUGGESTION_MAX_LIMIT) {
                        history.add(suggestion)
                    }

                SearchSuggestionType.FACE ->
                    if (isZeroSearchState) {
                        if (face.size < FACE_SUGGESTION_MAX_LIMIT) {
                            face.add(suggestion)
                        }
                    } else {
                        if (other.size < ALL_SUGGESTION_MAX_LIMIT) {
                            other.add(suggestion)
                        }
                    }

                else ->
                    if (other.size < ALL_SUGGESTION_MAX_LIMIT) {
                        other.add(suggestion)
                    }
            }
            if (
                history.size >= HISTORY_SUGGESTION_MAX_LIMIT &&
                    face.size >= FACE_SUGGESTION_MAX_LIMIT &&
                    other.size >= ALL_SUGGESTION_MAX_LIMIT
            )
                break // Early exit
        }
        return SearchSuggestions(history, face, other)
    }

    @VisibleForTesting
    fun getCachedSuggestions(): SearchSuggestionCache {
        return suggestionCache
    }
}

/** Represents the different states of the search functionality. */
sealed class SearchState {
    object Inactive : SearchState()

    sealed class Active : SearchState() {
        data class QuerySearch(val query: String) : Active()

        data class SuggestionSearch(val suggestion: SearchSuggestion) : Active()
    }
}
