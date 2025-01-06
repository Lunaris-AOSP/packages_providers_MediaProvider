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

package src.com.android.photopicker.features.categorygrid.data

import android.os.CancellationSignal
import androidx.paging.PagingSource
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.GroupPageKey
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.features.categorygrid.data.CategoryDataService

class TestCategoryDataServiceImpl : CategoryDataService {
    override fun getCategories(
        parentCategory: Group.Category?,
        cancellationSignal: CancellationSignal?,
    ): PagingSource<GroupPageKey, Group> {
        TODO("Not yet implemented")
    }

    override fun getMediaSets(
        category: Group.Category,
        cancellationSignal: CancellationSignal?,
    ): PagingSource<GroupPageKey, Group.MediaSet> {
        TODO("Not yet implemented")
    }

    override fun getMediaSetContents(
        mediaSet: Group.MediaSet,
        cancellationSignal: CancellationSignal?,
    ): PagingSource<MediaPageKey, Media> {
        TODO("Not yet implemented")
    }
}
