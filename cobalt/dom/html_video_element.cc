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

#include "cobalt/dom/html_video_element.h"

#include "base/logging.h"
#include "base/strings/string_number_conversions.h"
#include "base/trace_event/trace_event.h"
#include "cobalt/dom/dom_settings.h"
#include "cobalt/dom/media_settings.h"
#include "cobalt/dom/performance.h"
#include "cobalt/dom/window.h"
#include "cobalt/math/size_f.h"

namespace cobalt {
namespace dom {

using media::DecodeTargetProvider;
using media::WebMediaPlayer;

const char HTMLVideoElement::kTagName[] = "video";

const MediaSettings& GetMediaSettings(web::EnvironmentSettings* settings) {
  DCHECK(settings);
  DCHECK(settings->context());
  DCHECK(settings->context()->web_settings());

  const auto& web_settings = settings->context()->web_settings();
  return web_settings->media_settings();
}

HTMLVideoElement::HTMLVideoElement(Document* document)
    : HTMLMediaElement(document, base_token::Token(kTagName)) {}

uint32 HTMLVideoElement::width() const {
  size_t idx;
  std::string value_in_string = GetAttribute("width").value_or("0");
  uint32 result = std::stoi(value_in_string, &idx);
  if (value_in_string.size() != idx) {
    LOG(WARNING) << "Invalid width attribute: \'" << value_in_string << "\'";
  }
  return result;
}

uint32 HTMLVideoElement::height() const {
  std::string value_in_string = GetAttribute("height").value_or("0");
  size_t idx;
  uint32 result = std::stoi(value_in_string, &idx);
  if (value_in_string.size() != idx) {
    LOG(WARNING) << "Invalid height attribute: \'" << value_in_string << "\'";
  }
  return result;
}

void HTMLVideoElement::set_width(uint32 width) {
  SetAttribute("width", base::NumberToString(width));
}

void HTMLVideoElement::set_height(uint32 height) {
  SetAttribute("height", base::NumberToString(height));
}

uint32 HTMLVideoElement::video_width() const {
  if (!player()) {
    return 0u;
  }
  int width = player()->GetNaturalWidth();
  DCHECK_GE(width, 0);
  return static_cast<uint32>(width);
}

uint32 HTMLVideoElement::video_height() const {
  if (!player()) {
    return 0u;
  }
  int height = player()->GetNaturalHeight();
  DCHECK_GE(height, 0);
  return static_cast<uint32>(height);
}

scoped_refptr<VideoPlaybackQuality> HTMLVideoElement::GetVideoPlaybackQuality(
    script::EnvironmentSettings* environment_settings) const {
  TRACE_EVENT0("cobalt::dom", "HTMLVideoElement::GetVideoPlaybackQuality()");
  DOMSettings* dom_settings =
      base::polymorphic_downcast<DOMSettings*>(environment_settings);
  DCHECK(dom_settings);
  DCHECK(dom_settings->window());
  DCHECK(dom_settings->window()->performance());

  if (player()) {
    auto player_stats = player()->GetStatistics();
    return new VideoPlaybackQuality(
        dom_settings->window()->performance()->Now(),
        static_cast<uint32>(player_stats.video_frames_decoded),
        static_cast<uint32>(player_stats.video_frames_dropped));
  } else {
    return new VideoPlaybackQuality(
        dom_settings->window()->performance()->Now(), 0, 0);
  }
}

scoped_refptr<DecodeTargetProvider> HTMLVideoElement::GetDecodeTargetProvider(
    bool* paint_to_black) {
  DCHECK_CALLED_ON_VALID_THREAD(thread_checker_);
  DCHECK(paint_to_black);

  *paint_to_black = GetMediaSettings(environment_settings())
                        .IsPaintingVideoBackgroundToBlack()
                        .value_or(false);

  return player() ? player()->GetDecodeTargetProvider() : NULL;
}

WebMediaPlayer::SetBoundsCB HTMLVideoElement::GetSetBoundsCB() {
  return player() ? player()->GetSetBoundsCB() : WebMediaPlayer::SetBoundsCB();
}

math::SizeF HTMLVideoElement::GetVideoSize() const {
  return math::SizeF(video_width(), video_height());
}

}  // namespace dom
}  // namespace cobalt

bool HTMLVideoElement::IsVideoPlaybackFitToFillEnabled() const {
  return GetMediaSettings(environment_settings())
      .IsVideoPlaybackFitToFillEnabled()
      .value_or(false);
}
