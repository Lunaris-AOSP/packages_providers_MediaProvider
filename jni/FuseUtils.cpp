// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#define LOG_TAG "FuseUtils"

#include "include/libfuse_jni/FuseUtils.h"

#include <regex>
#include <string>
#include <vector>
#include <unicode/utext.h>

#include "android-base/logging.h"
#include "android-base/strings.h"

using std::string;

namespace mediaprovider {
namespace fuse {

bool containsMount(const string& path) {
    // This method is called from lookup, so it's called rather frequently.
    // Hence, we avoid concatenating the strings and we use 3 separate suffixes.

    static const string prefix = "/storage/emulated/";
    if (!android::base::StartsWithIgnoreCase(path, prefix)) {
        return false;
    }

    size_t pos = path.find_first_of('/', prefix.length());
    if (pos == std::string::npos) {
        return false;
    }

    const string& path_suffix = path.substr(pos);

    static const string android_suffix = "/Android";
    static const string data_suffix = "/Android/data";
    static const string obb_suffix = "/Android/obb";

    return android::base::EqualsIgnoreCase(path_suffix, android_suffix) ||
           android::base::EqualsIgnoreCase(path_suffix, data_suffix) ||
           android::base::EqualsIgnoreCase(path_suffix, obb_suffix);
}

string getVolumeNameFromPath(const std::string& path) {
    std::string volume_name = "";
    if (!android::base::StartsWith(path, STORAGE_PREFIX)) {
        volume_name = VOLUME_INTERNAL;
    } else if (android::base::StartsWith(path, PRIMARY_VOLUME_PREFIX) || path == STORAGE_PREFIX) {
        volume_name = VOLUME_EXTERNAL_PRIMARY;
    } else {
        // Use regex to extract volume name
        std::regex volumeRegex(R"(/storage/([a-zA-Z0-9-]+)/)");
        std::smatch match;
        if (std::regex_search(path, match, volumeRegex)) {
            volume_name = match[1].str();
            // Convert to lowercase
            std::transform(volume_name.begin(), volume_name.end(), volume_name.begin(), ::tolower);
        }
    }
    return volume_name;
}

std::string removeDefaultIgnorableCodepoints(const std::string_view& str) {
    // These libicu unicode methods require SDK 31 or above. Otherwise, we return an empty string.
    if (__builtin_available(android 31, *)) {
        UErrorCode error_code = U_ZERO_ERROR;
        UText *ut = utext_openUTF8(nullptr,
                                   str.data(),
                                   (int64_t) str.length(),
                                   &error_code);
        if (ut == nullptr || U_FAILURE(error_code)) {
            LOG(WARNING) << "Could not decode string as UTF-8: error " << error_code << ": " << str;
            return "";
        }

        std::string out;
        // Arbitrary +8 for some extra room.
        out.reserve(str.length() + 8);
        for (UChar32 c = utext_next32From(ut, 0);
             c >= 0;
             c = utext_next32(ut)) {
            if (!u_hasBinaryProperty(c, UProperty::UCHAR_DEFAULT_IGNORABLE_CODE_POINT)) {
                char utf8[U8_MAX_LENGTH];
                int size = 0;
                U8_APPEND_UNSAFE(utf8, size, c);
                out.append(utf8, size);
            }
        }
        utext_close(ut);
        return out;
    }
    return "";
}

}  // namespace fuse
}  // namespace mediaprovider
