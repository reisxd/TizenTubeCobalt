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

#ifndef COBALT_DOM_MEDIA_SETTINGS_H_
#define COBALT_DOM_MEDIA_SETTINGS_H_

#include <string>

#include "base/optional.h"
#include "base/synchronization/lock.h"

namespace cobalt {
namespace dom {

// Holds browser wide settings for media related objects.  Their default values
// are set in related implementations, and the default values will be overridden
// if the return values of the member functions are non-empty.
// Please refer to where these functions are called for the particular
// behaviors being controlled by them.
class MediaSettings {
 public:
  virtual base::Optional<int> GetSourceBufferEvictExtraInBytes() const = 0;
  virtual base::Optional<int> GetMinimumProcessorCountToOffloadAlgorithm()
      const = 0;
  virtual base::Optional<bool> IsAsynchronousReductionEnabled() const = 0;
  virtual base::Optional<bool> IsAvoidCopyingArrayBufferEnabled() const = 0;
  virtual base::Optional<bool> IsCallingEndedWhenClosedEnabled() const = 0;
  virtual base::Optional<int> GetMaxSizeForImmediateJob() const = 0;
  virtual base::Optional<int> GetMaxSourceBufferAppendSizeInBytes() const = 0;

  virtual base::Optional<int>
  GetMediaElementTimeupdateEventIntervalInMilliseconds() const = 0;
  virtual base::Optional<bool> IsPaintingVideoBackgroundToBlack() const = 0;
  virtual base::Optional<bool> IsVideoPlaybackFitToFillEnabled() const = 0;
  virtual base::Optional<bool>
  IsMediaElementUsingMediaSourceBufferedRangeEnabled() const = 0;
  virtual base::Optional<bool>
  IsMediaElementUsingMediaSourceAttachmentMethodsEnabled() const = 0;
  virtual base::Optional<bool> IsMseInWorkersEnabled() const = 0;

 protected:
  MediaSettings() = default;
  ~MediaSettings() = default;

  MediaSettings(const MediaSettings&) = delete;
  MediaSettings& operator=(const MediaSettings&) = delete;
};

// Allows setting the values of MediaSource settings via a name and an int
// value.
// This class is thread safe.
class MediaSettingsImpl : public MediaSettings {
 public:
  base::Optional<int> GetSourceBufferEvictExtraInBytes() const override {
    base::AutoLock auto_lock(lock_);
    return source_buffer_evict_extra_in_bytes_;
  }
  base::Optional<int> GetMinimumProcessorCountToOffloadAlgorithm()
      const override {
    base::AutoLock auto_lock(lock_);
    return minimum_processor_count_to_offload_algorithm_;
  }
  base::Optional<bool> IsAsynchronousReductionEnabled() const override {
    base::AutoLock auto_lock(lock_);
    return is_asynchronous_reduction_enabled_;
  }
  base::Optional<bool> IsAvoidCopyingArrayBufferEnabled() const override {
    base::AutoLock auto_lock(lock_);
    return is_avoid_copying_array_buffer_enabled_;
  }
  base::Optional<bool> IsCallingEndedWhenClosedEnabled() const override {
    base::AutoLock auto_lock(lock_);
    return is_calling_ended_when_closed_enabled_;
  }
  base::Optional<int> GetMaxSizeForImmediateJob() const override {
    base::AutoLock auto_lock(lock_);
    return max_size_for_immediate_job_;
  }
  base::Optional<int> GetMaxSourceBufferAppendSizeInBytes() const override {
    base::AutoLock auto_lock(lock_);
    return max_source_buffer_append_size_in_bytes_;
  }

  base::Optional<int> GetMediaElementTimeupdateEventIntervalInMilliseconds()
      const override {
    return media_element_timeupdate_event_interval_in_milliseconds_;
  }
  base::Optional<bool> IsPaintingVideoBackgroundToBlack() const override {
    return is_painting_video_background_to_black_;
  }
  base::Optional<bool> IsVideoPlaybackFitToFillEnabled() const override {
    return is_video_playback_fit_to_fill_enabled_;
  }
  base::Optional<bool> IsMediaElementUsingMediaSourceBufferedRangeEnabled()
      const override {
    base::AutoLock auto_lock(lock_);
    return is_media_element_using_media_source_buffered_range_enabled_;
  }
  base::Optional<bool> IsMediaElementUsingMediaSourceAttachmentMethodsEnabled()
      const override {
    base::AutoLock auto_lock(lock_);
    return is_media_element_using_media_source_attachment_methods_enabled_;
  }
  base::Optional<bool> IsMseInWorkersEnabled() const override {
    base::AutoLock auto_lock(lock_);
    return is_mse_in_workers_enabled_;
  }

  // Returns true when the setting associated with `name` is set to `value`.
  // Returns false when `name` is not associated with any settings, or if
  // `value` contains an invalid value.
  bool Set(const std::string& name, int value);

 private:
  mutable base::Lock lock_;
  base::Optional<int> source_buffer_evict_extra_in_bytes_;
  base::Optional<int> minimum_processor_count_to_offload_algorithm_;
  base::Optional<bool> is_asynchronous_reduction_enabled_;
  base::Optional<bool> is_avoid_copying_array_buffer_enabled_;
  base::Optional<bool> is_calling_ended_when_closed_enabled_;
  base::Optional<int> max_size_for_immediate_job_;
  base::Optional<int> max_source_buffer_append_size_in_bytes_;

  base::Optional<int> media_element_timeupdate_event_interval_in_milliseconds_;
  base::Optional<bool>
      is_media_element_using_media_source_buffered_range_enabled_;
  base::Optional<bool>
      is_media_element_using_media_source_attachment_methods_enabled_;
  base::Optional<bool> is_mse_in_workers_enabled_;

  base::Optional<bool> is_painting_video_background_to_black_;
  base::Optional<bool> is_video_playback_fit_to_fill_enabled_;
};

}  // namespace dom
}  // namespace cobalt

#endif  // COBALT_DOM_MEDIA_SETTINGS_H_
