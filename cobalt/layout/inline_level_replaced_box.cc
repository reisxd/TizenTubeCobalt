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

#include "cobalt/layout/inline_level_replaced_box.h"

#include "cobalt/layout/used_style.h"
#include "cobalt/math/size_f.h"

namespace cobalt {
namespace layout {

InlineLevelReplacedBox::InlineLevelReplacedBox(
    const scoped_refptr<cssom::CSSComputedStyleDeclaration>&
        css_computed_style_declaration,
    const ReplaceImageCB& replace_image_cb, const SetBoundsCB& set_bounds_cb,
    const scoped_refptr<Paragraph>& paragraph, int32 text_position,
    const base::Optional<LayoutUnit>& maybe_intrinsic_width,
    const base::Optional<LayoutUnit>& maybe_intrinsic_height,
    const base::Optional<float>& maybe_intrinsic_ratio,
    UsedStyleProvider* used_style_provider,
    base::Optional<ReplacedBox::ReplacedBoxMode> replaced_box_mode,
    const math::SizeF& content_size,
    base::Optional<render_tree::LottieAnimation::LottieProperties>
        lottie_properties,
    bool center_crop,
    LayoutStatTracker* layout_stat_tracker)
    : ReplacedBox(css_computed_style_declaration, replace_image_cb,
                  set_bounds_cb, paragraph, text_position,
                  maybe_intrinsic_width, maybe_intrinsic_height,
                  maybe_intrinsic_ratio, used_style_provider, replaced_box_mode,
                  content_size, lottie_properties, center_crop, layout_stat_tracker),
      is_hidden_by_ellipsis_(false),
      was_hidden_by_ellipsis_(false) {}

Box::Level InlineLevelReplacedBox::GetLevel() const { return kInlineLevel; }

bool InlineLevelReplacedBox::DoesFulfillEllipsisPlacementRequirement() const {
  // This box fulfills the requirement that the first character or inline-level
  // element must appear on the line before ellipsing can occur
  // (https://www.w3.org/TR/css3-ui/#propdef-text-overflow).
  return true;
}

void InlineLevelReplacedBox::DoPreEllipsisPlacementProcessing() {
  was_hidden_by_ellipsis_ = is_hidden_by_ellipsis_;
  is_hidden_by_ellipsis_ = false;
}

void InlineLevelReplacedBox::DoPostEllipsisPlacementProcessing() {
  if (was_hidden_by_ellipsis_ != is_hidden_by_ellipsis_) {
    InvalidateRenderTreeNodesOfBoxAndAncestors();
  }
}

bool InlineLevelReplacedBox::IsHiddenByEllipsis() const {
  return is_hidden_by_ellipsis_;
}

void InlineLevelReplacedBox::UpdateHorizontalMargins(
    BaseDirection containing_block_direction, LayoutUnit containing_block_width,
    LayoutUnit border_box_width,
    const base::Optional<LayoutUnit>& maybe_margin_left,
    const base::Optional<LayoutUnit>& maybe_margin_right) {
  // A computed value of "auto" for "margin-left" or "margin-right" becomes
  // a used value of "0".
  //   https://www.w3.org/TR/CSS21/visudet.html#inline-replaced-width
  set_margin_left(maybe_margin_left.value_or(LayoutUnit()));
  set_margin_right(maybe_margin_right.value_or(LayoutUnit()));
}

#ifdef ENABLE_DEBUGGER

void InlineLevelReplacedBox::DumpClassName(std::ostream* stream) const {
  *stream << "InlineLevelReplacedBox ";
}

#endif  // ENABLE_DEBUGGER

void InlineLevelReplacedBox::DoPlaceEllipsisOrProcessPlacedEllipsis(
    BaseDirection base_direction, LayoutUnit desired_offset,
    bool* is_placement_requirement_met, bool* is_placed,
    LayoutUnit* placed_offset) {
  // If the ellipsis is already placed, then simply mark the box as hidden by
  // the ellipsis: "Implementations must hide characters and atomic inline-level
  // elements at the applicable edge(s) of the line as necessary to fit the
  // ellipsis."
  //   https://www.w3.org/TR/css3-ui/#propdef-text-overflow
  if (*is_placed) {
    is_hidden_by_ellipsis_ = true;
    // Otherwise, the box is placing the ellipsis.
  } else {
    *is_placed = true;

    // The first character or atomic inline-level element on a line must be
    // clipped rather than ellipsed.
    //   https://www.w3.org/TR/css3-ui/#propdef-text-overflow
    // If this requirement has been met, then place the ellipsis at the start
    // edge of atomic inline-level element, as it should be fully hidden.
    if (*is_placement_requirement_met) {
      *placed_offset =
          GetMarginBoxStartEdgeOffsetFromContainingBlock(base_direction);
      is_hidden_by_ellipsis_ = true;
      // Otherwise, this box is fulfilling the required first inline-level
      // element and the ellipsis must be added at the end edge.
    } else {
      *placed_offset =
          GetMarginBoxEndEdgeOffsetFromContainingBlock(base_direction);
    }
  }
}

}  // namespace layout
}  // namespace cobalt
