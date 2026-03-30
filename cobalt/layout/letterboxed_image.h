// Copyright 2015 The Cobalt Authors. All Rights Reserved.
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

#ifndef COBALT_LAYOUT_LETTERBOXED_IMAGE_H_
#define COBALT_LAYOUT_LETTERBOXED_IMAGE_H_

#include <vector>

#include "base/optional.h"
#include "cobalt/math/rect_f.h"
#include "cobalt/math/size_f.h"

namespace cobalt {
namespace layout {

struct LetterboxDimensions {
  // The destination dimensions of the image such that its aspect ratio is
  // maintained.
  base::Optional<math::RectF> image_rect;

  // A set of filling rectangles that pad the image.
  std::vector<math::RectF> fill_rects;
};

// This helper function will compute the dimensions of an image and filling
// rectangles such that the image appears at the correct aspect ratio within
// a letterboxed destination rectangle.  This function will return the
// dimensions to use for an image and all filling rectangles (which will
// presumably be filled as solid black) in order to create the letterbox effect.
// It does the above by the following steps:
// 2. If the area of the Image is 0, this function will return a single filling
//    rectangle covering the entire |destination_size|.
// 3. If the aspect ratio of the image is the same as |destination_size|, the
//    image will be stretched to fill the whole destination.
// 4. If the image is wider than the destination, it will be stretched to ensure
//    that its width is the same as the width of the destination.  The image
//    will be centered vertically and two filling rectangles placed on the top
//    and bottom to fill the blank.
// 5. Otherwise, the image is higher than the destination.  it will be stretched
//    to ensure that its height is the same as the height of the destination.
//    The image will be centered horizontally and two filling rectangles placed
//    on the left and right to fill the blank.
LetterboxDimensions GetLetterboxDimensions(const math::SizeF& image_size,
                                           const math::SizeF& destination_size,
                                           bool center_crop = false);

}  // namespace layout
}  // namespace cobalt

#endif  // COBALT_LAYOUT_LETTERBOXED_IMAGE_H_
