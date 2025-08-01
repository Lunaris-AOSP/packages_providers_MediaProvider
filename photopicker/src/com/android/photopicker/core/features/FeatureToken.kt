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

package com.android.photopicker.core.features

/**
 * A feature token which is claimed by a feature. These can be used to designate which feature is
 * making a specific call.
 */
enum class FeatureToken(val token: String) {
    // keep-sorted start
    ALBUM_GRID("ALBUM_GRID"),
    BROWSE("BROWSE"),
    CATEGORY_GRID("CATEGORY_GRID"),
    CLOUD_MEDIA("CLOUD_MEDIA"),
    CORE("CORE"),
    MEDIA_PREPARE("MEDIA_PREPARE"),
    NAVIGATION_BAR("NAVIGATION_BAR"),
    OVERFLOW_MENU("OVERFLOW_MENU"),
    PHOTO_GRID("PHOTO_GRID"),
    PREVIEW("PREVIEW"),
    PRIVACY_EXPLAINER("PRIVACY_EXPLAINER"),
    PROFILE_SELECTOR("PROFILE_SELECTOR"),
    SEARCH("SEARCH"),
    SELECTION_BAR("SELECTION_BAR"),
    SNACK_BAR("SNACK_BAR"),
    // keep-sorted end
}
