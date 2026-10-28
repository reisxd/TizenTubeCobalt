// Copyright 2026 The Cobalt Authors. All Rights Reserved.
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

#include "base/android/jni_array.h"
#include "base/android/jni_string.h"
#include "base/strings/string_number_conversions.h"
#include "build/build_config.h"
#include "starboard/android/shared/application_android.h"
#include "starboard/android/shared/file_internal.h"
#include "starboard/android/shared/log_internal.h"
#include "starboard/android/shared/starboard_bridge.h"
#include "starboard/common/command_line.h"
#include "starboard/common/log.h"
#include "starboard/common/time.h"
#include "starboard/shared/starboard/application.h"
#include "starboard/shared/starboard/audio_sink/audio_sink_internal.h"
#include "third_party/jni_zero/jni_zero.h"

// TODO(b/492704919): enable on AOSP when the layering violation is fixed.
#if !BUILDFLAG(IS_PARTNER_TOOLCHAIN)
#include "cobalt/browser/client_hint_headers/cobalt_header_value_provider.h"
#include "cobalt/browser/h5vcc_runtime/deep_link_manager.h"
#endif  // !BUILDFLAG(IS_PARTNER_TOOLCHAIN)

// Must come after all headers that specialize FromJniType() / ToJniType().
#include "cobalt/android/jni_headers/BaseStarboardBridge_jni.h"

namespace starboard {

namespace {

using base::android::AppendJavaStringArrayToStringVector;
using base::android::ConvertJavaStringToUTF8;
using base::android::ConvertUTF8ToJavaString;
using jni_zero::AttachCurrentThread;
using jni_zero::GetClass;
using jni_zero::JavaParamRef;
using jni_zero::ScopedJavaGlobalRef;
using jni_zero::ScopedJavaLocalRef;

// Global pointer to hold the single instance of ApplicationAndroid.
ApplicationAndroid* g_native_app_instance = nullptr;
static pthread_mutex_t g_native_app_init_mutex PTHREAD_MUTEX_INITIALIZER;

std::vector<std::string> GetArgs() {
  std::vector<std::string> args;
  // Fake program name as args[0]
  args.push_back("android_main");

  JNIEnv* env = AttachCurrentThread();
  StarboardBridge::GetInstance()->AppendArgs(env, &args);

  return args;
}

}  // namespace

jboolean JNI_BaseStarboardBridge_InitJNI(
    JNIEnv* env,
    const JavaParamRef<jobject>& j_starboard_bridge) {
  SB_CHECK(env);

  // Initialize the singleton instance of StarboardBridge
  StarboardBridge::GetInstance()->Initialize(env, j_starboard_bridge.obj());

  StarboardBridge::GetInstance()->SetStartupMilestone(5);
  return true;
}

jlong JNI_BaseStarboardBridge_CurrentMonotonicTime(JNIEnv* env) {
  return CurrentMonotonicTime();
}

jlong JNI_BaseStarboardBridge_StartNativeStarboard(
    JNIEnv* env,
    const JavaParamRef<jobject>& j_asset_manager,
    const JavaParamRef<jstring>& j_files_dir,
    const JavaParamRef<jstring>& j_cache_dir,
    const JavaParamRef<jstring>& j_native_library_dir) {
  pthread_mutex_lock(&g_native_app_init_mutex);
  if (g_native_app_instance == nullptr) {
    auto command_line = std::make_unique<CommandLine>(GetArgs());
    LogInit(*command_line);
    ScopedJavaGlobalRef<jobject> asset_manager(env, j_asset_manager.obj());
#if BUILDFLAG(IS_STARBOARD)
    // Just initialize file_internal directories and asset manager here,
    // ApplicationAOSP will be created in SbRunStarboardMain
    SbFileAndroidInitialize(std::move(asset_manager),
                            ConvertJavaStringToUTF8(env, j_files_dir),
                            ConvertJavaStringToUTF8(env, j_cache_dir),
                            ConvertJavaStringToUTF8(env, j_native_library_dir));
#else
    g_native_app_instance = new ApplicationAndroid(
        std::move(command_line), std::move(asset_manager),
        ConvertJavaStringToUTF8(env, j_files_dir),
        ConvertJavaStringToUTF8(env, j_cache_dir),
        ConvertJavaStringToUTF8(env, j_native_library_dir));
#endif
  }
  pthread_mutex_unlock(&g_native_app_init_mutex);
  return reinterpret_cast<jlong>(g_native_app_instance);
}

void JNI_BaseStarboardBridge_CloseNativeStarboard(JNIEnv* env,
                                                  jlong nativeApp) {
  auto* app = reinterpret_cast<ApplicationAndroid*>(nativeApp);
  delete app;
}

void JNI_BaseStarboardBridge_InitializePlatformAudioSink(JNIEnv* env) {
  SbAudioSinkImpl::Initialize();
}

void JNI_BaseStarboardBridge_HandleDeepLink(JNIEnv* env,
                                            const JavaParamRef<jstring>& jurl,
                                            jboolean applicationStarted) {
  const std::string& url = ConvertJavaStringToUTF8(env, jurl);

#if BUILDFLAG(IS_STARBOARD)
  // AOSP can't depend on cobalt::browser::DeepLinkManager directly (see
  // b/492704919), so route the warm-start case through Application::Link()
  // instead, which cobalt/app already forwards to DeepLinkManager on every
  // platform. This function also runs once for cold start (from
  // BaseStarboardBridge's constructor, with applicationStarted always
  // false), but that call happens before Application even exists, so it's
  // a no-op here by construction. MainActivity instead passes the link as
  // a "--link=<url>" startup argument, which Application::Run() already
  // threads to DeepLinkManager before the app is considered started.
  if (applicationStarted && !url.empty()) {
    Application::Get()->Link(url.c_str());
  }
#else
  // Non-AOSP targets can depend on cobalt/browser directly, so they still
  // call DeepLinkManager here. Whether this stays the long-term shape for
  // AOSP too, or AOSP's event-based path above becomes the general pattern
  // instead, is still an open question.
  auto* manager = cobalt::browser::DeepLinkManager::GetInstance();
  if (applicationStarted) {
    manager->OnDeepLink(url);
  } else {
    manager->set_deep_link(url);
  }
#endif  // BUILDFLAG(IS_STARBOARD)
}

void JNI_BaseStarboardBridge_SetAndroidOSExperience(JNIEnv* env,
                                                    jboolean isAmatiDevice) {
  // TODO(b/492704919): enable on AOSP when the layering violation is fixed.
#if !BUILDFLAG(IS_PARTNER_TOOLCHAIN)
  std::string value = isAmatiDevice ? "Amati" : "Watson";
  auto header_value_provider =
      cobalt::browser::CobaltHeaderValueProvider::GetInstance();
  header_value_provider->SetHeaderValue(
      cobalt::browser::kAndroidOSExperienceHeaderName, value);
#endif  // !BUILDFLAG(IS_PARTNER_TOOLCHAIN)
}

void JNI_BaseStarboardBridge_SetAndroidPlayServicesVersion(JNIEnv* env,
                                                           jlong version) {
  // TODO(b/492704919): enable on AOSP when the layering violation is fixed.
#if !BUILDFLAG(IS_PARTNER_TOOLCHAIN)
  auto header_value_provider =
      cobalt::browser::CobaltHeaderValueProvider::GetInstance();
  header_value_provider->SetHeaderValue(
      cobalt::browser::kPlayServicesVersionHeaderName,
      base::NumberToString(version));
#endif  // !BUILDFLAG(IS_PARTNER_TOOLCHAIN)
}

void JNI_BaseStarboardBridge_SetAndroidBuildFingerprint(
    JNIEnv* env,
    const JavaParamRef<jstring>& fingerprint) {
  // TODO(b/492704919): enable on AOSP when the layering violation is fixed.
#if !BUILDFLAG(IS_PARTNER_TOOLCHAIN)
  auto header_value_provider =
      cobalt::browser::CobaltHeaderValueProvider::GetInstance();
  header_value_provider->SetHeaderValue(
      cobalt::browser::kBuildFingerprintHeaderName,
      ConvertJavaStringToUTF8(env, fingerprint));
#endif  // !BUILDFLAG(IS_PARTNER_TOOLCHAIN)
}

void JNI_BaseStarboardBridge_SetYoutubeCertificationScope(
    JNIEnv* env,
    const JavaParamRef<jstring>& certScope) {
  // TODO(b/492704919): enable on AOSP when the layering violation is fixed.
#if !BUILDFLAG(IS_PARTNER_TOOLCHAIN)
  auto header_value_provider =
      cobalt::browser::CobaltHeaderValueProvider::GetInstance();
  header_value_provider->SetHeaderValue(
      cobalt::browser::kCobaltCertScopeHeaderName,
      ConvertJavaStringToUTF8(env, certScope));
#endif  // !BUILDFLAG(IS_PARTNER_TOOLCHAIN)
}

// StarboardBridge::GetInstance() should not be inlined in the
// header. This makes sure that when source files from multiple targets include
// this header they don't end up with different copies of the inlined code
// creating multiple copies of the singleton.
// static
SB_EXPORT_ANDROID StarboardBridge* StarboardBridge::GetInstance() {
  return base::Singleton<StarboardBridge>::get();
}

void StarboardBridge::Initialize(JNIEnv* env, jobject obj) {
  j_starboard_bridge_.Reset(env, obj);
}

int64_t StarboardBridge::GetAppStartTimestamp(JNIEnv* env) {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_getAppStartTimestamp(env,
                                                       j_starboard_bridge_);
}

void StarboardBridge::ApplicationStarted(JNIEnv* env) {
  SB_DCHECK(env);
  Java_BaseStarboardBridge_applicationStarted(env, j_starboard_bridge_);
}

void StarboardBridge::ApplicationStopping(JNIEnv* env) {
  SB_DCHECK(env);
  Java_BaseStarboardBridge_applicationStopping(env, j_starboard_bridge_);
}

void StarboardBridge::AppendArgs(JNIEnv* env,
                                 std::vector<std::string>* args_vector) {
  SB_DCHECK(env);
  ScopedJavaLocalRef<jobjectArray> args_java =
      Java_BaseStarboardBridge_getArgs(env, j_starboard_bridge_);
  AppendJavaStringArrayToStringVector(env, args_java, args_vector);
}

void StarboardBridge::RaisePlatformError(JNIEnv* env,
                                         jint errorType,
                                         jlong data,
                                         const std::string& url,
                                         bool disable_dismiss_button) {
  SB_DCHECK(env);
  Java_BaseStarboardBridge_raisePlatformError(
      env, j_starboard_bridge_, errorType, data,
      ConvertUTF8ToJavaString(env, url), disable_dismiss_button);
}

bool StarboardBridge::IsPlatformErrorShowing(JNIEnv* env) {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_isPlatformErrorShowing(env,
                                                         j_starboard_bridge_);
}

void StarboardBridge::RequestSuspend(JNIEnv* env) {
  SB_DCHECK(env);
  Java_BaseStarboardBridge_requestSuspend(env, j_starboard_bridge_);
}

ScopedJavaLocalRef<jobject> StarboardBridge::GetTextToSpeechHelper(
    JNIEnv* env) {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_getTextToSpeechHelper(env,
                                                        j_starboard_bridge_);
}

ScopedJavaLocalRef<jobject> StarboardBridge::GetCaptionSettings(JNIEnv* env) {
  SB_CHECK(env);
  return Java_BaseStarboardBridge_getCaptionSettings(env, j_starboard_bridge_);
}

ScopedJavaLocalRef<jobject> StarboardBridge::GetResourceOverlay(JNIEnv* env) {
  SB_CHECK(env);
  return Java_BaseStarboardBridge_getResourceOverlay(env, j_starboard_bridge_);
}

ScopedJavaLocalRef<jstring> StarboardBridge::GetSystemLocaleId(JNIEnv* env) {
  SB_CHECK(env);
  return Java_BaseStarboardBridge_systemGetLocaleId(env, j_starboard_bridge_);
}

SB_EXPORT_ANDROID std::string StarboardBridge::GetAdvertisingId(JNIEnv* env) {
  SB_DCHECK(env);
  ScopedJavaLocalRef<jstring> advertising_id_java =
      Java_BaseStarboardBridge_getAdvertisingId(env, j_starboard_bridge_);
  return ConvertJavaStringToUTF8(env, advertising_id_java);
}

SB_EXPORT_ANDROID bool StarboardBridge::GetLimitAdTracking(JNIEnv* env) {
  SB_DCHECK(env);
  jboolean limit_ad_tracking_java =
      Java_BaseStarboardBridge_getLimitAdTracking(env, j_starboard_bridge_);
  return limit_ad_tracking_java == JNI_TRUE;
}

SB_EXPORT_ANDROID std::string StarboardBridge::GetFriendlyName(JNIEnv* env) {
  SB_DCHECK(env);
  ScopedJavaLocalRef<jstring> friendly_name_java =
      Java_BaseStarboardBridge_getFriendlyName(env, j_starboard_bridge_);
  return ConvertJavaStringToUTF8(env, friendly_name_java);
}

SB_EXPORT_ANDROID double StarboardBridge::GetScreenDiagonal(JNIEnv* env) {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_getScreenDiagonal(env, j_starboard_bridge_);
}

SB_EXPORT_ANDROID bool StarboardBridge::GetWasLowMemoryKilled(JNIEnv* env) {
  SB_DCHECK(env);
  if (!j_starboard_bridge_) {
    return false;
  }
  return Java_BaseStarboardBridge_getWasLowMemoryKilled(
             env, j_starboard_bridge_) == JNI_TRUE;
}

SB_EXPORT_ANDROID void StarboardBridge::CloseApp(JNIEnv* env) {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_closeApp(env, j_starboard_bridge_);
}

void StarboardBridge::RequestStop(JNIEnv* env, jint error_level) {
  SB_CHECK(env);
  Java_BaseStarboardBridge_requestStop(env, j_starboard_bridge_, error_level);
}

std::string StarboardBridge::GetTimeZoneId(JNIEnv* env) {
  SB_DCHECK(env);
  ScopedJavaLocalRef<jstring> timezone_id_java =
      Java_BaseStarboardBridge_getTimeZoneId(env, j_starboard_bridge_);
  return ConvertJavaStringToUTF8(env, timezone_id_java);
}

ScopedJavaLocalRef<jobject> StarboardBridge::GetDisplayDpi(JNIEnv* env) {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_getDisplayDpi(env, j_starboard_bridge_);
}

Size StarboardBridge::GetDeviceResolution(JNIEnv* env) {
  SB_CHECK(env);
  ScopedJavaLocalRef<jobject> j_size =
      Java_BaseStarboardBridge_getDisplaySize(env, j_starboard_bridge_);
  return {Java_Size_getWidth(env, j_size), Java_Size_getHeight(env, j_size)};
}

void StarboardBridge::ReportFullyDrawn(JNIEnv* env) {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_reportFullyDrawn(env, j_starboard_bridge_);
}

void StarboardBridge::SetCrashContext(JNIEnv* env,
                                      const char* key,
                                      const char* value) {
  SB_CHECK(env);
  Java_BaseStarboardBridge_setCrashContext(env, j_starboard_bridge_,
                                           ConvertUTF8ToJavaString(env, key),
                                           ConvertUTF8ToJavaString(env, value));
}

bool StarboardBridge::IsMicrophoneDisconnected(JNIEnv* env) {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_isMicrophoneDisconnected(env,
                                                           j_starboard_bridge_);
}

bool StarboardBridge::IsMicrophoneMute(JNIEnv* env) {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_isMicrophoneMute(env, j_starboard_bridge_);
}

ScopedJavaLocalRef<jobject> StarboardBridge::GetAudioPermissionRequester(
    JNIEnv* env) {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_getAudioPermissionRequester(
      env, j_starboard_bridge_);
}

void StarboardBridge::ResetVideoSurface(JNIEnv* env) {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_resetVideoSurface(env, j_starboard_bridge_);
}

void StarboardBridge::SetVideoSurfaceBounds(JNIEnv* env,
                                            int x,
                                            int y,
                                            int width,
                                            int height) {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_setVideoSurfaceBounds(
      env, j_starboard_bridge_, x, y, width, height);
}

ScopedJavaLocalRef<jobject> StarboardBridge::GetAudioOutputManager(
    JNIEnv* env) {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_getAudioOutputManager(env,
                                                        j_starboard_bridge_);
}

std::string StarboardBridge::GetUserAgentAuxField(JNIEnv* env) const {
  SB_DCHECK(env);
  return ConvertJavaStringToUTF8(
      env,
      Java_BaseStarboardBridge_getUserAgentAuxField(env, j_starboard_bridge_));
}

bool StarboardBridge::IsAmatiDevice(JNIEnv* env) const {
  SB_DCHECK(env);
  return Java_BaseStarboardBridge_getIsAmatiDevice(env, j_starboard_bridge_) ==
         JNI_TRUE;
}

std::string StarboardBridge::GetBuildFingerprint(JNIEnv* env) const {
  SB_DCHECK(env);
  return ConvertJavaStringToUTF8(
      env,
      Java_BaseStarboardBridge_getBuildFingerprint(env, j_starboard_bridge_));
}

int64_t StarboardBridge::GetPlayServicesVersion(JNIEnv* env) const {
  SB_DCHECK(env);
  return static_cast<int64_t>(Java_BaseStarboardBridge_getPlayServicesVersion(
      env, j_starboard_bridge_));
}

ScopedJavaLocalRef<jobject> StarboardBridge::OpenCobaltService(
    JNIEnv* env,
    jlong native_service,
    const char* service_name) {
  SB_CHECK(env);
  return Java_BaseStarboardBridge_openCobaltService(
      env, j_starboard_bridge_, native_service,
      ConvertUTF8ToJavaString(env, service_name));
}

void StarboardBridge::CloseCobaltService(JNIEnv* env, jlong native_service) {
  SB_CHECK(env);
  Java_BaseStarboardBridge_closeCobaltService(env, j_starboard_bridge_,
                                              native_service);
}

bool StarboardBridge::HasCobaltService(JNIEnv* env, const char* service_name) {
  SB_CHECK(env);
  return Java_BaseStarboardBridge_hasCobaltService(
      env, j_starboard_bridge_, ConvertUTF8ToJavaString(env, service_name));
}

void StarboardBridge::CloseAllCobaltService(JNIEnv* env) const {
  SB_DCHECK(env);
  Java_BaseStarboardBridge_closeAllCobaltService(env, j_starboard_bridge_);
}

void StarboardBridge::HideSplashScreen(JNIEnv* env) const {
  SB_DCHECK(env);
  Java_BaseStarboardBridge_hideSplashScreen(env, j_starboard_bridge_);
}

// TizenTube Cobalt APIs
bool StarboardBridge::InstallAppFromURL(JNIEnv* env, const char* url) const {
  SB_CHECK(env);
  return Java_BaseStarboardBridge_installAppFromURL(
      env, j_starboard_bridge_, ConvertUTF8ToJavaString(env, url));
}

void StarboardBridge::SetStartupMilestone(jint milestone) const {
  JNIEnv* env = AttachCurrentThread();
  Java_BaseStarboardBridge_setStartupMilestone(env, j_starboard_bridge_,
                                               milestone);
}

std::string StarboardBridge::GetVersion(JNIEnv* env) const {
  SB_CHECK(env);
  return ConvertJavaStringToUTF8(
      env, Java_BaseStarboardBridge_getVersion(env, j_starboard_bridge_));
}

std::string StarboardBridge::GetArchitecture(JNIEnv* env) const {
  SB_CHECK(env);
  return ConvertJavaStringToUTF8(
      env, Java_BaseStarboardBridge_getArchitecture(env, j_starboard_bridge_));
}

std::string StarboardBridge::GetBrandAndModel(JNIEnv* env) const {
  SB_CHECK(env);
  return ConvertJavaStringToUTF8(
      env, Java_BaseStarboardBridge_getBrandAndModel(env, j_starboard_bridge_));
}

void StarboardBridge::SetFrameRate(JNIEnv* env, float frame_rate) const {
  SB_CHECK(env);
  Java_BaseStarboardBridge_setFrameRate(env, j_starboard_bridge_, frame_rate);
}

void StarboardBridge::EnterPIP(JNIEnv* env) const {
  SB_CHECK(env);
  Java_BaseStarboardBridge_enterPIP(env, j_starboard_bridge_);
}

bool StarboardBridge::HasSystemFeature(JNIEnv* env, const char* feature_name) const {
  SB_CHECK(env);
  return Java_BaseStarboardBridge_hasSystemFeature(
      env, j_starboard_bridge_, ConvertUTF8ToJavaString(env, feature_name));
}

void StarboardBridge::ShowAlertDialog(JNIEnv* env,
                                       const char* title,
                                       const char* message) const {
  SB_CHECK(env);
  Java_BaseStarboardBridge_showAlertDialog(
      env, j_starboard_bridge_, ConvertUTF8ToJavaString(env, title),
      ConvertUTF8ToJavaString(env, message));
}

void StarboardBridge::PublishHomeScreen(JNIEnv* env, const char* json) const {
  SB_CHECK(env);
  Java_BaseStarboardBridge_publishHomeScreen(env, j_starboard_bridge_,
                                             ConvertUTF8ToJavaString(env, json));
}

void StarboardBridge::SetStartupDiagnosisInfo(const char* key,
                                              const char* value) const {
  JNIEnv* env = AttachCurrentThread();
  Java_BaseStarboardBridge_setStartupDiagnosisInfo(
      env, j_starboard_bridge_, ConvertUTF8ToJavaString(env, key),
      ConvertUTF8ToJavaString(env, value));
}

}  // namespace starboard
