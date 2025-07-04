// Copyright 2019 The Cobalt Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Module Overview: Starboard String module C++ convenience layer
//
// Implements convenience functions to simplify formatting strings.

#ifndef STARBOARD_COMMON_STRING_H_
#define STARBOARD_COMMON_STRING_H_

#include <stdarg.h>
#if SB_API_VERSION >= 16
#include <stdio.h>
#endif
#include <cstring>
#include <string>
#include <vector>

#include "starboard/configuration.h"
#include "starboard/string.h"

namespace starboard {

inline std::string FormatString(const char* format, ...) SB_PRINTF_FORMAT(1, 2);

inline std::string FormatString(const char* format, ...) {
  va_list arguments;
  va_start(arguments, format);
  int expected_size = vsnprintf(NULL, 0, format, arguments);
  va_end(arguments);

  std::string result;
  if (expected_size <= 0) {
    return result;
  }

  std::vector<char> buffer(expected_size + 1);
  va_start(arguments, format);
  vsnprintf(buffer.data(), buffer.size(), format, arguments);
  va_end(arguments);
  return std::string(buffer.data(), expected_size);
}

inline std::string HexEncode(const void* data,
                             int size,
                             const char* delimiter = NULL) {
  const char kDecToHex[] = "0123456789abcdef";

  std::string result;
  auto delimiter_size = delimiter ? std::strlen(delimiter) : 0;
  result.reserve((delimiter_size + 2) * size);

  const uint8_t* data_in_uint8 = static_cast<const uint8_t*>(data);

  for (int i = 0; i < size; ++i) {
    result += kDecToHex[data_in_uint8[i] / 16];
    result += kDecToHex[data_in_uint8[i] % 16];
    if (i != size - 1 && delimiter != nullptr) {
      result += delimiter;
    }
  }

  return result;
}

template <typename CHAR>
static SB_C_FORCE_INLINE int strlcpy(CHAR* dst, const CHAR* src, int dst_size) {
  for (int i = 0; i < dst_size; ++i) {
    if ((dst[i] = src[i]) == 0)  // We hit and copied the terminating NULL.
      return i;
  }

  // We were left off at dst_size.  We over copied 1 byte.  Null terminate.
  if (dst_size != 0)
    dst[dst_size - 1] = 0;

  // Count the rest of the |src|, and return its length in characters.
  while (src[dst_size])
    ++dst_size;
  return dst_size;
}

template <typename CHAR>
static SB_C_FORCE_INLINE int strlcat(CHAR* dst, const CHAR* src, int dst_size) {
  int dst_length = 0;
  for (; dst_length < dst_size; ++dst_length) {
    if (dst[dst_length] == 0)
      break;
  }

  return strlcpy<CHAR>(dst + dst_length, src, dst_size - dst_length) +
         dst_length;
}

// Splits a string on a char delimiter.
inline std::vector<std::string> SplitString(const std::string& input,
                                            char delimiter) {
  std::vector<std::string> output;
  if (input.empty()) {
    return output;
  }

  size_t start = 0;
  while (start != std::string::npos) {
    size_t end = input.find_first_of(delimiter, start);
    std::string piece;

    if (end == std::string::npos) {
      piece = input.substr(start);
      start = std::string::npos;
    } else {
      piece = input.substr(start, end - start);
      start = end + 1;
    }

    output.emplace_back(piece);
  }

  return output;
}

}  // namespace starboard

#endif  // STARBOARD_COMMON_STRING_H_
