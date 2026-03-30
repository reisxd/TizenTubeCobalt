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

#include "cobalt/layout/letterboxed_image.h"

#include "cobalt/base/polymorphic_downcast.h"
#include "cobalt/math/size.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace cobalt {
namespace layout {
namespace {

using math::PointF;
using math::Size;
using math::SizeF;

// When the area of the image is 0, the destination will be fill with the given
// color.
TEST(LetterboxedImageTest, ZeroAreaImage) {
  const SizeF kDestinationSize(300, 200);

  LetterboxDimensions dimensions =
      GetLetterboxDimensions(Size(0, 1), kDestinationSize);
  ASSERT_EQ(1, dimensions.fill_rects.size());
  EXPECT_FALSE(dimensions.image_rect);

  EXPECT_EQ(PointF(0, 0), dimensions.fill_rects[0].origin());
  EXPECT_EQ(kDestinationSize, dimensions.fill_rects[0].size());
}

// When the aspect ratio of the image is the same as the destination, it will be
// stretched to cover the whole destination and no color bands will be drawn.
TEST(LetterboxedImageTest, ImageAtRightAspectRatio) {
  const SizeF kDestinationSize(300, 200);

  for (int i = 0; i < 10; ++i) {
    LetterboxDimensions dimensions = GetLetterboxDimensions(
        Size(3 * (1 << i), 2 * (1 << i)), kDestinationSize);

    EXPECT_TRUE(dimensions.fill_rects.empty());
    ASSERT_TRUE(dimensions.image_rect);

    EXPECT_EQ(PointF(0, 0), dimensions.image_rect->origin());
    EXPECT_EQ(kDestinationSize, dimensions.image_rect->size());
  }
}

// When the aspect ratio of the image is wider than the destination, blanks will
// be added to the top and bottom as extra RectNodes.
TEST(LetterboxedImageTest, WiderImage) {
  const SizeF kDestinationSize(300, 200);

  for (int i = 0; i < 10; ++i) {
    LetterboxDimensions dimensions = GetLetterboxDimensions(
        Size(5 * (1 << i), 3 * (1 << i)), kDestinationSize);

    ASSERT_EQ(2, dimensions.fill_rects.size());
    ASSERT_TRUE(dimensions.image_rect);

    EXPECT_EQ(PointF(0, 10), dimensions.image_rect->origin());
    EXPECT_EQ(kDestinationSize.width(), dimensions.image_rect->width());
    EXPECT_EQ(180, dimensions.image_rect->height());

    EXPECT_EQ(PointF(0, 0), dimensions.fill_rects[0].origin());
    EXPECT_EQ(kDestinationSize.width(), dimensions.fill_rects[0].width());
    EXPECT_EQ(10, dimensions.fill_rects[0].height());

    EXPECT_EQ(PointF(0, 190), dimensions.fill_rects[1].origin());
    EXPECT_EQ(kDestinationSize.width(), dimensions.fill_rects[1].width());
    EXPECT_EQ(10, dimensions.fill_rects[1].height());
  }
}

// When the aspect ratio of the image is higher than the destination, blanks
// will be added to the left and right as extra RectNodes.
TEST(LetterboxedImageTest, HigherImage) {
  const SizeF kDestinationSize(300, 200);

  for (int i = 0; i < 10; ++i) {
    LetterboxDimensions dimensions = GetLetterboxDimensions(
        Size(4 * (1 << i), 5 * (1 << i)), kDestinationSize);

    ASSERT_EQ(2, dimensions.fill_rects.size());
    ASSERT_TRUE(dimensions.image_rect);

    EXPECT_EQ(PointF(70, 0), dimensions.image_rect->origin());
    EXPECT_EQ(160, dimensions.image_rect->width());
    EXPECT_EQ(kDestinationSize.height(), dimensions.image_rect->height());

    EXPECT_EQ(PointF(0, 0), dimensions.fill_rects[0].origin());
    EXPECT_EQ(70, dimensions.fill_rects[0].width());
    EXPECT_EQ(kDestinationSize.height(), dimensions.fill_rects[0].height());

    EXPECT_EQ(PointF(230, 0), dimensions.fill_rects[1].origin());
    EXPECT_EQ(70, dimensions.fill_rects[1].width());
    EXPECT_EQ(kDestinationSize.height(), dimensions.fill_rects[1].height());
  }
}

}  // namespace
TEST(LetterboxedImageTest, WiderImageCenterCrop) {
  const SizeF kDestinationSize(300, 200);

  for (int i = 0; i < 10; ++i) {
    LetterboxDimensions dimensions = GetLetterboxDimensions(
        Size(5 * (1 << i), 3 * (1 << i)), kDestinationSize, true);

    EXPECT_TRUE(dimensions.fill_rects.empty());
    ASSERT_TRUE(dimensions.image_rect);

    EXPECT_NEAR(200.0f * (5.0f/3.0f), dimensions.image_rect->width(), 1.0f);
    EXPECT_NEAR(200.0f, dimensions.image_rect->height(), 1.0f);

    EXPECT_LT(dimensions.image_rect->x(), 0.0f);
    EXPECT_EQ(0.0f, dimensions.image_rect->y());
  }
}

TEST(LetterboxedImageTest, HigherImageCenterCrop) {
  const SizeF kDestinationSize(300, 200);

  for (int i = 0; i < 10; ++i) {
    LetterboxDimensions dimensions = GetLetterboxDimensions(
        Size(4 * (1 << i), 5 * (1 << i)), kDestinationSize, true);

    EXPECT_TRUE(dimensions.fill_rects.empty());
    ASSERT_TRUE(dimensions.image_rect);

    EXPECT_NEAR(300.0f, dimensions.image_rect->width(), 1.0f);
    EXPECT_NEAR(300.0f * (5.0f/4.0f), dimensions.image_rect->height(), 1.0f);

    EXPECT_EQ(0.0f, dimensions.image_rect->x());
    EXPECT_LT(dimensions.image_rect->y(), 0.0f);
  }
}

}  // namespace layout

}  // namespace cobalt
