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

#ifndef THIRD_PARTY_BLINK_RENDERER_MODULES_COBALT_H5VCC_TIZENTUBE_H_5_VCC_TIZENTUBE_H_
#define THIRD_PARTY_BLINK_RENDERER_MODULES_COBALT_H5VCC_TIZENTUBE_H_5_VCC_TIZENTUBE_H_

#include <optional>

#include "cobalt/browser/h5vcc_tizentube/public/mojom/h5vcc_tizentube.mojom-blink.h"
#include "third_party/blink/renderer/bindings/core/v8/idl_types.h"
#include "third_party/blink/renderer/bindings/core/v8/script_promise.h"
#include "third_party/blink/renderer/bindings/core/v8/script_promise_resolver.h"
#include "third_party/blink/renderer/bindings/core/v8/v8_union_long_string.h"
#include "third_party/blink/renderer/core/execution_context/execution_context_lifecycle_observer.h"
#include "third_party/blink/renderer/modules/modules_export.h"
#include "third_party/blink/renderer/platform/bindings/script_wrappable.h"
#include "third_party/blink/renderer/platform/heap/collection_support/heap_hash_set.h"
#include "third_party/blink/renderer/platform/mojo/heap_mojo_remote.h"

namespace blink {

class ExceptionState;
class LocalDOMWindow;
class ScriptState;

class MODULES_EXPORT H5vccTizenTube final
    : public ScriptWrappable,
      public ExecutionContextLifecycleObserver {
  DEFINE_WRAPPERTYPEINFO();

 public:
  explicit H5vccTizenTube(LocalDOMWindow&);

  void ContextDestroyed() override;

  // Web-exposed interface:

  bool InstallAppFromURL(
      ScriptState* script_state, const WTF::String& url,
      ExceptionState& exception_state);

  WTF::String GetVersion(ScriptState* script_state,
                                      ExceptionState& exception_state);

  WTF::String GetArchitecture(ScriptState* script_state,
                             ExceptionState& exception_state);

  void SetFrameRate(float frame_rate);
  void EnterPIP();
  bool SetUserAgent(ScriptState* script_state,
                    const WTF::String& user_agent,
                    ExceptionState& exception_state);
  bool HasSystemFeature(ScriptState* script_state,
                        const WTF::String& feature_name,
                        ExceptionState& exception_state);
  void ShowAlertDialog(const WTF::String& title,
                        const WTF::String& message);
  // Rows and Continue Watching for the Android TV home screen (JSON, see HomeChannels.java).
  void PublishHomeScreen(const WTF::String& json);

  bool EnsureReceiverIsBound();

  HeapMojoRemote<h5vcc_tizentube::mojom::blink::H5vccTizentube> service_;

  void OnConnectionError();

  // Trace method.
  void Trace(Visitor*) const override;

 private:

};

}  // namespace blink

#endif  // THIRD_PARTY_BLINK_RENDERER_MODULES_COBALT_H5VCC_TIZENTUBE_H_5_VCC_TIZENTUBE_H_
