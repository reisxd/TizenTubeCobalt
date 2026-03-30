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

#ifndef COBALT_LAYOUT_INLINE_LEVEL_REPLACED_BOX_H_
#define COBALT_LAYOUT_INLINE_LEVEL_REPLACED_BOX_H_

#include "base/basictypes.h"
#include "base/memory/ref_counted.h"
#include "base/optional.h"
#include "cobalt/cssom/css_declared_style_data.h"
#include "cobalt/cssom/css_transition_set.h"
#include "cobalt/layout/box.h"
#include "cobalt/layout/paragraph.h"
#include "cobalt/layout/replaced_box.h"
#include "cobalt/math/size_f.h"

namespace cobalt {
namespace layout {

class UsedStyleProvider;

class InlineLevelReplacedBox : public ReplacedBox {
 public:
  InlineLevelReplacedBox(
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
      LayoutStatTracker* layout_stat_tracker);

  // From |Box|.
  Level GetLevel() const override;

  bool DoesFulfillEllipsisPlacementRequirement() const override;
  void DoPreEllipsisPlacementProcessing() override;
  void DoPostEllipsisPlacementProcessing() override;
  bool IsHiddenByEllipsis() const override;

 protected:
  // From |Box|.
#ifdef ENABLE_DEBUGGER
  void DumpClassName(std::ostream* stream) const override;
#endif  // ENABLE_DEBUGGER

  // From |ReplacedBox|.
  void UpdateHorizontalMargins(
      BaseDirection containing_block_direction,
      LayoutUnit containing_block_width, LayoutUnit border_box_width,
      const base::Optional<LayoutUnit>& maybe_margin_left,
      const base::Optional<LayoutUnit>& maybe_margin_right) override;

 private:
  // From |Box|.
  void DoPlaceEllipsisOrProcessPlacedEllipsis(
      BaseDirection base_direction, LayoutUnit desired_offset,
      bool* is_placement_requirement_met, bool* is_placed,
      LayoutUnit* placed_offset) override;

  // This flag indicates that the box is fully hidden by the ellipsis and it,
  // along with its contents will not be visible.
  // "Implementations must hide characters and atomic inline-level elements at
  // the applicable edge(s) of the line as necessary to fit the ellipsis."
  //   https://www.w3.org/TR/css3-ui/#propdef-text-overflow
  bool is_hidden_by_ellipsis_;
  // Tracking of the previous value of |is_hidden_by_ellipsis_|, which allows
  // for determination of whether or not the value changed during ellipsis
  // placement. When this occurs, the cached render tree nodes of this box and
  // its ancestors are invalidated.
  bool was_hidden_by_ellipsis_;
};

}  // namespace layout
}  // namespace cobalt

#endif  // COBALT_LAYOUT_INLINE_LEVEL_REPLACED_BOX_H_
