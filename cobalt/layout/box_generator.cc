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

#include "cobalt/layout/box_generator.h"

#include <memory>
#include <string>
#include <utility>

#include "base/bind.h"
#include "base/trace_event/trace_event.h"
#include "cobalt/cssom/computed_style.h"
#include "cobalt/cssom/css_computed_style_declaration.h"
#include "cobalt/cssom/css_transition_set.h"
#include "cobalt/cssom/keyword_value.h"
#include "cobalt/cssom/property_definitions.h"
#include "cobalt/cssom/property_value_visitor.h"
#include "cobalt/cssom/url_value.h"
#include "cobalt/dom/html_br_element.h"
#include "cobalt/dom/html_element.h"
#include "cobalt/dom/html_video_element.h"
#include "cobalt/dom/lottie_player.h"
#include "cobalt/dom/text.h"
#include "cobalt/layout/base_direction.h"
#include "cobalt/layout/block_formatting_block_container_box.h"
#include "cobalt/layout/block_level_replaced_box.h"
#include "cobalt/layout/flex_container_box.h"
#include "cobalt/layout/inline_container_box.h"
#include "cobalt/layout/inline_level_replaced_box.h"
#include "cobalt/layout/layout_boxes.h"
#include "cobalt/layout/layout_stat_tracker.h"
#include "cobalt/layout/text_box.h"
#include "cobalt/layout/used_style.h"
#include "cobalt/layout/white_space_processing.h"
#include "cobalt/loader/image/lottie_animation.h"
#include "cobalt/media/base/decode_target_provider.h"
#include "cobalt/render_tree/image.h"
#include "cobalt/web_animations/keyframe_effect_read_only.h"
#include "starboard/decode_target.h"

namespace cobalt {
namespace layout {

using media::DecodeTargetProvider;

namespace {

scoped_refptr<render_tree::Image> GetVideoFrame(
    const scoped_refptr<DecodeTargetProvider>& decode_target_provider,
    render_tree::ResourceProvider* resource_provider) {
  TRACE_EVENT0("cobalt::layout", "GetVideoFrame()");
  SbDecodeTarget decode_target =
      decode_target_provider->GetCurrentSbDecodeTarget();
  if (SbDecodeTargetIsValid(decode_target)) {
    return resource_provider->CreateImageFromSbDecodeTarget(decode_target);
  } else {
    DCHECK(decode_target_provider);
    return NULL;
  }
}

scoped_refptr<render_tree::Image> GetLottieAnimation(
    scoped_refptr<loader::image::Image> lottie_animation) {
  TRACE_EVENT0("cobalt::layout", "GetLottieAnimation()");
  return base::polymorphic_downcast<loader::image::LottieAnimation*>(
             lottie_animation.get())
      ->animation();
}

}  // namespace

BoxGenerator::BoxGenerator(
    const scoped_refptr<cssom::CSSComputedStyleDeclaration>&
        parent_css_computed_style_declaration,
    const scoped_refptr<const web_animations::AnimationSet>& parent_animations,
    scoped_refptr<Paragraph>* paragraph, const int dom_element_depth,
    const Context* context)
    : parent_css_computed_style_declaration_(
          parent_css_computed_style_declaration),
      parent_animations_(parent_animations),
      paragraph_(paragraph),
      dom_element_depth_(dom_element_depth),
      context_(context) {}

BoxGenerator::~BoxGenerator() {
  // Later code assumes that if layout_boxes() is non-null, then it contains
  // more than one box.  This allows us to avoid some allocations of LayoutBoxes
  // objects.  We don't need to worry about setting layout_boxes() back to
  // null because this should end up being done in html_element.cc when the
  // boxes become invalidated.
  if (generating_html_element_ && !boxes_.empty()) {
    generating_html_element_->set_layout_boxes(
        std::unique_ptr<dom::LayoutBoxes>(new LayoutBoxes(std::move(boxes_))));
  }
}

void BoxGenerator::Visit(dom::Element* element) {
  if (dom_element_depth_ > context_->dom_max_element_depth) {
    LOG(WARNING) << "Elements too deep in the DOM tree are ignored in layout.";
    return;
  }

  scoped_refptr<dom::HTMLElement> html_element = element->AsHTMLElement();
  if (!html_element) {
    return;
  }
  generating_html_element_ = html_element;

  // If the html element already has layout boxes, we can reuse them.
  if (html_element->layout_boxes()) {
    LayoutBoxes* layout_boxes =
        base::polymorphic_downcast<LayoutBoxes*>(html_element->layout_boxes());
    DCHECK(boxes_.empty());
    DCHECK(!layout_boxes->boxes().empty());
    if (layout_boxes->boxes().front()->GetLevel() == Box::kBlockLevel) {
      boxes_ = layout_boxes->boxes();
      for (Boxes::const_iterator box_iterator = boxes_.begin();
           box_iterator != boxes_.end(); ++box_iterator) {
        Box* box = box_iterator->get();
        do {
          box->SetUiNavItem(html_element->GetUiNavItem());
          box->InvalidateParent();
          box = box->GetSplitSibling();
        } while (box != NULL);
      }
      return;
    }
  }

  scoped_refptr<dom::HTMLVideoElement> video_element =
      html_element->AsHTMLVideoElement();
  if (video_element) {
    VisitVideoElement(video_element);
    return;
  }

  scoped_refptr<dom::HTMLBRElement> br_element =
      html_element->AsHTMLBRElement();
  if (br_element) {
    VisitBrElement(br_element);
    return;
  }

  scoped_refptr<dom::LottiePlayer> lottie_player =
      html_element->AsLottiePlayer();
  if (lottie_player) {
    VisitLottiePlayer(lottie_player);
    return;
  }

  VisitNonReplacedElement(html_element);
}

namespace {

class ReplacedBoxGenerator : public cssom::NotReachedPropertyValueVisitor {
 public:
  ReplacedBoxGenerator(
      const scoped_refptr<cssom::CSSComputedStyleDeclaration>&
          css_computed_style_declaration,
      const ReplacedBox::ReplaceImageCB& replace_image_cb,
      const ReplacedBox::SetBoundsCB& set_bounds_cb,
      const scoped_refptr<Paragraph>& paragraph, int32 text_position,
      const base::Optional<LayoutUnit>& maybe_intrinsic_width,
      const base::Optional<LayoutUnit>& maybe_intrinsic_height,
      const base::Optional<float>& maybe_intrinsic_ratio,
      const BoxGenerator::Context* context,
      base::Optional<ReplacedBox::ReplacedBoxMode> replaced_box_mode,
      math::SizeF content_size,
      base::Optional<render_tree::LottieAnimation::LottieProperties>
          lottie_properties,
      bool center_crop = false)
      : css_computed_style_declaration_(css_computed_style_declaration),
        replace_image_cb_(replace_image_cb),
        set_bounds_cb_(set_bounds_cb),
        paragraph_(paragraph),
        text_position_(text_position),
        maybe_intrinsic_width_(maybe_intrinsic_width),
        maybe_intrinsic_height_(maybe_intrinsic_height),
        maybe_intrinsic_ratio_(maybe_intrinsic_ratio),
        context_(context),
        replaced_box_mode_(replaced_box_mode),
        content_size_(content_size),
        lottie_properties_(lottie_properties),
        center_crop_(center_crop) {}

  void VisitKeyword(cssom::KeywordValue* keyword) override;

  const scoped_refptr<ReplacedBox>& replaced_box() { return replaced_box_; }

 private:
  const scoped_refptr<cssom::CSSComputedStyleDeclaration>
      css_computed_style_declaration_;
  const ReplacedBox::ReplaceImageCB replace_image_cb_;
  const ReplacedBox::SetBoundsCB set_bounds_cb_;
  const scoped_refptr<Paragraph> paragraph_;
  const int32 text_position_;
  const base::Optional<LayoutUnit> maybe_intrinsic_width_;
  const base::Optional<LayoutUnit> maybe_intrinsic_height_;
  const base::Optional<float> maybe_intrinsic_ratio_;
  const BoxGenerator::Context* context_;
  base::Optional<ReplacedBox::ReplacedBoxMode> replaced_box_mode_;
  math::SizeF content_size_;
  base::Optional<render_tree::LottieAnimation::LottieProperties>
      lottie_properties_;
  bool center_crop_;

  scoped_refptr<ReplacedBox> replaced_box_;
};

void ReplacedBoxGenerator::VisitKeyword(cssom::KeywordValue* keyword) {
  // See https://www.w3.org/TR/CSS21/visuren.html#display-prop.
  switch (keyword->value()) {
    // Generate a block-level replaced box.
    case cssom::KeywordValue::kBlock:
    case cssom::KeywordValue::kFlex:
      replaced_box_ = WrapRefCounted(new BlockLevelReplacedBox(
          css_computed_style_declaration_, replace_image_cb_, set_bounds_cb_,
          paragraph_, text_position_, maybe_intrinsic_width_,
          maybe_intrinsic_height_, maybe_intrinsic_ratio_,
          context_->used_style_provider, replaced_box_mode_, content_size_,
          lottie_properties_, center_crop_, context_->layout_stat_tracker));
      break;
    // Generate an inline-level replaced box. There is no need to distinguish
    // between inline replaced elements and inline-block replaced elements
    // because their widths, heights, and margins are calculated in the same
    // way.
    case cssom::KeywordValue::kInline:
    case cssom::KeywordValue::kInlineBlock:
    case cssom::KeywordValue::kInlineFlex:
      replaced_box_ = WrapRefCounted(new InlineLevelReplacedBox(
          css_computed_style_declaration_, replace_image_cb_, set_bounds_cb_,
          paragraph_, text_position_, maybe_intrinsic_width_,
          maybe_intrinsic_height_, maybe_intrinsic_ratio_,
          context_->used_style_provider, replaced_box_mode_, content_size_,
          lottie_properties_, center_crop_, context_->layout_stat_tracker));
      break;
    // The element generates no boxes and has no effect on layout.
    case cssom::KeywordValue::kNone:
      // Leave |replaced_box_| NULL.
      break;
    default:
      NOTREACHED();
      break;
  }
}

}  // namespace

void BoxGenerator::VisitVideoElement(dom::HTMLVideoElement* video_element) {
  // For video elements, create a replaced box.

  // A replaced box is formatted as an atomic inline element. It is treated
  // directionally as a neutral character and its line breaking behavior is
  // equivalent to that of the Object Replacement Character.
  //   https://www.w3.org/TR/CSS21/visuren.html#inline-boxes
  //   https://www.w3.org/TR/CSS21/visuren.html#propdef-unicode-bidi
  //   https://www.w3.org/TR/css3-text/#line-break-details
  int32 text_position =
      (*paragraph_)
          ->AppendCodePoint(Paragraph::kObjectReplacementCharacterCodePoint);

  render_tree::ResourceProvider* resource_provider =
      *video_element->node_document()
           ->html_element_context()
           ->resource_provider();

  // If the optional is disengaged, then we don't know if punch out is enabled
  // or not.
  base::Optional<ReplacedBox::ReplacedBoxMode> replaced_box_mode;
  bool paint_to_black = false;
  auto decode_target_provider =
      video_element->GetDecodeTargetProvider(&paint_to_black);
  if (decode_target_provider) {
    DecodeTargetProvider::OutputMode output_mode =
        decode_target_provider->GetOutputMode();
    if (output_mode == DecodeTargetProvider::kOutputModeInvalid) {
      if (paint_to_black) {
        replaced_box_mode = ReplacedBox::ReplacedBoxMode::kPaintToBlack;
      }
    } else {
      replaced_box_mode =
          (output_mode == DecodeTargetProvider::kOutputModePunchOut)
              ? ReplacedBox::ReplacedBoxMode::kPunchOutVideo
              : ReplacedBox::ReplacedBoxMode::kDecodeToTextureVideo;
    }
  } else {
    // ReplacedBox won't paint anything when |decode_target_provider| is
    // nullptr, as |replace_image_cb_| is also null in this case.
  }

  ReplacedBoxGenerator replaced_box_generator(
      video_element->css_computed_style_declaration(),
      decode_target_provider
          ? base::Bind(GetVideoFrame, decode_target_provider, resource_provider)
          : ReplacedBox::ReplaceImageCB(),
      video_element->GetSetBoundsCB(), *paragraph_, text_position,
      base::nullopt, base::nullopt, base::nullopt, context_, replaced_box_mode,
      video_element->GetVideoSize(), base::nullopt,
      video_element->IsVideoPlaybackFitToFillEnabled());
  video_element->computed_style()->display()->Accept(&replaced_box_generator);

  scoped_refptr<ReplacedBox> replaced_box =
      replaced_box_generator.replaced_box();
  if (replaced_box.get() == NULL) {
    // The element with "display: none" generates no boxes and has no effect
    // on layout. Descendant elements do not generate any boxes either.
    // This behavior cannot be overridden by setting the "display" property on
    // the descendants.
    //   https://www.w3.org/TR/CSS21/visuren.html#display-prop
    return;
  }

#ifdef ENABLE_DEBUGGER
  replaced_box->SetGeneratingNode(video_element);
#endif  // ENABLE_DEBUGGER

  replaced_box->SetUiNavItem(video_element->GetUiNavItem());
  boxes_.push_back(replaced_box);

  // The content of replaced elements is not considered in the CSS rendering
  // model.
  //   https://www.w3.org/TR/CSS21/conform.html#replaced-element
}

void BoxGenerator::VisitBrElement(dom::HTMLBRElement* br_element) {
  // If the br element has "display: none", then it has no effect on the layout.
  if (br_element->computed_style()->display() ==
      cssom::KeywordValue::GetNone()) {
    return;
  }

  scoped_refptr<cssom::CSSComputedStyleDeclaration>
      css_computed_style_declaration = new cssom::CSSComputedStyleDeclaration();
  css_computed_style_declaration->SetData(GetComputedStyleOfAnonymousBox(
      br_element->css_computed_style_declaration()));

  css_computed_style_declaration->set_animations(br_element->animations());

  DCHECK(*paragraph_);
  int32 text_position = (*paragraph_)->GetTextEndPosition();

  const bool kTriggersLineBreakTrue = true;
  const bool kIsProductOfSplitFalse = false;

  scoped_refptr<TextBox> br_text_box =
      new TextBox(css_computed_style_declaration, *paragraph_, text_position,
                  text_position, kTriggersLineBreakTrue, kIsProductOfSplitFalse,
                  context_->used_style_provider, context_->layout_stat_tracker);

  // Add a line feed code point to the paragraph to signify the new line for
  // the line breaking and bidirectional algorithms.
  (*paragraph_)->AppendCodePoint(Paragraph::kLineFeedCodePoint);

#ifdef ENABLE_DEBUGGER
  br_text_box->SetGeneratingNode(br_element);
#endif  // ENABLE_DEBUGGER

  br_text_box->SetUiNavItem(br_element->GetUiNavItem());
  boxes_.push_back(br_text_box);
}

void BoxGenerator::VisitLottiePlayer(dom::LottiePlayer* lottie_player) {
  int32 text_position =
      (*paragraph_)
          ->AppendCodePoint(Paragraph::kObjectReplacementCharacterCodePoint);

  ReplacedBoxGenerator replaced_box_generator(
      lottie_player->css_computed_style_declaration(),
      lottie_player->cached_image() &&
              lottie_player->cached_image()->TryGetResource()
          ? base::Bind(GetLottieAnimation,
                       lottie_player->cached_image()->TryGetResource())
          : ReplacedBox::ReplaceImageCB(),
      ReplacedBox::SetBoundsCB(), *paragraph_, text_position, base::nullopt,
      base::nullopt, base::nullopt, context_,
      ReplacedBox::ReplacedBoxMode::kLottie,
      math::Size() /* only relevant to punch out video */,
      lottie_player->GetProperties());
  lottie_player->computed_style()->display()->Accept(&replaced_box_generator);

  scoped_refptr<ReplacedBox> replaced_box =
      replaced_box_generator.replaced_box();
  if (replaced_box.get() == NULL) {
    // The element with "display: none" generates no boxes and has no effect
    // on layout. Descendant elements do not generate any boxes either.
    // This behavior cannot be overridden by setting the "display" property on
    // the descendants.
    //   https://www.w3.org/TR/CSS21/visuren.html#display-prop

    // A LottiePlayer element with "display: none" should potentially trigger
    // a freeze event.
    if (!lottie_player->GetProperties().onfreeze_callback.is_null()) {
      lottie_player->GetProperties().onfreeze_callback.Run();
    }
    return;
  }

#ifdef ENABLE_DEBUGGER
  replaced_box->SetGeneratingNode(lottie_player);
#endif  // ENABLE_DEBUGGER

  replaced_box->SetUiNavItem(lottie_player->GetUiNavItem());
  boxes_.push_back(replaced_box);
}

namespace {

typedef dom::HTMLElement::DirState DirState;

class ContainerBoxGenerator : public cssom::NotReachedPropertyValueVisitor {
 public:
  enum CloseParagraph {
    kDoNotCloseParagraph,
    kCloseParagraph,
  };

  ContainerBoxGenerator(DirState element_dir,
                        const scoped_refptr<cssom::CSSComputedStyleDeclaration>&
                            css_computed_style_declaration,
                        scoped_refptr<Paragraph>* paragraph,
                        const BoxGenerator::Context* context)
      : element_dir_(element_dir),
        css_computed_style_declaration_(css_computed_style_declaration),
        context_(context),
        has_scoped_directional_isolate_(false),
        paragraph_(paragraph),
        paragraph_scoped_(false) {}
  ~ContainerBoxGenerator();

  void VisitKeyword(cssom::KeywordValue* keyword) override;

  const scoped_refptr<ContainerBox>& container_box() { return container_box_; }

 private:
  void CreateScopedParagraph(CloseParagraph close_prior_paragraph);

  const DirState element_dir_;
  const scoped_refptr<cssom::CSSComputedStyleDeclaration>
      css_computed_style_declaration_;
  const BoxGenerator::Context* context_;

  // If a directional isolate was added to the paragraph by this container box
  // and needs to be popped in the destructor:
  // http://unicode.org/reports/tr9/#Explicit_Directional_Isolates
  bool has_scoped_directional_isolate_;

  scoped_refptr<Paragraph>* paragraph_;
  scoped_refptr<Paragraph> prior_paragraph_;
  bool paragraph_scoped_;

  scoped_refptr<ContainerBox> container_box_;
};

ContainerBoxGenerator::~ContainerBoxGenerator() {
  // If there's a scoped directional isolate, then it needs to popped from
  // the paragraph so that this box does not impact the directionality of later
  // boxes in the paragraph.
  // http://unicode.org/reports/tr9/#Terminating_Explicit_Directional_Isolates
  if (has_scoped_directional_isolate_) {
    (*paragraph_)->AppendCodePoint(Paragraph::kPopDirectionalIsolateCodePoint);
  }

  if (paragraph_scoped_) {
    (*paragraph_)->Close();

    // If the prior paragraph was closed, then replace it with a new paragraph
    // that has the same direction as the previous one. Otherwise, restore the
    // prior one.
    if (prior_paragraph_->IsClosed()) {
      *paragraph_ = new Paragraph(
          prior_paragraph_->GetLocale(), prior_paragraph_->base_direction(),
          prior_paragraph_->GetDirectionalFormattingStack(),
          context_->line_break_iterator, context_->character_break_iterator);
    } else {
      *paragraph_ = prior_paragraph_;
    }
  }
}

void ContainerBoxGenerator::VisitKeyword(cssom::KeywordValue* keyword) {
  // See https://www.w3.org/TR/CSS21/visuren.html#display-prop.
  switch (keyword->value()) {
    // Generate a block-level block container box.
    case cssom::KeywordValue::kBlock:
      // The block ends the current paragraph and begins a new one that ends
      // with the block, so close the current paragraph, and create a new
      // paragraph that will close when the container box generator is
      // destroyed.
      CreateScopedParagraph(kCloseParagraph);

      container_box_ = base::WrapRefCounted(new BlockLevelBlockContainerBox(
          css_computed_style_declaration_, (*paragraph_)->base_direction(),
          context_->used_style_provider, context_->layout_stat_tracker));
      break;
    case cssom::KeywordValue::kFlex:
      container_box_ = base::WrapRefCounted(new BlockLevelFlexContainerBox(
          css_computed_style_declaration_, (*paragraph_)->base_direction(),
          context_->used_style_provider, context_->layout_stat_tracker));
      break;
    case cssom::KeywordValue::kInlineFlex: {
      // An inline flex container is an atomic inline and therefore is treated
      // directionally as a neutral character and its line breaking behavior is
      // equivalent to that of the Object Replacement Character.
      //   https://www.w3.org/TR/css-display-3/#atomic-inline
      //   https://www.w3.org/TR/CSS21/visuren.html#propdef-unicode-bidi
      //   https://www.w3.org/TR/css3-text/#line-break-details
      int32 text_position =
          (*paragraph_)
              ->AppendCodePoint(
                  Paragraph::kObjectReplacementCharacterCodePoint);
      scoped_refptr<Paragraph> prior_paragraph = *paragraph_;

      // The inline flex container creates a new paragraph, which the old
      // paragraph flows around. Create a new paragraph, which will close with
      // the end of the flex container. However, do not close the old
      // paragraph, because it will continue once the scope of the inline block
      // ends.
      CreateScopedParagraph(kDoNotCloseParagraph);

      container_box_ = base::WrapRefCounted(new InlineLevelFlexContainerBox(
          css_computed_style_declaration_, (*paragraph_)->base_direction(),
          prior_paragraph, text_position, context_->used_style_provider,
          context_->layout_stat_tracker));
    } break;
    // Generate one or more inline boxes. Note that more inline boxes may be
    // generated when the original inline box is split due to participation
    // in the formatting context.
    case cssom::KeywordValue::kInline:
      // If the creating HTMLElement had an explicit directionality, then append
      // a directional isolate to the paragraph. This will be popped from the
      // paragraph, when the ContainerBoxGenerator goes out of scope.
      // https://dev.w3.org/html5/spec-preview/global-attributes.html#the-directionality
      // http://unicode.org/reports/tr9/#Explicit_Directional_Isolates
      // http://unicode.org/reports/tr9/#Markup_And_Formatting
      if (element_dir_ == DirState::kDirLeftToRight) {
        has_scoped_directional_isolate_ = true;
        (*paragraph_)->AppendCodePoint(Paragraph::kLeftToRightIsolateCodePoint);
      } else if (element_dir_ == DirState::kDirRightToLeft) {
        has_scoped_directional_isolate_ = true;
        (*paragraph_)->AppendCodePoint(Paragraph::kRightToLeftIsolateCodePoint);
      }

      // If the paragraph has not started yet, then add a no-break space to it,
      // thereby starting the paragraph without providing a wrappable location,
      // as the line should never wrap at the start of text.
      // http://unicode.org/reports/tr14/#BreakingRules
      //
      // Starting the paragraph ensures that subsequent text nodes create text
      // boxes, even when they consist of only collapsible white-space. This is
      // necessary because empty inline container boxes can justify a line's
      // existence if they have a non-zero margin, border or padding, which
      // means that the collapsible white-space is potentially wrappable
      // regardless of whether any intervening text is added to the paragraph.
      // Not creating the collapsible text box in this case would incorrectly
      // eliminate a wrappable location from the line.
      if ((*paragraph_)->GetTextEndPosition() == 0) {
        (*paragraph_)->AppendCodePoint(Paragraph::kNoBreakSpaceCodePoint);
      }

      container_box_ = base::WrapRefCounted(new InlineContainerBox(
          css_computed_style_declaration_, context_->used_style_provider,
          context_->layout_stat_tracker, (*paragraph_)->base_direction()));
      break;
    // Generate an inline-level block container box. The inside of
    // an inline-block is formatted as a block box, and the element itself
    // is formatted as an atomic inline-level box.
    //   https://www.w3.org/TR/CSS21/visuren.html#inline-boxes
    case cssom::KeywordValue::kInlineBlock: {
      // An inline block is an atomic inline and therefore is treated
      // directionally as a neutral character and its line breaking behavior is
      // equivalent to that of the Object Replacement Character.
      //   https://www.w3.org/TR/css-display-3/#atomic-inline
      //   https://www.w3.org/TR/CSS21/visuren.html#propdef-unicode-bidi
      //   https://www.w3.org/TR/css3-text/#line-break-details
      int32 text_position =
          (*paragraph_)
              ->AppendCodePoint(
                  Paragraph::kObjectReplacementCharacterCodePoint);
      scoped_refptr<Paragraph> prior_paragraph = *paragraph_;

      // The inline block creates a new paragraph, which the old paragraph
      // flows around. Create a new paragraph, which will close with the end
      // of the inline block. However, do not close the old paragraph, because
      // it will continue once the scope of the inline block ends.
      CreateScopedParagraph(kDoNotCloseParagraph);

      container_box_ = base::WrapRefCounted(new InlineLevelBlockContainerBox(
          css_computed_style_declaration_, (*paragraph_)->base_direction(),
          prior_paragraph, text_position, context_->used_style_provider,
          context_->layout_stat_tracker));
    } break;
    // The element generates no boxes and has no effect on layout.
    case cssom::KeywordValue::kNone:
      // Leave |container_box_| NULL.
      break;
    default:
      NOTREACHED();
      break;
  }
}

void ContainerBoxGenerator::CreateScopedParagraph(
    CloseParagraph close_prior_paragraph) {
  DCHECK(!paragraph_scoped_);

  paragraph_scoped_ = true;
  prior_paragraph_ = *paragraph_;

  // Determine the base direction of the new paragraph based upon the
  // directionality of the creating HTMLElement. If there was no explicit
  // directionality, then it is based upon the prior paragraph, meaning that
  // it is inherited from the parent element.
  // https://dev.w3.org/html5/spec-preview/global-attributes.html#the-directionality
  BaseDirection base_direction;
  if (element_dir_ == DirState::kDirLeftToRight) {
    base_direction = kLeftToRightBaseDirection;
  } else if (element_dir_ == DirState::kDirRightToLeft) {
    base_direction = kRightToLeftBaseDirection;
  } else {
    base_direction = prior_paragraph_->GetDirectionalFormattingStackDirection();
  }

  if (close_prior_paragraph == kCloseParagraph) {
    prior_paragraph_->Close();
  }

  *paragraph_ = new Paragraph(prior_paragraph_->GetLocale(), base_direction,
                              Paragraph::DirectionalFormattingStack(),
                              context_->line_break_iterator,
                              context_->character_break_iterator);
}

}  // namespace

void BoxGenerator::AppendChildBoxToLine(const scoped_refptr<Box>& child_box) {
  // When an inline box contains an in-flow block-level box, the inline box
  // (and its inline ancestors within the same block container box*) are
  // broken around the block-level box, splitting the inline box into two
  // boxes (even if either side is empty), one on each side of
  // the block-level box. The line boxes before the break and after
  // the break are enclosed in anonymous block boxes, and the block-level
  // box becomes a sibling of those anonymous boxes.
  //   https://www.w3.org/TR/CSS21/visuren.html#anonymous-block-level
  //
  // * CSS 2.1 says "the same line box" but line boxes are not real boxes
  //   in Cobalt, see |LineBox| for details.
  ContainerBox* last_container_box =
      base::polymorphic_downcast<ContainerBox*>(boxes_.back().get());

  if (!last_container_box->TryAddChild(child_box)) {
    scoped_refptr<ContainerBox> next_container_box =
        last_container_box->TrySplitAtEnd();
    DCHECK(next_container_box);

    // Attempt to add the box to the next container before adding it to the top
    // level. In the case where a line break was blocking the add in the last
    // container, the child should successfully go into the next container.
    if (!next_container_box->TryAddChild(child_box)) {
      boxes_.push_back(child_box);
    }

    boxes_.push_back(next_container_box);
  }
}

namespace {

class ContentProvider : public cssom::NotReachedPropertyValueVisitor {
 public:
  ContentProvider() : is_element_generated_(false) {}

  const std::string& content_string() const { return content_string_; }
  bool is_element_generated() const { return is_element_generated_; }

  void VisitString(cssom::StringValue* string_value) override {
    content_string_ = string_value->value();
    is_element_generated_ = true;
  }

  void VisitURL(cssom::URLValue* url_value) override {
    // TODO: Implement support for 'content: url(foo)'.
    DLOG(ERROR) << "Unsupported content property value: "
                << url_value->ToString();
  }

  void VisitKeyword(cssom::KeywordValue* keyword) override {
    switch (keyword->value()) {
      case cssom::KeywordValue::kNone:
      case cssom::KeywordValue::kNormal:
        // The pseudo-element is not generated.
        //   https://www.w3.org/TR/CSS21/generate.html#propdef-content
        is_element_generated_ = false;
        break;
      default:
        NOTREACHED();
    }
  }

 private:
  std::string content_string_;
  bool is_element_generated_;
};

bool HasOnlyColorPropertyAnimations(
    const scoped_refptr<const web_animations::AnimationSet>& animations) {
  const web_animations::AnimationSet::InternalSet& animation_set =
      animations->animations();

  if (animation_set.empty()) {
    return false;
  }

  for (web_animations::AnimationSet::InternalSet::const_iterator iter =
           animation_set.begin();
       iter != animation_set.end(); ++iter) {
    const web_animations::KeyframeEffectReadOnly* keyframe_effect =
        base::polymorphic_downcast<
            const web_animations::KeyframeEffectReadOnly*>(
            (*iter)->effect().get());
    if (!keyframe_effect->data().IsOnlyPropertyAnimated(
            cssom::kColorProperty)) {
      return false;
    }
  }

  return true;
}

}  // namespace

void BoxGenerator::AppendPseudoElementToLine(
    dom::HTMLElement* html_element,
    dom::PseudoElementType pseudo_element_type) {
  // Add boxes with generated content from :before or :after pseudo elements to
  // the line.
  //   https://www.w3.org/TR/CSS21/generate.html#before-after-content
  dom::PseudoElement* pseudo_element =
      html_element->pseudo_element(pseudo_element_type);
  if (!pseudo_element) {
    return;
  }

  // We assume that if our parent element's boxes are being regenerated, then we
  // should regenerate the pseudo element boxes.  There are some cases where
  // the parent element may be regenerating its boxes even if it already had
  // some, such as if its boxes were inline level.  In that case, pseudo
  // elements may also have boxes, so we make it clear that we will not be
  // reusing pseudo element boxes even if they exist by explicitly resetting
  // them now.
  pseudo_element->reset_layout_boxes();

  ContainerBoxGenerator pseudo_element_box_generator(
      DirState::kDirNotDefined,
      pseudo_element->css_computed_style_declaration(), paragraph_, context_);
  pseudo_element->computed_style()->display()->Accept(
      &pseudo_element_box_generator);
  scoped_refptr<ContainerBox> pseudo_element_box =
      pseudo_element_box_generator.container_box();
  // A pseudo element with "display: none" generates no boxes and has no
  // effect on layout.
  if (pseudo_element_box.get() == NULL) {
    return;
  }

  // Generate the box(es) to be added to the associated html element, using
  // the computed style of the pseudo element.

  // The generated content is a text node with the string value of the
  // 'content' property.
  ContentProvider content_provider;
  pseudo_element->computed_style()->content()->Accept(&content_provider);
  if (!content_provider.is_element_generated()) {
    return;
  }

  scoped_refptr<dom::Text> child_node(new dom::Text(
      html_element->node_document(), content_provider.content_string()));

  // In the case where the pseudo element has no color property of its
  // own, but is directly inheriting a color property from its parent html
  // element, we use the parent's animations if the pseudo element has
  // none and the parent has only color property animations. This allows
  // the child text boxes to animate properly and fixes bugs, while
  // keeping the impact of the fix as small as possible to minimize the
  // risk of introducing new bugs.
  // TODO: Remove this logic when support for inheriting
  // animations on inherited properties is added.
  bool use_html_element_animations =
      !pseudo_element->computed_style()->IsDeclared(cssom::kColorProperty) &&
      html_element->computed_style()->IsDeclared(cssom::kColorProperty) &&
      pseudo_element->css_computed_style_declaration()
          ->animations()
          ->IsEmpty() &&
      HasOnlyColorPropertyAnimations(
          html_element->css_computed_style_declaration()->animations());

  BoxGenerator child_box_generator(
      pseudo_element->css_computed_style_declaration(),
      use_html_element_animations ? html_element->animations()
                                  : pseudo_element->animations(),
      paragraph_, dom_element_depth_ + 1, context_);
  child_node->Accept(&child_box_generator);
  for (const auto& child_box : child_box_generator.boxes()) {
    if (!pseudo_element_box->TryAddChild(child_box)) {
      return;
    }
  }

  pseudo_element->set_layout_boxes(
      std::unique_ptr<dom::LayoutBoxes>(new LayoutBoxes({pseudo_element_box})));

  // Add the box(es) from the pseudo element to the associated element.
  AppendChildBoxToLine(pseudo_element_box);
}

namespace {
scoped_refptr<cssom::CSSComputedStyleDeclaration> StripBackground(
    const scoped_refptr<cssom::CSSComputedStyleDeclaration>& style) {
  scoped_refptr<cssom::CSSComputedStyleDeclaration> new_style(
      new cssom::CSSComputedStyleDeclaration());
  new_style->set_animations(style->animations());

  scoped_refptr<cssom::MutableCSSComputedStyleData> new_data(
      new cssom::MutableCSSComputedStyleData());
  new_data->AssignFrom(*style->data());
  new_data->SetPropertyValue(cssom::kBackgroundColorProperty, NULL);
  new_data->SetPropertyValue(cssom::kBackgroundImageProperty, NULL);
  new_style->SetData(new_data);

  return new_style;
}
}  // namespace

void BoxGenerator::VisitNonReplacedElement(dom::HTMLElement* html_element) {
  const scoped_refptr<cssom::CSSComputedStyleDeclaration>& element_style(
      html_element->css_computed_style_declaration());

  ContainerBoxGenerator container_box_generator(
      html_element->GetUsedDirState(),
      html_element == context_->ignore_background_element
          ? StripBackground(element_style)
          : element_style,
      paragraph_, context_);
  html_element->computed_style()->display()->Accept(&container_box_generator);
  scoped_refptr<ContainerBox> container_box_before_split =
      container_box_generator.container_box();
  if (container_box_before_split.get() == NULL) {
    // The element with "display: none" generates no boxes and has no effect
    // on layout. Descendant elements do not generate any boxes either.
    // This behavior cannot be overridden by setting the "display" property on
    // the descendants.
    //   https://www.w3.org/TR/CSS21/visuren.html#display-prop
    return;
  }

#ifdef ENABLE_DEBUGGER
  container_box_before_split->SetGeneratingNode(html_element);
#endif  // ENABLE_DEBUGGER

  container_box_before_split->SetUiNavItem(html_element->GetUiNavItem());
  boxes_.push_back(container_box_before_split);

  // We already handle the case where the Intersection Observer root is the
  // viewport with the initial containing block in layout.
  if (html_element !=
      html_element->node_document()->document_element()->AsHTMLElement()) {
    BoxIntersectionObserverModule::IntersectionObserverRootVector roots =
        html_element->GetLayoutIntersectionObserverRoots();
    BoxIntersectionObserverModule::IntersectionObserverTargetVector targets =
        html_element->GetLayoutIntersectionObserverTargets();
    container_box_before_split->AddIntersectionObserverRootsAndTargets(
        std::move(roots), std::move(targets));
  }

  AppendPseudoElementToLine(html_element, dom::kBeforePseudoElementType);

  // Generate child boxes.
  for (dom::Node* child_node = html_element->first_child(); child_node;
       child_node = child_node->next_sibling()) {
    BoxGenerator child_box_generator(
        html_element->css_computed_style_declaration(),
        html_element->css_computed_style_declaration()->animations(),
        paragraph_, dom_element_depth_ + 1, context_);
    child_node->Accept(&child_box_generator);
    const Boxes& child_boxes = child_box_generator.boxes();
    for (Boxes::const_iterator child_box_iterator = child_boxes.begin();
         child_box_iterator != child_boxes.end(); ++child_box_iterator) {
      AppendChildBoxToLine(*child_box_iterator);
    }
  }

  AppendPseudoElementToLine(html_element, dom::kAfterPseudoElementType);
}

void BoxGenerator::Visit(dom::CDATASection* cdata_section) {}

void BoxGenerator::Visit(dom::Comment* comment) {}

void BoxGenerator::Visit(dom::Document* document) { NOTREACHED(); }

void BoxGenerator::Visit(dom::DocumentType* document_type) { NOTREACHED(); }

namespace {
scoped_refptr<web_animations::AnimationSet> GetAnimationsForAnonymousBox(
    const scoped_refptr<const web_animations::AnimationSet>&
        parent_animations) {
  scoped_refptr<web_animations::AnimationSet> animations(
      new web_animations::AnimationSet);
  const web_animations::AnimationSet::InternalSet& animation_set =
      parent_animations->animations();
  const cssom::PropertyKeyVector& properties_set =
      cssom::GetInheritedAnimatableProperties();

  // Go through all the parent animations and only add those pertaining to
  // inheritable properties.
  for (const auto& animation : animation_set) {
    const web_animations::KeyframeEffectReadOnly* keyframe_effect =
        base::polymorphic_downcast<
            const web_animations::KeyframeEffectReadOnly*>(
            animation->effect().get());

    for (const auto& property : properties_set) {
      if (keyframe_effect->data().IsPropertyAnimated(property)) {
        animations->AddAnimation(animation);
        break;
      }
    }
  }

  return animations;
}
}  // namespace

// Append the text from the text node to the text paragraph and create the
// node's initial text box. The text box has indices that map to the paragraph,
// which allows it to retrieve its underlying text. Initially, a single text box
// is created that encompasses the entire node.

// Prior to layout, the paragraph applies the Unicode bidirectional algorithm
// to its text (http://www.unicode.org/reports/tr9/) and causes the text boxes
// referencing it to split at level runs.
//
// During layout, the text boxes are potentially split further, as the paragraph
// determines line breaking locations for its text at soft wrap opportunities
// (https://www.w3.org/TR/css-text-3/#soft-wrap-opportunity) according to the
// Unicode line breaking algorithm (http://www.unicode.org/reports/tr14/).
void BoxGenerator::Visit(dom::Text* text) {
  scoped_refptr<cssom::CSSComputedStyleDeclaration>
      css_computed_style_declaration = new cssom::CSSComputedStyleDeclaration();
  css_computed_style_declaration->SetData(
      GetComputedStyleOfAnonymousBox(parent_css_computed_style_declaration_));

  // Copy inheritable animatable properties from the parent.
  css_computed_style_declaration->set_animations(
      GetAnimationsForAnonymousBox(parent_animations_));

  DCHECK(text);
  DCHECK(css_computed_style_declaration->data());

  const std::string& original_text = text->text();
  if (original_text.empty()) {
    return;
  }

  const scoped_refptr<cssom::PropertyValue>& white_space_property =
      css_computed_style_declaration->data()->white_space();
  bool should_preserve_segment_breaks =
      !DoesCollapseSegmentBreaks(white_space_property);
  bool should_collapse_white_space =
      DoesCollapseWhiteSpace(white_space_property);
  bool should_prevent_text_wrapping =
      !DoesAllowTextWrapping(white_space_property);

  // Loop until the entire text is consumed. If the white-space property does
  // not have a value of "pre" or "pre-wrap" then the entire text will be
  // processed the first time through the loop; otherwise, the text will be
  // split at newline sequences.
  size_t start_index = 0;
  while (start_index < original_text.size()) {
    size_t end_index;
    size_t newline_sequence_length;

    // Phase I: Segment Break Transformation Rules
    // https://www.w3.org/TR/css3-text/#line-break-transform
    bool generates_newline = false;
    if (should_preserve_segment_breaks) {
      generates_newline = FindNextNewlineSequence(
          original_text, start_index, &end_index, &newline_sequence_length);
    } else {
      end_index = original_text.size();
      newline_sequence_length = 0;
    }

    std::string modifiable_text =
        original_text.substr(start_index, end_index - start_index);

    // Phase I: Collapsing and Transformation
    //   https://www.w3.org/TR/css3-text/#white-space-phase-1
    if (should_collapse_white_space) {
      CollapseWhiteSpace(&modifiable_text);

      // If the paragraph hasn't been started yet and the text only consists of
      // a collapsible space, then return without creating the box. The leading
      // spaces in a line box are collapsed, so this box would be collapsed
      // away during the layout.
      if ((*paragraph_)->GetTextEndPosition() == 0 && modifiable_text == " ") {
        return;
      }
    }

    Paragraph::TextTransform transform;
    if (css_computed_style_declaration->data()->text_transform() ==
        cssom::KeywordValue::GetUppercase()) {
      transform = Paragraph::kUppercaseTextTransform;
    } else {
      transform = Paragraph::kNoTextTransform;
    }

    DCHECK(*paragraph_);
    int32 text_start_position =
        (*paragraph_)->AppendUtf8String(modifiable_text, transform);
    int32 text_end_position = (*paragraph_)->GetTextEndPosition();

    const bool kIsProductOfSplitFalse = false;

    boxes_.push_back(new TextBox(
        css_computed_style_declaration, *paragraph_, text_start_position,
        text_end_position, generates_newline, kIsProductOfSplitFalse,
        context_->used_style_provider, context_->layout_stat_tracker));

    // Newline sequences should be transformed into a preserved line feed.
    //   https://www.w3.org/TR/css3-text/#line-break-transform
    if (generates_newline) {
      (*paragraph_)->AppendCodePoint(Paragraph::kLineFeedCodePoint);
    }

    start_index = end_index + newline_sequence_length;
  }

  // If the white-space style prevents text wrapping and the text ends in a
  // space, then add a no-break space to the paragraph, so that the last space
  // will be treated as a no-break space when determining if wrapping can occur
  // before the subsequent box.
  //
  // While CSS3 gives little direction to the user agent as to what should occur
  // in this case, this is guidance given by CSS2, which states that "any
  // sequence of spaces (U+0020) unbroken by an element boundary is treated as a
  // sequence of non-breaking spaces." Furthermore, this matches the behavior of
  // WebKit, Firefox, and IE.
  // https://www.w3.org/TR/css-text-3/#white-space-phase-1
  // https://www.w3.org/TR/CSS2/text.html#white-space-model
  if (should_prevent_text_wrapping &&
      original_text[original_text.size() - 1] == ' ') {
    (*paragraph_)->AppendCodePoint(Paragraph::kNoBreakSpaceCodePoint);
  }
}

}  // namespace layout
}  // namespace cobalt
