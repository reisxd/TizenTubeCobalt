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

#include "cobalt/layout/replaced_box.h"

#include <algorithm>
#include <memory>

#include "base/bind.h"
#include "base/logging.h"
#include "base/trace_event/trace_event.h"
#include "cobalt/base/polymorphic_downcast.h"
#include "cobalt/cssom/filter_function_list_value.h"
#include "cobalt/cssom/keyword_value.h"
#include "cobalt/cssom/map_to_mesh_function.h"
#include "cobalt/layout/container_box.h"
#include "cobalt/layout/letterboxed_image.h"
#include "cobalt/layout/used_style.h"
#include "cobalt/layout/white_space_processing.h"
#include "cobalt/loader/mesh/mesh_projection.h"
#include "cobalt/math/transform_2d.h"
#include "cobalt/math/vector2d_f.h"
#include "cobalt/render_tree/brush.h"
#include "cobalt/render_tree/color_rgba.h"
#include "cobalt/render_tree/filter_node.h"
#include "cobalt/render_tree/image_node.h"
#include "cobalt/render_tree/lottie_node.h"
#include "cobalt/render_tree/map_to_mesh_filter.h"
#include "cobalt/render_tree/punch_through_video_node.h"
#include "cobalt/render_tree/rect_node.h"
#include "cobalt/render_tree/resource_provider.h"

namespace cobalt {
namespace layout {

using render_tree::CompositionNode;
using render_tree::FilterNode;
using render_tree::ImageNode;
using render_tree::LottieNode;
using render_tree::MapToMeshFilter;
using render_tree::Node;
using render_tree::PunchThroughVideoNode;
using render_tree::RectNode;
using render_tree::SolidColorBrush;
using render_tree::animations::AnimateNode;

namespace {

// Used when intrinsic ratio cannot be determined,
// as per https://www.w3.org/TR/CSS21/visudet.html#inline-replaced-width.
const float kFallbackIntrinsicRatio = 2.0f;

// Becomes a used value of "width" if it cannot be determined by any other
// means, as per https://www.w3.org/TR/CSS21/visudet.html#inline-replaced-width.
const float kFallbackWidth = 300.0f;

const char* kEquirectangularMeshURL =
    "h5vcc-embedded://equirectangular_40_40.msh";

const char* kWarningInvalidMeshUrl =
    "Mesh specification invalid in map-to-mesh filter: "
    "must be either a valid URL or 'equirectangular'.";

const char* kWarningLoadingMeshFailed =
    "Could not load mesh specified by map-to-mesh filter.";

// Convert the parsed keyword value for a stereo mode into a stereo mode enum
// value.
render_tree::StereoMode ReadStereoMode(
    const scoped_refptr<cssom::KeywordValue>& keyword_value) {
  cssom::KeywordValue::Value value = keyword_value->value();

  if (value == cssom::KeywordValue::kMonoscopic) {
    return render_tree::kMono;
  } else if (value == cssom::KeywordValue::kStereoscopicLeftRight) {
    return render_tree::kLeftRight;
  } else if (value == cssom::KeywordValue::kStereoscopicTopBottom) {
    return render_tree::kTopBottom;
  } else {
    LOG(DFATAL) << "Stereo mode has an invalid non-NULL value, defaulting to "
                << "monoscopic";
    return render_tree::kMono;
  }
}

}  // namespace

ReplacedBox::ReplacedBox(
    const scoped_refptr<cssom::CSSComputedStyleDeclaration>&
        css_computed_style_declaration,
    const ReplaceImageCB& replace_image_cb, const SetBoundsCB& set_bounds_cb,
    const scoped_refptr<Paragraph>& paragraph, int32 text_position,
    const base::Optional<LayoutUnit>& maybe_intrinsic_width,
    const base::Optional<LayoutUnit>& maybe_intrinsic_height,
    const base::Optional<float>& maybe_intrinsic_ratio,
    UsedStyleProvider* used_style_provider,
    base::Optional<ReplacedBoxMode> replaced_box_mode,
    const math::SizeF& content_size,
    base::Optional<render_tree::LottieAnimation::LottieProperties>
        lottie_properties,
    bool center_crop,
    LayoutStatTracker* layout_stat_tracker)
    : Box(css_computed_style_declaration, used_style_provider,
          layout_stat_tracker),
      maybe_intrinsic_width_(maybe_intrinsic_width),
      maybe_intrinsic_height_(maybe_intrinsic_height),
      // Like Chromium, we assume that an element must always have an intrinsic
      // ratio, although technically it's a spec violation. For details see
      // https://www.w3.org/TR/CSS21/visudet.html#inline-replaced-width.
      intrinsic_ratio_(maybe_intrinsic_ratio.value_or(kFallbackIntrinsicRatio)),
      replace_image_cb_(replace_image_cb),
      set_bounds_cb_(set_bounds_cb),
      paragraph_(paragraph),
      text_position_(text_position),
      replaced_box_mode_(replaced_box_mode),
      content_size_(content_size),
      lottie_properties_(lottie_properties),
      center_crop_(center_crop) {}

WrapResult ReplacedBox::TryWrapAt(WrapAtPolicy wrap_at_policy,
                                  WrapOpportunityPolicy wrap_opportunity_policy,
                                  bool is_line_existence_justified,
                                  LayoutUnit available_width,
                                  bool should_collapse_trailing_white_space) {
  // NOTE: This logic must stay in sync with
  // InlineLevelBlockContainerBox::TryWrapAt().
  DCHECK(!IsAbsolutelyPositioned());

  // Wrapping is not allowed until the line's existence is justified, meaning
  // that wrapping cannot occur before the box. Given that this box cannot be
  // split, no wrappable point is available.
  if (!is_line_existence_justified) {
    return kWrapResultNoWrap;
  }

  // Atomic inline elements participate in the inline formatting context as a
  // single opaque box. Therefore, the parent's style should be used, as the
  // internals of the atomic inline element have no impact on the formatting of
  // the line.
  // https://www.w3.org/TR/CSS21/visuren.html#inline-boxes
  if (!parent()) {
    return kWrapResultNoWrap;
  }

  bool style_allows_break_word = parent()->computed_style()->overflow_wrap() ==
                                 cssom::KeywordValue::GetBreakWord();

  if (!ShouldProcessWrapOpportunityPolicy(wrap_opportunity_policy,
                                          style_allows_break_word)) {
    return kWrapResultNoWrap;
  }

  // Even when the style prevents wrapping, wrapping can still occur before the
  // box if the line's existence has already been justified and whitespace
  // precedes it.
  if (!DoesAllowTextWrapping(parent()->computed_style()->white_space())) {
    if (text_position_ > 0 &&
        paragraph_->IsCollapsibleWhiteSpace(text_position_ - 1)) {
      return kWrapResultWrapBefore;
    } else {
      return kWrapResultNoWrap;
    }
  }

  Paragraph::BreakPolicy break_policy =
      Paragraph::GetBreakPolicyFromWrapOpportunityPolicy(
          wrap_opportunity_policy, style_allows_break_word);
  return paragraph_->IsBreakPosition(text_position_, break_policy)
             ? kWrapResultWrapBefore
             : kWrapResultNoWrap;
}

void ReplacedBox::SplitBidiLevelRuns() {}

bool ReplacedBox::TrySplitAtSecondBidiLevelRun() { return false; }

base::Optional<int> ReplacedBox::GetBidiLevel() const {
  return paragraph_->GetBidiLevel(text_position_);
}

void ReplacedBox::SetShouldCollapseLeadingWhiteSpace(
    bool should_collapse_leading_white_space) {
  // Do nothing.
}

void ReplacedBox::SetShouldCollapseTrailingWhiteSpace(
    bool should_collapse_trailing_white_space) {
  // Do nothing.
}

bool ReplacedBox::HasLeadingWhiteSpace() const { return false; }

bool ReplacedBox::HasTrailingWhiteSpace() const { return false; }

bool ReplacedBox::IsCollapsed() const { return false; }

bool ReplacedBox::JustifiesLineExistence() const { return true; }

bool ReplacedBox::AffectsBaselineInBlockFormattingContext() const {
  return false;
}

LayoutUnit ReplacedBox::GetBaselineOffsetFromTopMarginEdge() const {
  return GetMarginBoxHeight();
}

namespace {
void AddLetterboxedImageToRenderTree(
    const LetterboxDimensions& dimensions,
    const scoped_refptr<render_tree::Image>& image,
    CompositionNode::Builder* composition_node_builder) {
  if (dimensions.image_rect) {
    ImageNode::Builder image_builder(image, *dimensions.image_rect);
    composition_node_builder->AddChild(new ImageNode(image_builder));
  }
}

void AddLetterboxedPunchThroughVideoNodeToRenderTree(
    const LetterboxDimensions& dimensions,
    const ReplacedBox::SetBoundsCB& set_bounds_cb,
    CompositionNode::Builder* border_node_builder) {
  if (dimensions.image_rect) {
    PunchThroughVideoNode::Builder builder(*(dimensions.image_rect),
                                           set_bounds_cb);
    border_node_builder->AddChild(new PunchThroughVideoNode(builder));
  }
}

void AnimateVideoImage(const ReplacedBox::ReplaceImageCB& replace_image_cb,
                       ImageNode::Builder* image_node_builder) {
  DCHECK(!replace_image_cb.is_null());
  DCHECK(image_node_builder);

  image_node_builder->source = replace_image_cb.Run();
  if (image_node_builder->source) {
    image_node_builder->destination_rect =
        math::RectF(image_node_builder->source->GetSize());
  }
}

// Animates an image, and letterboxes the image as well according to the aspect
// ratio of the resulting animated image versus the aspect ratio of the
// destination box size.
void AnimateVideoWithLetterboxing(
    const ReplacedBox::ReplaceImageCB& replace_image_cb,
    math::SizeF destination_size, bool center_crop,
    CompositionNode::Builder* composition_node_builder) {
  DCHECK(!replace_image_cb.is_null());
  DCHECK(composition_node_builder);

  scoped_refptr<render_tree::Image> image = replace_image_cb.Run();

  // If the image hasn't changed, then no need to change anything else.  The
  // image should be the first child (see AddLetterboxedImageToRenderTree()).
  if (!composition_node_builder->children().empty()) {
    render_tree::ImageNode* existing_image_node =
        base::polymorphic_downcast<render_tree::ImageNode*>(
            composition_node_builder->GetChild(0)->get());
    if (existing_image_node->data().source.get() == image.get()) {
      return;
    }
  }

  // Reset the composition node from whatever it was before, we will recreate
  // it anew in each animation frame.
  *composition_node_builder = CompositionNode::Builder();

  // TODO: Detect better when the intrinsic video size is used for the
  //   node size, and trigger a re-layout from the media element when the size
  //   changes.
  if (image && 0 == destination_size.height()) {
    destination_size = image->GetSize();
  }

  if (image) {
    AddLetterboxedImageToRenderTree(
        GetLetterboxDimensions(image->GetSize(), destination_size, center_crop), image,
        composition_node_builder);
  }
}

void AnimateLottie(
    const ReplacedBox::ReplaceImageCB& replace_image_cb,
    const render_tree::LottieAnimation::LottieProperties& lottie_properties,
    math::RectF destination_rect, LottieNode::Builder* node_builder,
    base::TimeDelta time_elapsed) {
  scoped_refptr<render_tree::Image> animation = replace_image_cb.Run();
  render_tree::LottieAnimation* lottie =
      base::polymorphic_downcast<render_tree::LottieAnimation*>(
          animation.get());
  lottie->BeginRenderFrame(lottie_properties);
  node_builder->animation = lottie;
  node_builder->destination_rect = destination_rect;
  node_builder->animation_time = time_elapsed;
}

}  // namespace

void ReplacedBox::RenderAndAnimateContent(
    CompositionNode::Builder* border_node_builder,
    ContainerBox* stacking_context) const {
  if (computed_style()->visibility() != cssom::KeywordValue::GetVisible()) {
    return;
  }

  if (replace_image_cb_.is_null()) {
    return;
  }

  if (!replaced_box_mode_.has_value()) {
    // Don't render anything, so any background color or image will be visible.
    return;
  }

  if (replaced_box_mode_ == ReplacedBoxMode::kPaintToBlack) {
    // Explicitly render black if we don't have a data stream associated with
    // this video [yet], this is the same as the previous behavior.
    border_node_builder->AddChild(new RectNode(
        math::RectF(content_box_size()),
        std::unique_ptr<render_tree::Brush>(new render_tree::SolidColorBrush(
            render_tree::ColorRGBA(0.0f, 0.0f, 0.0f, 1.0f)))));
    // Nothing to render.
    return;
  }

  if (*replaced_box_mode_ == ReplacedBoxMode::kLottie) {
    AnimateNode::Builder animate_node_builder;
    scoped_refptr<LottieNode> lottie_node =
        new LottieNode(nullptr, math::RectF());
    animate_node_builder.Add(
        lottie_node,
        base::Bind(&AnimateLottie, replace_image_cb_, *lottie_properties_,
                   math::RectF(content_box_size())));
    border_node_builder->AddChild(
        new AnimateNode(animate_node_builder, lottie_node));
    return;
  }

  const cssom::MapToMeshFunction* mtm_filter_function =
      cssom::MapToMeshFunction::ExtractFromFilterList(
          computed_style()->filter());

  // Map-to-mesh is only supported with decode-to-texture videos.
  const bool supports_mtm =
      replaced_box_mode_ &&
      *replaced_box_mode_ == ReplacedBoxMode::kDecodeToTextureVideo;

  if (supports_mtm && mtm_filter_function &&
      mtm_filter_function->mesh_spec().mesh_type() !=
          cssom::MapToMeshFunction::kRectangular) {
    RenderAndAnimateContentWithMapToMesh(border_node_builder,
                                         mtm_filter_function);
  } else {
    RenderAndAnimateContentWithLetterboxing(border_node_builder);
  }
}

void ReplacedBox::UpdateContentSizeAndMargins(
    const LayoutParams& layout_params) {
  base::Optional<LayoutUnit> maybe_width = GetUsedWidthIfNotAuto(
      computed_style(), layout_params.containing_block_size, NULL);
  base::Optional<LayoutUnit> maybe_height = GetUsedHeightIfNotAuto(
      computed_style(), layout_params.containing_block_size, NULL);

  if (layout_params.freeze_width) {
    maybe_width = width();
  }
  if (layout_params.freeze_height) {
    maybe_height = height();
  }

  base::Optional<LayoutUnit> maybe_left = GetUsedLeftIfNotAuto(
      computed_style(), layout_params.containing_block_size);
  base::Optional<LayoutUnit> maybe_top = GetUsedTopIfNotAuto(
      computed_style(), layout_params.containing_block_size);

  if (IsAbsolutelyPositioned()) {
    // TODO: Implement CSS section 10.3.8, see
    // https://www.w3.org/TR/CSS21/visudet.html#abs-replaced-width.
    set_left(maybe_left.value_or(LayoutUnit(GetStaticPositionLeft())));
    set_top(maybe_top.value_or(LayoutUnit(GetStaticPositionTop())));
  }
  // Note that computed height may be "auto", even if it is specified as a
  // percentage (depending on conditions of the containing block). See details
  // in the spec. https://www.w3.org/TR/CSS22/visudet.html#the-height-property
  if (!maybe_height) {
    LOG(ERROR) << "ReplacedBox element has computed height \"auto\"!";
  }
  if (!maybe_width) {
    LOG(ERROR) << "ReplacedBox element has computed width \"auto\"!";
  }
  // In order for Cobalt to handle "auto" dimensions correctly for both punchout
  // and decode-to-texture we need to use the content's intrinsic dimensions &
  // ratio rather than using the content_box_size directly. Until this
  // functionality is found to be useful, we avoid the extra complexity
  // introduced by its implementation.
  if (!maybe_height || !maybe_width) {
    LOG(ERROR)
        << "Cobalt ReplacedBox does not handle \"auto\" dimensions correctly! "
           "\"auto\" dimensions are updated using the intrinsic dimensions of "
           "the content (e.g. video width/height), which is often not what is "
           "intended.";
  }
  if (!maybe_width) {
    if (!maybe_height) {
      if (maybe_intrinsic_width_) {
        // If "height" and "width" both have computed values of "auto" and
        // the element also has an intrinsic width, then that intrinsic width
        // is the used value of "width".
        //   https://www.w3.org/TR/CSS21/visudet.html#inline-replaced-width
        set_width(*maybe_intrinsic_width_);
      } else if (maybe_intrinsic_height_) {
        // If "height" and "width" both have computed values of "auto" and
        // the element has no intrinsic width, but does have an intrinsic height
        // and intrinsic ratio then the used value of "width" is:
        //     (intrinsic height) * (intrinsic ratio)
        //   https://www.w3.org/TR/CSS21/visudet.html#inline-replaced-width
        set_width(*maybe_intrinsic_height_ * intrinsic_ratio_);
      } else {
        // Otherwise, if "width" has a computed value of "auto", but none of
        // the conditions above are met, then the used value of "width" becomes
        // 300px.
        //   https://www.w3.org/TR/CSS21/visudet.html#inline-replaced-width
        set_width(LayoutUnit(kFallbackWidth));
      }
    } else {
      // If "width" has a computed value of "auto", "height" has some other
      // computed value, and the element does have an intrinsic ratio then
      // the used value of "width" is:
      //     (used height) * (intrinsic ratio)
      //   https://www.w3.org/TR/CSS21/visudet.html#inline-replaced-width
      set_width(*maybe_height * intrinsic_ratio_);
    }
  } else {
    set_width(*maybe_width);
  }

  if (!maybe_height) {
    if (!maybe_width && maybe_intrinsic_height_) {
      // If "height" and "width" both have computed values of "auto" and
      // the element also has an intrinsic height, then that intrinsic height
      // is the used value of "height".
      //   https://www.w3.org/TR/CSS21/visudet.html#inline-replaced-height
      set_height(*maybe_intrinsic_height_);
    } else {
      // Otherwise, if "height" has a computed value of "auto", and the element
      // has an intrinsic ratio then the used value of "height" is:
      //     (used width) / (intrinsic ratio)
      //   https://www.w3.org/TR/CSS21/visudet.html#inline-replaced-height
      set_height(width() / intrinsic_ratio_);
    }
  } else {
    set_height(*maybe_height);
  }

  if (!maybe_width && !maybe_height) {
    // For replaced elements with an intrinsic ratio and both 'width' and
    // 'height' specified as 'auto', the algorithm is as described in
    // https://www.w3.org/TR/CSS21/visudet.html#min-max-widths.

    base::Optional<LayoutUnit> maybe_max_width = GetUsedMaxWidthIfNotNone(
        computed_style(), layout_params.containing_block_size, NULL);
    LayoutUnit min_width =
        GetUsedMinWidthIfNotAuto(computed_style(),
                                 layout_params.containing_block_size, NULL)
            .value_or(LayoutUnit());
    base::Optional<LayoutUnit> maybe_max_height = GetUsedMaxHeightIfNotNone(
        computed_style(), layout_params.containing_block_size);
    LayoutUnit min_height =
        GetUsedMinHeightIfNotAuto(computed_style(),
                                  layout_params.containing_block_size)
            .value_or(LayoutUnit());

    // The values w and h stand for the results of the width and height
    // computations ignoring the 'min-width', 'min-height', 'max-width' and
    // 'max-height' properties. Normally these are the intrinsic width and
    // height, but they may not be in the case of replaced elements with
    // intrinsic ratios.
    //   https://www.w3.org/TR/CSS21/visudet.html#min-max-widths
    LayoutUnit w = width();
    LayoutUnit h = height();

    // Take the max-width and max-height as max(min, max) so that min <= max
    // holds true.
    //   https://www.w3.org/TR/CSS21/visudet.html#min-max-widths
    base::Optional<LayoutUnit> max_height;
    bool h_greater_than_max_height = false;
    if (maybe_max_height) {
      max_height = std::max(min_height, *maybe_max_height);
      h_greater_than_max_height = h > *max_height;
    }

    base::Optional<LayoutUnit> max_width;
    bool w_greater_than_max_width = false;
    if (maybe_max_width) {
      max_width = std::max(min_width, *maybe_max_width);
      w_greater_than_max_width = w > *max_width;
    }

    // This block sets resolved width and resolved height values according to
    // the table listing a number of different constraint violations in
    // https://www.w3.org/TR/CSS21/visudet.html#min-max-widths.
    if (w_greater_than_max_width) {
      if (h_greater_than_max_height) {
        LayoutUnit max_width_ratio = *max_width / w.toFloat();
        LayoutUnit max_height_ratio = *max_height / h.toFloat();
        if (max_width_ratio > max_height_ratio) {
          // Constraint: (w > max-width) and (h > max-height), where
          // (max-width/w > max-height/h)
          set_width(
              std::max(min_width, *max_height * (w.toFloat() / h.toFloat())));
          set_height(*max_height);
        } else {
          // Constraint: (w > max-width) and (h > max-height), where
          // (max-width/w <= max-height/h)
          set_width(*max_width);
          set_height(
              std::max(min_height, *max_width * (h.toFloat() / w.toFloat())));
        }
      } else {  // not h_greater_than_max_height
        if (h < min_height) {
          // Constraint: (w > max-width) and (h < min-height)
          set_width(*max_width);
          set_height(min_height);
        } else {  // not h < min_height
          // Constraint: w > max-width
          set_width(*max_width);
          set_height(
              std::max(*max_width * (h.toFloat() / w.toFloat()), min_height));
        }
      }
    } else {  // not w_greater_than_max_width
      if (w < min_width) {
        if (h_greater_than_max_height) {
          // Constraint: (w < min-width) and (h > max-height)
          set_width(min_width);
          set_height(*max_height);
        } else {  // not h_greater_than_max_height
          if (h < min_height) {
            LayoutUnit min_width_ratio = min_width / w.toFloat();
            LayoutUnit min_height_ratio = min_height / h.toFloat();
            if (min_width_ratio > min_height_ratio) {
              // Constraint: (w < min-width) and (h < min-height), where
              // (min-width/w > min-height/h)
              set_width(min_width);
              LayoutUnit height = min_width * (h.toFloat() / w.toFloat());
              if (max_height) {
                set_height(std::min(*max_height, height));
              } else {
                set_height(height);
              }
            } else {
              // Constraint: (w < min-width) and (h < min-height), where
              // (min-width/w <= min-height/h)
              LayoutUnit width = min_height * (w.toFloat() / h.toFloat());
              if (max_width) {
                set_width(std::min(*max_width, width));
              } else {
                set_width(width);
              }
              set_height(min_height);
            }
          } else {  // not h < min-height
            // Constraint: w < min-width
            set_width(min_width);
            LayoutUnit height = min_width * (h.toFloat() / w.toFloat());
            if (max_height) {
              set_height(std::min(height, *max_height));
            } else {
              set_height(height);
            }
          }
        }
      } else {  // not w < min_width
        if (h_greater_than_max_height) {
          // Constraint: h > max-height
          set_width(
              std::max(*max_height * (w.toFloat() / h.toFloat()), min_width));
          set_height(*max_height);
        } else {  // not h_greater_than_max_height
          if (h < min_height) {
            // Constraint: h < min-height
            LayoutUnit width = min_height * (w.toFloat() / h.toFloat());
            if (max_width) {
              set_width(std::min(width, *max_width));
            } else {
              set_width(width);
            }
            set_height(min_height);
          } else {  // not h < min_height
            // Constraint: none
            // Do nothing (keep w and h).
          }
        }
      }
    }
  }

  // The horizontal margin rules are difference for block level replaced boxes
  // versus inline level replaced boxes.
  //   https://www.w3.org/TR/CSS21/visudet.html#inline-replaced-width
  //   https://www.w3.org/TR/CSS21/visudet.html#block-replaced-width
  base::Optional<LayoutUnit> maybe_margin_left = GetUsedMarginLeftIfNotAuto(
      computed_style(), layout_params.containing_block_size);
  base::Optional<LayoutUnit> maybe_margin_right = GetUsedMarginRightIfNotAuto(
      computed_style(), layout_params.containing_block_size);
  LayoutUnit border_box_width = GetBorderBoxWidth();
  UpdateHorizontalMargins(layout_params.containing_block_direction,
                          layout_params.containing_block_size.width(),
                          border_box_width, maybe_margin_left,
                          maybe_margin_right);

  base::Optional<LayoutUnit> maybe_margin_top = GetUsedMarginTopIfNotAuto(
      computed_style(), layout_params.containing_block_size);
  base::Optional<LayoutUnit> maybe_margin_bottom = GetUsedMarginBottomIfNotAuto(
      computed_style(), layout_params.containing_block_size);

  // If "margin-top", or "margin-bottom" are "auto", their used value is 0.
  //   https://www.w3.org/TR/CSS21/visudet.html#inline-replaced-height
  set_margin_top(maybe_margin_top.value_or(LayoutUnit()));
  set_margin_bottom(maybe_margin_bottom.value_or(LayoutUnit()));
}

#ifdef ENABLE_DEBUGGER

void ReplacedBox::DumpProperties(std::ostream* stream) const {
  Box::DumpProperties(stream);

  *stream << "text_position=" << text_position_ << " "
          << "bidi_level=" << paragraph_->GetBidiLevel(text_position_) << " ";
}

#endif  // ENABLE_DEBUGGER

void ReplacedBox::RenderAndAnimateContentWithMapToMesh(
    CompositionNode::Builder* border_node_builder,
    const cssom::MapToMeshFunction* mtm_function) const {
  // First setup the animated image node.
  AnimateNode::Builder animate_node_builder;
  scoped_refptr<ImageNode> image_node = new ImageNode(nullptr);
  animate_node_builder.Add(image_node,
                           base::Bind(&AnimateVideoImage, replace_image_cb_));
  scoped_refptr<AnimateNode> animate_node =
      new AnimateNode(animate_node_builder, image_node);

  // Then wrap the animated image into a MapToMeshFilter render tree node.
  const cssom::MapToMeshFunction::MeshSpec& spec = mtm_function->mesh_spec();
  const scoped_refptr<cssom::KeywordValue>& stereo_mode_keyword_value =
      mtm_function->stereo_mode();
  render_tree::StereoMode stereo_mode =
      ReadStereoMode(stereo_mode_keyword_value);

  scoped_refptr<render_tree::Node> filter_node;
  // Fetch either the embedded equirectangular mesh or a custom one depending
  // on the spec.
  MapToMeshFilter::Builder builder;
  if (spec.mesh_type() == cssom::MapToMeshFunction::kUrls) {
    // Custom mesh URLs.
    // Set a default mesh (in case no resolution-specific mesh matches).
    cssom::URLValue* default_url_value =
        base::polymorphic_downcast<cssom::URLValue*>(spec.mesh_url().get());
    GURL default_url(default_url_value->value());

    if (!default_url.is_valid()) {
      DLOG(WARNING) << kWarningInvalidMeshUrl;
      return;
    }

    scoped_refptr<loader::mesh::MeshProjection> default_mesh_projection(
        used_style_provider()->ResolveURLToMeshProjection(default_url));

    if (!default_mesh_projection) {
      DLOG(WARNING) << kWarningLoadingMeshFailed;
      return;
    }

    builder.SetDefaultMeshes(
        default_mesh_projection->GetMesh(
            loader::mesh::MeshProjection::kLeftEyeOrMonoCollection),
        default_mesh_projection->GetMesh(
            loader::mesh::MeshProjection::kRightEyeCollection));

    // Lookup among the list of resolutions for a match and use that mesh URL.
    const cssom::MapToMeshFunction::ResolutionMatchedMeshListBuilder& meshes =
        spec.resolution_matched_meshes();
    for (size_t i = 0; i < meshes.size(); i++) {
      cssom::URLValue* url_value = base::polymorphic_downcast<cssom::URLValue*>(
          meshes[i]->mesh_url().get());
      GURL url(url_value->value());

      if (!url.is_valid()) {
        DLOG(WARNING) << kWarningInvalidMeshUrl;
        return;
      }
      scoped_refptr<loader::mesh::MeshProjection> mesh_projection(
          used_style_provider()->ResolveURLToMeshProjection(url));

      if (!mesh_projection) {
        DLOG(WARNING) << kWarningLoadingMeshFailed;
      }

      TRACE_EVENT2("cobalt::layout",
                   "ReplacedBox::RenderAndAnimateContentWithMapToMesh()",
                   "height", meshes[i]->height_match(), "crc",
                   mesh_projection->crc().value_or(-1));

      builder.AddResolutionMatchedMeshes(
          math::Size(meshes[i]->width_match(), meshes[i]->height_match()),
          mesh_projection->GetMesh(
              loader::mesh::MeshProjection::kLeftEyeOrMonoCollection),
          mesh_projection->GetMesh(
              loader::mesh::MeshProjection::kRightEyeCollection));
    }
  } else if (spec.mesh_type() == cssom::MapToMeshFunction::kEquirectangular) {
    GURL url(kEquirectangularMeshURL);
    scoped_refptr<loader::mesh::MeshProjection> mesh_projection(
        used_style_provider()->ResolveURLToMeshProjection(url));

    if (!mesh_projection) {
      DLOG(WARNING) << kWarningLoadingMeshFailed;
    }

    builder.SetDefaultMeshes(
        mesh_projection->GetMesh(
            loader::mesh::MeshProjection::kLeftEyeOrMonoCollection),
        mesh_projection->GetMesh(
            loader::mesh::MeshProjection::kRightEyeCollection));
  }

  filter_node =
      new FilterNode(MapToMeshFilter(stereo_mode, builder), animate_node);

  // Attach a 3D camera to the map-to-mesh node, so the rendering of its
  // content can be transformed.
  border_node_builder->AddChild(
      used_style_provider()->attach_camera_node_function().Run(
          filter_node, mtm_function->horizontal_fov_in_radians(),
          mtm_function->vertical_fov_in_radians()));
}

void ReplacedBox::RenderAndAnimateContentWithLetterboxing(
    CompositionNode::Builder* border_node_builder) const {
  CompositionNode::Builder composition_node_builder(
      math::Vector2dF((border_left_width() + padding_left()).toFloat(),
                      (border_top_width() + padding_top()).toFloat()));
  scoped_refptr<CompositionNode> composition_node =
      new CompositionNode(composition_node_builder);

  if (*replaced_box_mode_ == ReplacedBoxMode::kPunchOutVideo) {
    LetterboxDimensions letterbox_dims =
        GetLetterboxDimensions(content_size_, content_box_size(), center_crop_);
    AddLetterboxedPunchThroughVideoNodeToRenderTree(
        letterbox_dims, set_bounds_cb_, border_node_builder);

  } else {
    AnimateNode::Builder animate_node_builder;
    animate_node_builder.Add(composition_node,
                             base::Bind(&AnimateVideoWithLetterboxing,
                                        replace_image_cb_, content_box_size(), center_crop_));
    border_node_builder->AddChild(
        new AnimateNode(animate_node_builder, composition_node));
  }
}

}  // namespace layout
}  // namespace cobalt
