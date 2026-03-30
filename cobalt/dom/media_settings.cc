// Copyright 2022 The Cobalt Authors. All Rights Reserved.
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

#include "cobalt/dom/media_settings.h"

#include <cstring>

#include "base/logging.h"

namespace cobalt {
namespace dom {

bool MediaSettingsImpl::Set(const std::string& name, int value) {
  const char* kPrefixes[] = {"MediaElement.", "MediaSource."};
  if (name.compare(0, strlen(kPrefixes[0]), kPrefixes[0]) != 0 &&
      name.compare(0, strlen(kPrefixes[1]), kPrefixes[1]) != 0) {
    return false;
  }

  base::AutoLock auto_lock(lock_);
  if (name == "MediaSource.SourceBufferEvictExtraInBytes") {
    if (value >= 0) {
      source_buffer_evict_extra_in_bytes_ = value;
      LOG(INFO) << name << ": set to " << value;
      return true;
    }
  } else if (name == "MediaSource.MinimumProcessorCountToOffloadAlgorithm") {
    if (value >= 0) {
      minimum_processor_count_to_offload_algorithm_ = value;
      LOG(INFO) << name << ": set to " << value;
      return true;
    }
  } else if (name == "MediaSource.EnableAsynchronousReduction") {
    if (value == 0 || value == 1) {
      is_asynchronous_reduction_enabled_ = value != 0;
      LOG(INFO) << name << ": set to " << value;
      return true;
    }
  } else if (name == "MediaSource.EnableAvoidCopyingArrayBuffer") {
    if (value == 0 || value == 1) {
      is_avoid_copying_array_buffer_enabled_ = value != 0;
      LOG(INFO) << name << ": set to " << value;
      return true;
    }
  } else if (name == "MediaSource.EnableCallingEndedWhenClosed") {
    if (value == 0 || value == 1) {
      is_calling_ended_when_closed_enabled_ = value != 0;
      LOG(INFO) << name << ": set to " << value;
      return true;
    }
  } else if (name == "MediaSource.MaxSizeForImmediateJob") {
    if (value >= 0) {
      max_size_for_immediate_job_ = value;
      LOG(INFO) << name << ": set to " << value;
      return true;
    }
  } else if (name == "MediaSource.MaxSourceBufferAppendSizeInBytes") {
    if (value > 0) {
      max_source_buffer_append_size_in_bytes_ = value;
      LOG(INFO) << name << ": set to " << value;
      return true;
    }
  } else if (name == "MediaElement.TimeupdateEventIntervalInMilliseconds") {
    if (value > 0) {
      media_element_timeupdate_event_interval_in_milliseconds_ = value;
      LOG(INFO) << name << ": set to " << value;
      return true;
    }
  } else if (name == "MediaElement.PaintingVideoBackgroundToBlack") {
    if (value == 0 || value == 1) {
      is_painting_video_background_to_black_ = value != 0;
      LOG(INFO) << name << ": set to " << value;
      return true;
    }
  } else if (name == "MediaElement.VideoPlaybackFitToFillEnabled") {
    if (value == 0 || value == 1) {
      is_video_playback_fit_to_fill_enabled_ = value != 0;
      LOG(INFO) << name << ": set to " << value;
      return true;
    }
  } else if (name == "MediaElement.EnableUsingMediaSourceBufferedRange") {
    if (value == 0 || value == 1) {
      is_media_element_using_media_source_buffered_range_enabled_ = value != 0;
      LOG(INFO) << name << ": set to " << value;
      return true;
    }
  } else if (name == "MediaElement.EnableUsingMediaSourceAttachmentMethods") {
    if (value == 0 || value == 1) {
      is_media_element_using_media_source_attachment_methods_enabled_ =
          value != 0;
      LOG(INFO) << name << ": set to " << value;
      return true;
    }
  } else if (name == "MediaSource.EnableInWorkers") {
    if (value == 0 || value == 1) {
      is_mse_in_workers_enabled_ = value != 0;
      LOG(INFO) << name << ": set to " << value;
      return true;
    }
  } else {
    LOG(WARNING) << "Ignore unknown setting with name \"" << name << "\"";
    return false;
  }
  LOG(WARNING) << name << ": ignore invalid value " << value;
  return false;
}

}  // namespace dom
}  // namespace cobalt
