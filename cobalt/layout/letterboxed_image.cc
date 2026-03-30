// Copyright 2014 The Cobalt Authors. All Rights Reserved.
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

#include "cobalt/layout/letterboxed_image.h"

#include <cmath>

#include "cobalt/math/point_f.h"
#include "cobalt/math/rect_f.h"
#include "cobalt/math/size_f.h"

namespace cobalt {
namespace layout {

using math::RectF;
using math::SizeF;

LetterboxDimensions GetLetterboxDimensions(
    const math::SizeF& image_size, const math::SizeF& destination_size,
    bool center_crop) {
  const float kEpsilon = 0.01f;

  LetterboxDimensions dimensions;

  if (destination_size.GetArea() == 0) {
    DLOG(WARNING) << "destination_size " << destination_size << " is empty";
    return dimensions;
  }

  if (image_size.GetArea() == 0) {
    dimensions.fill_rects.push_back(RectF(destination_size));
    return dimensions;
  }

  float image_aspect_ratio = image_size.width() / image_size.height();
  float destination_aspect_ratio =
      destination_size.width() / destination_size.height();

  if (fabsf(image_aspect_ratio - destination_aspect_ratio) < kEpsilon) {
    // The aspect ratio of the Image are the same as the destination.
    dimensions.image_rect = math::RectF(destination_size);
  } else if (image_aspect_ratio > destination_aspect_ratio) {
    if (center_crop) {
      // Image is wider, scale to fit height and crop sides.
      SizeF adjusted_size(destination_size.height() * image_aspect_ratio,
                          destination_size.height());
      float crop_width = (adjusted_size.width() - destination_size.width()) / 2;
      dimensions.image_rect =
          math::RectF(math::PointF(-crop_width, 0), adjusted_size);
    } else {
      // Image is wider.  We have to put bands on top and bottom.
      SizeF adjusted_size(destination_size.width(),
                          destination_size.width() / image_aspect_ratio);
      float band_height =
          (destination_size.height() - adjusted_size.height()) / 2;

      dimensions.image_rect =
          math::RectF(math::PointF(0, band_height), adjusted_size);
      dimensions.fill_rects.push_back(
          RectF(0, 0, destination_size.width(), band_height));
      dimensions.fill_rects.push_back(
          RectF(0, destination_size.height() - band_height,
                destination_size.width(), band_height));
    }
  } else {
    if (center_crop) {
      // Image is higher, scale to fit width and crop top/bottom.
      SizeF adjusted_size(destination_size.width(),
                          destination_size.width() / image_aspect_ratio);
      float crop_height = (adjusted_size.height() - destination_size.height()) / 2;
      dimensions.image_rect =
          math::RectF(math::PointF(0, -crop_height), adjusted_size);
    } else {
      // Image is higher.  We have to put bands on left and right.
      SizeF adjusted_size(destination_size.height() * image_aspect_ratio,
                          destination_size.height());
      float band_width = (destination_size.width() - adjusted_size.width()) / 2;

      dimensions.image_rect =
          math::RectF(math::PointF(band_width, 0), adjusted_size);
      dimensions.fill_rects.push_back(
          RectF(0, 0, band_width, destination_size.height()));
      dimensions.fill_rects.push_back(RectF(destination_size.width() - band_width,
                                            0, band_width,
                                            destination_size.height()));
    }
  }

  return dimensions;
}

}  // namespace layout
}  // namespace cobalt
