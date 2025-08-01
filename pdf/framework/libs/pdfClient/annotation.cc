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

#include "annotation.h"

#include <utils/pdf_strings.h>

#include "image_object.h"
#include "logging.h"
#include "path_object.h"

#define LOG_TAG "annotation"

namespace pdfClient {

std::vector<PageObject*> StampAnnotation::GetObjects() const {
    std::vector<PageObject*> page_objects;
    for (const auto& page_object : pageObjects_) {
        page_objects.push_back(page_object.get());
    }

    return page_objects;
}

bool updateExistingBounds(FPDF_ANNOTATION fpdf_annot, size_t num_bounds,
                          std::vector<Rectangle_f> bounds) {
    for (auto bound_index = 0; bound_index < num_bounds; bound_index++) {
        Rectangle_f rect = bounds[bound_index];
        FS_QUADPOINTSF quad_points = {rect.left, rect.top,    rect.right, rect.top,
                                      rect.left, rect.bottom, rect.right, rect.bottom};
        if (!FPDFAnnot_SetAttachmentPoints(fpdf_annot, bound_index, &quad_points)) {
            LOGD("Failed to update the bounds of highlight annotation");
            return false;
        }
    }
    return true;
}

bool StampAnnotation::PopulateFromPdfiumInstance(FPDF_ANNOTATION fpdf_annot, FPDF_PAGE page) {
    int num_of_objects = FPDFAnnot_GetObjectCount(fpdf_annot);

    for (int object_index = 0; object_index < num_of_objects; object_index++) {
        FPDF_PAGEOBJECT page_object = FPDFAnnot_GetObject(fpdf_annot, object_index);
        int objectType = FPDFPageObj_GetType(page_object);

        std::unique_ptr<PageObject> page_object_;

        switch (objectType) {
            case FPDF_PAGEOBJ_PATH: {
                page_object_ = std::make_unique<PathObject>();
                break;
            }
            case FPDF_PAGEOBJ_IMAGE: {
                page_object_ = std::make_unique<ImageObject>();
                break;
            }
            default: {
                break;
            }
        }

        if (page_object_ && !page_object_->PopulateFromFPDFInstance(page_object, page)) {
            LOGE("Failed to get all the data corresponding to object with index "
                 "%d ",
                 object_index);
            page_object_ = nullptr;
        }

        // Add the page_object_ to the stamp annotation even if page_object_ is null
        // as we are storing empty unique ptr for the unsupported page objects
        AddObject(std::move(page_object_));
    }
    return true;
}

ScopedFPDFAnnotation StampAnnotation::CreatePdfiumInstance(FPDF_DOCUMENT document, FPDF_PAGE page) {
    // Create a ScopedFPDFAnnotation, If it will fail to populate this pdfium annot with desired
    // params, we will return null that will lead to scoped annot getting out of scope and thus
    // getting destroyed
    ScopedFPDFAnnotation scoped_annot =
            ScopedFPDFAnnotation(FPDFPage_CreateAnnot(page, FPDF_ANNOT_STAMP));

    if (!scoped_annot) {
        LOGE("Failed to create stamp Annotation.");
        return nullptr;
    }

    Rectangle_f annotation_bounds = GetBounds();
    FS_RECTF rect;
    rect.left = annotation_bounds.left;
    rect.bottom = annotation_bounds.bottom;
    rect.right = annotation_bounds.right;
    rect.top = annotation_bounds.top;

    if (!FPDFAnnot_SetRect(scoped_annot.get(), &rect)) {
        LOGE("Stamp Annotation bounds couldn't be set");
        return nullptr;
    }

    std::vector<PageObject*> pageObjects = GetObjects();
    for (auto pageObject : pageObjects) {
        ScopedFPDFPageObject scoped_page_object = pageObject->CreateFPDFInstance(document, page);

        if (!scoped_page_object) {
            LOGE("Failed to create page object to add in the stamp annotation");
            return nullptr;
        }

        if (!FPDFAnnot_AppendObject(scoped_annot.get(), scoped_page_object.release())) {
            LOGE("Page object couldn't be inserted in the stamp annotation");
            return nullptr;
        }
    }

    return scoped_annot;
}

bool StampAnnotation::UpdatePdfiumInstance(FPDF_ANNOTATION fpdf_annot, FPDF_DOCUMENT document,
                                           FPDF_PAGE page) {
    if (FPDFAnnot_GetSubtype(fpdf_annot) != FPDF_ANNOT_STAMP) {
        LOGE("Unsupported operation - can't update a stamp annotation with some other type of "
             "annotation");
        return false;
    }

    Rectangle_f new_bounds = GetBounds();
    FS_RECTF rect;
    rect.left = new_bounds.left;
    rect.bottom = new_bounds.bottom;
    rect.right = new_bounds.right;
    rect.top = new_bounds.top;
    if (!FPDFAnnot_SetRect(fpdf_annot, &rect)) {
        LOGE("Failed to update the bounds of the stamp annotation at given index");
        return false;
    }

    // First Remove all the known existing objects from the stamp annotation, and then rewrite
    int num_objects = FPDFAnnot_GetObjectCount(fpdf_annot);
    for (int object_index = num_objects - 1; object_index >= 0; object_index--) {
        FPDF_PAGEOBJECT pageObject = FPDFAnnot_GetObject(fpdf_annot, object_index);
        int object_type = FPDFPageObj_GetType(pageObject);
        if (pageObject != nullptr &&
            (object_type == FPDF_PAGEOBJ_IMAGE || object_type == FPDF_PAGEOBJ_PATH)) {
            if (!FPDFAnnot_RemoveObject(fpdf_annot, object_index)) {
                LOGE("Failed to remove existing object from stamp annotation");
                return false;
            }
        }
    }

    // Rewrite
    std::vector<PageObject*> newPageObjects = GetObjects();
    for (auto pageObject : newPageObjects) {
        ScopedFPDFPageObject scoped_page_object = pageObject->CreateFPDFInstance(document, page);

        if (!scoped_page_object) {
            LOGE("Failed to create new page object to add in the stamp annotation");
            return false;
        }

        if (!FPDFAnnot_AppendObject(fpdf_annot, scoped_page_object.release())) {
            LOGE("Page object couldn't be inserted in the stamp annotation");
            return false;
        }
    }
    return true;
}

bool HighlightAnnotation::PopulateFromPdfiumInstance(FPDF_ANNOTATION fpdf_annot, FPDF_PAGE page) {
    // Get color
    unsigned int R;
    unsigned int G;
    unsigned int B;
    unsigned int A;

    if (!FPDFAnnot_GetColor(fpdf_annot, FPDFANNOT_COLORTYPE_Color, &R, &G, &B, &A)) {
        LOGE("Couldn't get color of highlight annotation");
        return false;
    }

    Color color(R, G, B, A);
    this->SetColor(color);
    return true;
}

ScopedFPDFAnnotation HighlightAnnotation::CreatePdfiumInstance(FPDF_DOCUMENT document,
                                                               FPDF_PAGE page) {
    ScopedFPDFAnnotation scoped_annot =
            ScopedFPDFAnnotation(FPDFPage_CreateAnnot(page, FPDF_ANNOT_HIGHLIGHT));

    if (!scoped_annot) {
        LOGE("Failed to create highlight Annotation.");
        return nullptr;
    }

    if (!this->UpdatePdfiumInstance(scoped_annot.get(), document, page)) {
        LOGE("Failed to create highlight annotation with given parameters");
    }

    return scoped_annot;
}

bool HighlightAnnotation::UpdatePdfiumInstance(FPDF_ANNOTATION fpdf_annot, FPDF_DOCUMENT document,
                                               FPDF_PAGE page) {
    if (FPDFAnnot_GetSubtype(fpdf_annot) != FPDF_ANNOT_HIGHLIGHT) {
        LOGE("Unsupported operation - can't update a highlight annotation with some other type of "
             "annotation");
        return false;
    }

    auto old_num_bounds = FPDFAnnot_CountAttachmentPoints(fpdf_annot);
    std::vector<Rectangle_f> bounds = GetBounds();
    auto new_num_bounds = bounds.size();

    if (old_num_bounds == new_num_bounds) {
        if (!updateExistingBounds(fpdf_annot, old_num_bounds, bounds)) return false;
    } else if (old_num_bounds < new_num_bounds) {
        if (!updateExistingBounds(fpdf_annot, old_num_bounds, bounds)) return false;
        for (auto bound_index = old_num_bounds; bound_index < new_num_bounds; bound_index++) {
            Rectangle_f rect = bounds[bound_index];
            FS_QUADPOINTSF quad_points = {rect.left, rect.top,    rect.right, rect.top,
                                          rect.left, rect.bottom, rect.right, rect.bottom};
            if (!FPDFAnnot_AppendAttachmentPoints(fpdf_annot, &quad_points)) {
                LOGD("Failed to update bounds of the highlight annotation");
                return false;
            }
        }
    } else {
        if (!updateExistingBounds(fpdf_annot, new_num_bounds, bounds)) return false;
        for (auto bound_index = new_num_bounds; bound_index < old_num_bounds; bound_index++) {
            FS_QUADPOINTSF quad_points = {0, 0, 0, 0, 0, 0, 0, 0};
            if (!FPDFAnnot_SetAttachmentPoints(fpdf_annot, bound_index, &quad_points)) {
                LOGD("Failed to update bounds of the highlight annotation");
                return false;
            }
        }
    }

    Color new_color = this->GetColor();
    if (!FPDFAnnot_SetColor(fpdf_annot, FPDFANNOT_COLORTYPE_Color, new_color.r, new_color.g,
                            new_color.b, new_color.a)) {
        LOGE("Highlight Annotation color couldn't be updated");
        return false;
    }
    return true;
}

bool FreeTextAnnotation::GetTextContentFromPdfium(FPDF_ANNOTATION fpdf_annot,
                                                  unsigned long text_length, std::wstring& text) {
    // Create a buffer of the obtained size to store the text contents.
    ScopedFPDFWChar text_content_buffer = std::make_unique<FPDF_WCHAR[]>(text_length);
    if (!FPDFAnnot_GetStringValue(fpdf_annot, kContents, text_content_buffer.get(), text_length)) {
        return false;
    }

    text = pdfClient_utils::ToWideString(text_content_buffer.get(), text_length);
    return true;
}

bool FreeTextAnnotation::PopulateFromPdfiumInstance(FPDF_ANNOTATION fpdf_annot, FPDF_PAGE page) {
    // Pass a empty buffer to get the length of the text contents.
    unsigned long text_length = FPDFAnnot_GetStringValue(fpdf_annot, kContents, nullptr, 0);
    if (text_length == 0) {
        LOGE("Failed to get contents of FreeText Annotation");
        return false;
    }

    if (!GetTextContentFromPdfium(fpdf_annot, text_length, text_content_)) {
        LOGE("GetTextContentFromPdfium Failed.");
        return false;
    }

    // Get color
    if (!FPDFAnnot_GetColor(fpdf_annot, FPDFANNOT_COLORTYPE_Color, &text_color_.r, &text_color_.g,
                            &text_color_.b, &text_color_.a)) {
        LOGE("Couldn't get text color of freetext annotation");
        return false;
    }

    if (!FPDFAnnot_GetColor(fpdf_annot, FPDFANNOT_COLORTYPE_InteriorColor, &background_color_.r,
                            &background_color_.g, &background_color_.b, &background_color_.a)) {
        LOGE("Couldn't get background color of freetext annotation");
        return false;
    }
    return true;
}

ScopedFPDFAnnotation FreeTextAnnotation::CreatePdfiumInstance(FPDF_DOCUMENT document,
                                                              FPDF_PAGE page) {
    ScopedFPDFAnnotation scoped_annot =
            ScopedFPDFAnnotation(FPDFPage_CreateAnnot(page, FPDF_ANNOT_FREETEXT));

    if (!scoped_annot) {
        LOGE("Failed to create FreeText Annotation");
        return nullptr;
    }

    if (!UpdatePdfiumInstance(scoped_annot.get(), document, page)) {
        LOGE("Failed to create FreeText Annotation with given parameters");
    }

    return scoped_annot;
}

bool FreeTextAnnotation::UpdatePdfiumInstance(FPDF_ANNOTATION fpdf_annot, FPDF_DOCUMENT document,
                                              FPDF_PAGE page) {
    if (FPDFAnnot_GetSubtype(fpdf_annot) != FPDF_ANNOT_FREETEXT) {
        LOGE("Unsupported operation - can't update a freetext annotation with some other type of "
             "annotation");
        return false;
    }

    Rectangle_f annotation_bounds = GetBounds();
    if (!FPDFAnnot_SetRect(fpdf_annot, reinterpret_cast<FS_RECTF*>(&annotation_bounds))) {
        LOGE("FreeText Annotation bounds could not be updated");
        return false;
    }

    auto fpdfWideString = pdfClient_utils::ToFPDFWideString(text_content_);
    if (!FPDFAnnot_SetStringValue(fpdf_annot, kContents, fpdfWideString.get())) {
        LOGE("FreeText Annotation text content could not be updated");
    }

    if (!FPDFAnnot_SetColor(fpdf_annot, FPDFANNOT_COLORTYPE_Color, text_color_.r, text_color_.g,
                            text_color_.b, text_color_.a)) {
        LOGE("FreeText Annotation text color couldn't be updated");
        return false;
    }

    if (!FPDFAnnot_SetColor(fpdf_annot, FPDFANNOT_COLORTYPE_InteriorColor, background_color_.r,
                            background_color_.g, background_color_.b, background_color_.a)) {
        LOGE("FreeText Annotation background color couldn't be updated");
        return false;
    }

    return true;
}

}  // namespace pdfClient