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

#include "third_party/blink/renderer/modules/cobalt/h5vcc_tizen_tube/h_5_vcc_tizen_tube.h"

#include "third_party/blink/public/platform/browser_interface_broker_proxy.h"
#include "third_party/blink/renderer/bindings/core/v8/script_promise.h"
#include "third_party/blink/renderer/bindings/core/v8/script_promise_resolver.h"
#include "third_party/blink/renderer/bindings/core/v8/script_promise.h"
#include "third_party/blink/renderer/bindings/core/v8/script_promise_resolver.h"
#include "third_party/blink/renderer/core/dom/dom_exception.h"
#include "third_party/blink/renderer/core/execution_context/execution_context.h"
#include "third_party/blink/renderer/core/frame/local_dom_window.h"
#include "third_party/blink/renderer/core/frame/local_frame.h"
#include "third_party/blink/renderer/platform/bindings/exception_state.h"
#include "third_party/blink/renderer/platform/weborigin/security_origin.h"
#include "third_party/blink/renderer/platform/wtf/functional.h"
#include "third_party/blink/renderer/platform/wtf/text/wtf_string.h"
#if BUILDFLAG(IS_ANDROID)
#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "starboard/android/shared/starboard_bridge.h"
#endif  // BUILDFLAG(IS_ANDROID)

namespace blink {

H5vccTizenTube::H5vccTizenTube(LocalDOMWindow& window)
    : ExecutionContextLifecycleObserver(window.GetExecutionContext()),
      service_(window.GetExecutionContext()) {}

void H5vccTizenTube::ContextDestroyed() {
  service_.reset();
}

void H5vccTizenTube::OnConnectionError() {
  service_.reset();
}

WTF::String H5vccTizenTube::GetVersion(
    ScriptState* script_state,
    ExceptionState& exception_state) {
#if BUILDFLAG(IS_ANDROID)
  auto* env = base::android::AttachCurrentThread();
  starboard::StarboardBridge* bridge = starboard::StarboardBridge::GetInstance();
  std::string version =
      bridge->GetVersion(env);
  return String::FromUTF8(version.c_str());
#else
  return "";
#endif // BUILDFLAG(IS_ANDROID)
}

WTF::String H5vccTizenTube::GetArchitecture(
    ScriptState* script_state,
    ExceptionState& exception_state) {
#if BUILDFLAG(IS_ANDROID)
  auto* env = base::android::AttachCurrentThread();
  starboard::StarboardBridge* bridge = starboard::StarboardBridge::GetInstance();
  std::string architecture =
      bridge->GetArchitecture(env);
  return String::FromUTF8(architecture.c_str());
#else
  return "Unknown";
#endif // BUILDFLAG(IS_ANDROID)
}

bool H5vccTizenTube::InstallAppFromURL(
    ScriptState* script_state,
    const String& url,
    ExceptionState& exception_state) {
#if BUILDFLAG(IS_ANDROID)
  auto* env = base::android::AttachCurrentThread();
  starboard::StarboardBridge* bridge = starboard::StarboardBridge::GetInstance();
  bridge->InstallAppFromURL(env, url.Utf8().data());
  return true;
#else
  return false;
#endif // BUILDFLAG(IS_ANDROID)
}

void H5vccTizenTube::SetFrameRate(float frame_rate) {
#if BUILDFLAG(IS_ANDROID)
  auto* env = base::android::AttachCurrentThread();
  starboard::StarboardBridge* bridge = starboard::StarboardBridge::GetInstance();
  bridge->SetFrameRate(env, frame_rate);
#endif // BUILDFLAG(IS_ANDROID)
}

void H5vccTizenTube::EnterPIP() {
  #if BUILDFLAG(IS_ANDROID)
    auto * env = base::android::AttachCurrentThread();
    starboard::StarboardBridge* bridge = starboard::StarboardBridge::GetInstance();
    bridge->EnterPIP(env);
  #endif // BUILDFLAG(IS_ANDROID)
}

bool H5vccTizenTube::EnsureReceiverIsBound() {
  if (service_.is_bound()) {
    return true;
  }

  auto* execution_context = GetExecutionContext();
  if (!execution_context) {
    return false;
  }

  auto task_runner = execution_context->GetTaskRunner(TaskType::kMiscPlatformAPI);
  execution_context->GetBrowserInterfaceBroker().GetInterface(
      service_.BindNewPipeAndPassReceiver(task_runner));
  service_.set_disconnect_handler(WTF::BindOnce(
      &H5vccTizenTube::OnConnectionError, WrapWeakPersistent(this)));
  return service_.is_bound();
}

bool H5vccTizenTube::SetUserAgent(
    ScriptState* script_state,
    const WTF::String& user_agent,
    ExceptionState& exception_state) {
  if (user_agent.IsNull() || user_agent.length() == 0) {
    exception_state.ThrowTypeError("User agent cannot be empty");
    return false;
  }

  if (!EnsureReceiverIsBound()) {
    exception_state.ThrowDOMException(DOMExceptionCode::kNotSupportedError,
                                      "Unable to connect to h5vcc_tizentube service");
    return false;
  }

  service_->SetUserAgent(user_agent.Utf8().data());
  return true;
}

bool H5vccTizenTube::HasSystemFeature(
    ScriptState* script_state,
    const WTF::String& feature_name,
    ExceptionState& exception_state) {
  if (feature_name.IsNull() || feature_name.length() == 0) {
    exception_state.ThrowTypeError("Feature name cannot be empty");
    return false;
  }

  #if BUILDFLAG(IS_ANDROID)
    auto* env = base::android::AttachCurrentThread();
    starboard::StarboardBridge* bridge = starboard::StarboardBridge::GetInstance();
    bool has_feature = bridge->HasSystemFeature(env, feature_name.Utf8().data());
    return has_feature;
  #else
  return false;
  #endif // BUILDFLAG(IS_ANDROID)
}

void H5vccTizenTube::ShowAlertDialog(
    const WTF::String& title,
    const WTF::String& message) {
  if (title.IsNull() || title.length() == 0) {
    return;
  }

  if (message.IsNull() || message.length() == 0) {
    return;
  }

  #if BUILDFLAG(IS_ANDROID)
    auto* env = base::android::AttachCurrentThread();
    starboard::StarboardBridge* bridge = starboard::StarboardBridge::GetInstance();
    bridge->ShowAlertDialog(env, title.Utf8().data(), message.Utf8().data());
  #endif // BUILDFLAG(IS_ANDROID)
}

void H5vccTizenTube::PublishHomeScreen(const WTF::String& json) {
  // Same cap as the Java side; anything bigger is not a payload we sent.
  if (json.IsNull() || json.length() == 0 || json.length() > 512 * 1024) {
    return;
  }

  // Only the YouTube page this app runs, in its top-level frame over HTTPS on the default port,
  // may put things on the home screen. ToString() is the serialised origin, e.g.
  // "https://www.youtube.com", so scheme, host and port must all match.
  auto* window = DynamicTo<LocalDOMWindow>(GetExecutionContext());
  if (!window || !window->GetFrame() || !window->GetFrame()->IsMainFrame()) {
    return;
  }
  const WTF::String origin = window->GetSecurityOrigin()->ToString();
  if (origin != "https://www.youtube.com" && origin != "https://youtube.com" &&
      origin != "https://m.youtube.com") {
    return;
  }

  #if BUILDFLAG(IS_ANDROID)
    auto* env = base::android::AttachCurrentThread();
    starboard::StarboardBridge* bridge = starboard::StarboardBridge::GetInstance();
    bridge->PublishHomeScreen(env, json.Utf8().data());
  #endif // BUILDFLAG(IS_ANDROID)
}

void H5vccTizenTube::Trace(Visitor* visitor) const {
  visitor->Trace(service_);
  ScriptWrappable::Trace(visitor);
  ExecutionContextLifecycleObserver::Trace(visitor);
}

}  // namespace blink
