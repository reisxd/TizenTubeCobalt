// Copyright 2026 Reis Can (reisxd). All Rights Reserved.
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

#include "cobalt/browser/h5vcc_tizentube/h5vcc_tizentube_impl.h"

#if BUILDFLAG(IS_ANDROID)
#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "starboard/android/shared/starboard_bridge.h"
#endif  // BUILDFLAG(IS_ANDROID)

#include <string>
#include <variant>

#include "cobalt/browser/global_features.h"

namespace h5vcc_tizentube {
#if BUILDFLAG(IS_ANDROID)
using base::android::AttachCurrentThread;
using starboard::StarboardBridge;
#endif

// static
void H5vccTizentubeImpl::Create(
    content::RenderFrameHost* render_frame_host,
    mojo::PendingReceiver<mojom::H5vccTizentube> receiver) {
  new H5vccTizentubeImpl(*render_frame_host, std::move(receiver));
}

H5vccTizentubeImpl::H5vccTizentubeImpl(
    content::RenderFrameHost& render_frame_host,
    mojo::PendingReceiver<mojom::H5vccTizentube> receiver)
    : content::DocumentService<mojom::H5vccTizentube>(render_frame_host,
                                                      std::move(receiver)) {}

void H5vccTizentubeImpl::InstallAppFromURL(const std::string& url, InstallAppFromURLCallback callback) {
  // Disables automatic updates completely by always returning false
  std::move(callback).Run(false);
  return;
}

void H5vccTizentubeImpl::GetVersion(GetVersionCallback callback) {
  // Implementation for getting version
  #if BUILDFLAG(IS_ANDROID)
    JNIEnv* env = AttachCurrentThread();
    std::string version = StarboardBridge::GetInstance()->GetVersion(env);
    std::move(callback).Run(version);
    return;
  #endif  // BUILDFLAG(IS_ANDROID)
  std::move(callback).Run("1.0.0"); // Placeholder implementation
}

void H5vccTizentubeImpl::GetArchitecture(GetArchitectureCallback callback) {
  // Implementation for getting architecture
  #if BUILDFLAG(IS_ANDROID)
    JNIEnv* env = AttachCurrentThread();
    std::string architecture = StarboardBridge::GetInstance()->GetArchitecture(env);
    std::move(callback).Run(architecture);
    return;
  #endif  // BUILDFLAG(IS_ANDROID)
  std::move(callback).Run("x86_64"); // Placeholder implementation
}

void H5vccTizentubeImpl::GetBrandAndModel(GetBrandAndModelCallback callback) {
  // Implementation for getting brand and model
  #if BUILDFLAG(IS_ANDROID)
    JNIEnv* env = AttachCurrentThread();
    std::string brand_and_model = StarboardBridge::GetInstance()->GetBrandAndModel(env);
    std::move(callback).Run(brand_and_model);
    return;
  #endif  // BUILDFLAG(IS_ANDROID)
  std::move(callback).Run("Example Brand Example Model"); // Placeholder implementation
}

void H5vccTizentubeImpl::SetFrameRate(float frame_rate) {
  #if BUILDFLAG(IS_ANDROID)
    JNIEnv* env = AttachCurrentThread();
    StarboardBridge::GetInstance()->SetFrameRate(env, frame_rate);
  #endif  // BUILDFLAG(IS_ANDROID)
  // Implementation for setting frame rate
}

void H5vccTizentubeImpl::SetUserAgent(const std::string& user_agent) {
  cobalt::GlobalFeatures::GetInstance()->SetSettings(
      "user_agent_override", user_agent);
}

}  // namespace h5vcc_tizentube
