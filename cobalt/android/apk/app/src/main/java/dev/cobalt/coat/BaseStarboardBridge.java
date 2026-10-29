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

package dev.cobalt.coat;

import static android.content.Context.AUDIO_SERVICE;
import static android.media.AudioManager.GET_DEVICES_INPUTS;
import static dev.cobalt.util.Log.TAG;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.app.Service;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Rational;
import android.view.Surface;
import android.view.Display;
import android.view.InputDevice;
import android.view.Surface;
import android.view.accessibility.CaptioningManager;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import dev.cobalt.media.AudioOutputManager;
import dev.cobalt.media.VideoSurfaceView;
import dev.cobalt.util.DisplayUtil;
import dev.cobalt.util.Holder;
import dev.cobalt.util.Log;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.chromium.base.CommandLine;
import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;

/** Implementation of the required JNI methods called by the Starboard C++ code. */
@JNINamespace("starboard")
// TODO(cobalt, b/383301493): we expect this class to be a singleton and should consider enforcing
// this property.
public class BaseStarboardBridge {

  private static volatile BaseStarboardBridge sInstance;

  public static BaseStarboardBridge getInstance() {
    return sInstance;
  }

  @VisibleForTesting
  public static void setInstanceForTesting(BaseStarboardBridge bridge) {
    sInstance = bridge;
  }

  /** Interface to be implemented by the Android Application hosting the starboard app. */
  public interface HostApplication {
    void setStarboardBridge(BaseStarboardBridge starboardBridge);

    BaseStarboardBridge getStarboardBridge();
  }

  private Surface mVideoSurface;
  private final Object mVideoSurfaceLock = new Object();
  private final CobaltSystemConfigChangeReceiver mSysConfigChangeReceiver;
  private final CobaltTextToSpeechHelper mTtsHelper;
  // TODO(cobalt): Re-enable these classes or remove if unnecessary.
  private final AudioOutputManager mAudioOutputManager;
  private final AudioPermissionRequester mAudioPermissionRequester;
  private final ResourceOverlay mResourceOverlay;
  private final AdvertisingId mAdvertisingId;
  private final Context mAppContext;
  protected final Holder<Activity> mActivityHolder;
  private final Holder<Service> mServiceHolder;
  // Maps each live Activity to whether it is currently started (Boolean.TRUE) or stopped
  // (Boolean.FALSE).
  // Backed by WeakHashMap to prevent pinning Activity instances in the singleton bridge.
  private final Map<Activity, Boolean> mActivities =
      Collections.synchronizedMap(new WeakHashMap<>());
  private final String[] mArgs;
  private final long mNativeApp;
  private final Runnable mStopRequester =
      new Runnable() {
        @Override
        public void run() {
          // When the platform locale setting is updated, the application needs
          // to exit or the Accept-Language request header configuration needs
          // to be updated. The Accept-Language header configuration is set on
          // application start.
          //
          // To match cobalt 25, we're exiting the application.
          // https://source.corp.google.com/piper///depot/google3/third_party/cobalt/app/android/coat/branch_25_lts/java/dev/cobalt/coat/StarboardBridge.java;l=96
          afterStopped();
        }
      };

  private volatile boolean mApplicationStopped;
  private volatile boolean mApplicationStarted;

  private long mAppStartTimestamp = 0;

  private final Map<String, CobaltService.Factory> mCobaltServiceFactories = new HashMap<>();
  // Keyed by native pointer handle so multiple documents, iframes, or overlapping Activities
  // can open services with the same name without colliding or leaking on close.
  private final Map<Long, CobaltService> mCobaltServices = new ConcurrentHashMap<>();

  private static final String GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms";
  private static final String AMATI_EXPERIENCE_FEATURE =
      "com.google.android.feature.AMATI_EXPERIENCE";
  private final boolean mIsAmatiDevice;
  private static final TimeZone DEFAULT_TIME_ZONE = TimeZone.getTimeZone("America/Los_Angeles");
  private final long mTimeNanosecondsPerMicrosecond = 1000;
  private static final String YTS_CERT_SCOPE_SYSTEM_PROPERTY = "ro.vendor.youtube.cert_scope";
  private static final String DEFAULT_DEVICE_NAME = "Android";
  private static volatile Boolean sWasLowMemoryKilledForTesting;
  private final Natives mNatives = BaseStarboardBridgeJni.get();

  /**
   * Lightweight constructor used by test activities (e.g. CobaltTestActivity).
   *
   * <p>Initializes JNI bindings and AudioOutputManager without launching a secondary native
   * Starboard main event loop.
   */
  protected BaseStarboardBridge(
      Context appContext, Holder<Activity> activityHolder, Holder<Service> serviceHolder) {
    Log.i(TAG, "BaseStarboardBridge test init.");
    BaseStarboardBridgeJni.get().initJNI(this);

    mAppContext = appContext;
    mActivityHolder = activityHolder;
    mServiceHolder = serviceHolder;
    mArgs = new String[0];
    mSysConfigChangeReceiver = null;
    mTtsHelper = null;
    mAudioOutputManager = new AudioOutputManager(appContext);
    mAudioPermissionRequester = null;
    mResourceOverlay = new ResourceOverlay(appContext);
    mAdvertisingId = null;
    mIsAmatiDevice = false;
    mNativeApp = 0;
  }

  public BaseStarboardBridge(
      Context appContext,
      Holder<Activity> activityHolder,
      Holder<Service> serviceHolder,
      String[] args,
      String startDeepLink) {

    Log.i(TAG, "BaseStarboardBridge init.");
    sInstance = this;

    // Make sure the JNI stack is properly initialized first as there is a
    // race condition as soon as any of the following objects creates a new thread.
    mNatives.initJNI(this);

    mAppContext = appContext;
    mActivityHolder = activityHolder;
    mServiceHolder = serviceHolder;
    mArgs = args;
    mSysConfigChangeReceiver = new CobaltSystemConfigChangeReceiver(appContext, mStopRequester);
    mTtsHelper = new CobaltTextToSpeechHelper(appContext);
    mAudioOutputManager = new AudioOutputManager(appContext);
    mAudioPermissionRequester = new AudioPermissionRequester(appContext, activityHolder);
    mResourceOverlay = new ResourceOverlay(appContext);
    mAdvertisingId = new AdvertisingId(appContext);
    mIsAmatiDevice = appContext.getPackageManager().hasSystemFeature(AMATI_EXPERIENCE_FEATURE);

    mNativeApp =
        mNatives.startNativeStarboard(
            getAssetsFromContext(),
            getFilesCanonicalPath(),
            getCacheCanonicalPath(),
            getNativeLibraryDir());

    mNatives.handleDeepLink(startDeepLink, /* applicationStarted= */ false);
    mNatives.setAndroidBuildFingerprint(getBuildFingerprint());
    mNatives.setAndroidOSExperience(mIsAmatiDevice);
    mNatives.setAndroidPlayServicesVersion(getPlayServicesVersion());
    mNatives.setYoutubeCertificationScope(getSystemProperty(YTS_CERT_SCOPE_SYSTEM_PROPERTY));
  }

  @NativeMethods
  interface Natives {
    long currentMonotonicTime();

    long startNativeStarboard(
        AssetManager assetManager, String filesDir, String cacheDir, String nativeLibraryDir);

    boolean initJNI(BaseStarboardBridge starboardBridge);

    void closeNativeStarboard(long app);

    void initializePlatformAudioSink();

    void handleDeepLink(String url, boolean applicationStarted);

    void setAndroidBuildFingerprint(String fingerprint);

    void setAndroidOSExperience(boolean isAmatiDevice);

    void setAndroidPlayServicesVersion(long version);

    void setYoutubeCertificationScope(String certScope);
  }

  private static Boolean sActivityLifecycleCoordinationEnabledForTesting = null;

  @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
  public static void setActivityLifecycleCoordinationEnabledForTesting(Boolean enabled) {
    sActivityLifecycleCoordinationEnabledForTesting = enabled;
  }

  public static boolean isActivityLifecycleCoordinationEnabled() {
    if (sActivityLifecycleCoordinationEnabledForTesting != null) {
      return sActivityLifecycleCoordinationEnabledForTesting;
    }
    return CommandLine.isInitialized()
        && CommandLine.getInstance().hasSwitch("enable-activity-lifecycle-coordination");
  }

  public boolean hasStartedActivities() {
    return mActivities.containsValue(Boolean.TRUE);
  }

  public boolean hasLiveActivities() {
    return !mActivities.isEmpty();
  }

  private int countStartedActivities() {
    synchronized (mActivities) {
      int count = 0;
      for (Boolean started : mActivities.values()) {
        if (Boolean.TRUE.equals(started)) {
          count++;
        }
      }
      return count;
    }
  }

  protected void onActivityCreate(Activity activity) {
    Log.i(TAG, "onActivityCreate ran: " + activity);
    mActivities.put(activity, Boolean.FALSE);
  }

  protected void onActivityStart(Activity activity) {
    Log.i(TAG, "onActivityStart ran: " + activity);
    int activeActivityCount = countStartedActivities();
    if (activeActivityCount > 0) {
      Log.w(
          TAG,
          String.format(
              "onActivityStart: New activity %s starting while there are previous %d activities are"
                  + " still active.",
              activity, activeActivityCount));
    }
    mActivities.put(activity, Boolean.TRUE);
    mActivityHolder.set(activity);
    mSysConfigChangeReceiver.setForeground(true);
    if (isActivityLifecycleCoordinationEnabled()) {
      // Only resume services and trigger foreground logic on 0 -> 1 started activity transition.
      if (activeActivityCount == 0) {
        beforeStartOrResume();
      }
    } else {
      beforeStartOrResume();
    }
  }

  protected void onActivityStop(Activity activity) {
    Log.i(TAG, "onActivityStop ran: " + activity);
    mActivities.put(activity, Boolean.FALSE);
    if (isActivityLifecycleCoordinationEnabled()) {
      // Only suspend services and disable foreground config updates if no other activity instance
      // is currently started.
      if (!hasStartedActivities()) {
        beforeSuspend();
        if (mActivityHolder.get() == activity) {
          mActivityHolder.set(null);
        }
        mSysConfigChangeReceiver.setForeground(false);
      } else {
        if (mActivityHolder.get() == activity) {
          mActivityHolder.set(null);
        }
        Log.i(
            TAG,
            "onActivityStop: Another activity is still started; skipping suspend and foreground"
                + " change.");
      }
    } else {
      beforeSuspend();
      if (mActivityHolder.get() == activity) {
        mActivityHolder.set(null);
      }
      mSysConfigChangeReceiver.setForeground(false);
    }
  }

  protected void onActivityDestroy(Activity activity) {
    Log.i(TAG, "onActivityDestroy ran: " + activity);
    mActivities.remove(activity);
    if (isActivityLifecycleCoordinationEnabled()) {
      if (mActivityHolder.get() == activity) {
        mActivityHolder.set(null);
      }
      if (hasLiveActivities()) {
        Log.i(
            TAG,
            "Activity destroyed but another activity instance is still live; skipping exit check.");
        return;
      }
      boolean shouldNotifySurface = false;
      synchronized (mVideoSurfaceLock) {
        if (mVideoSurface != null) {
          mVideoSurface = null;
          shouldNotifySurface = true;
        }
      }
      if (shouldNotifySurface) {
        VideoSurfaceView.notifyVideoSurfaceChanged(null);
      }
      // Safety net: if all activities are destroyed but services remain in mCobaltServices (e.g.
      // if WebContents/RFH teardown was deferred or skipped), clean them up to prevent leaks.
      if (!mCobaltServices.isEmpty()) {
        Log.w(
            TAG,
            String.format(
                "onActivityDestroy: %d CobaltService instance(s) still open after last activity"
                    + " destroyed; force cleaning up.",
                mCobaltServices.size()));
        closeAllCobaltService();
      }
    } else {
      // Note: During rapid restarts or configuration changes, Android allows a new Activity
      // instance's onCreate()/onStart() to run before the old instance's onDestroy() executes.
      // When that happens, mActivityHolder will already point to the new activity instance.
      // We must avoid cleaning up shared platform services or killing the process if another
      // active instance has taken over.
      if (mActivityHolder.get() != activity && mActivityHolder.get() != null) {
        Log.i(TAG, "Activity destroyed but another activity is active; skipping service cleanup.");
        return;
      }
      if (mActivityHolder.get() == activity) {
        mActivityHolder.set(null);
      }
      closeAllCobaltService();
    }
    if (mApplicationStopped) {
      // We can't restart the starboard app, so kill the process for a clean start next time.
      Log.i(TAG, "Activity destroyed after shutdown; killing app.");
      mNatives.closeNativeStarboard(mNativeApp);
      mTtsHelper.shutdown();
      mAdvertisingId.shutdown();
      System.exit(0);
    } else {
      Log.i(TAG, "Activity destroyed without shutdown; app suspended in background.");
    }
  }

  protected void onServiceStart(Service service) {
    mServiceHolder.set(service);
  }

  protected void onServiceDestroy(Service service) {
    if (mServiceHolder.get() == service) {
      mServiceHolder.set(null);
    }
  }

  protected void beforeStartOrResume() {
    Log.i(TAG, "Prepare to resume");
    // Bring our platform services to life before resuming so that they're ready to deal with
    // whatever the web app wants to do with them as part of its start/resume logic.
    for (CobaltService service : mCobaltServices.values()) {
      service.beforeStartOrResume();
    }
    mAdvertisingId.refresh();
  }

  protected void beforeSuspend() {
    try {
      Log.i(TAG, "Prepare to suspend");
      // We want the MediaSession to be deactivated immediately before suspending so that by
      // the
      // time, the launcher is visible our "Now Playing" card is already gone. Then Cobalt and
      // the web app can take their time suspending after that.
      for (CobaltService service : mCobaltServices.values()) {
        service.beforeSuspend();
      }
    } catch (Throwable e) {
      Log.i(TAG, "Caught exception in beforeSuspend: " + e.getMessage());
    }
  }

  protected void afterStopped() {
    mApplicationStopped = true;
    mTtsHelper.shutdown();
    closeAllCobaltService();
    Activity activity = mActivityHolder.get();
    if (activity != null) {
      // Wait until the activity is destroyed to exit.
      Log.i(TAG, "Shutdown in foreground; finishing Activity and removing task.");
      activity.finishAndRemoveTask();
    } else {
      // We can't restart the starboard app, so kill the process for a clean start next time.
      Log.i(TAG, "Shutdown in background; killing app without removing task.");
      System.exit(0);
    }
  }

  @CalledByNative
  protected void applicationStarted() {
    mApplicationStarted = true;
  }

  @CalledByNative
  protected void applicationStopping() {
    mApplicationStarted = false;
    mApplicationStopped = true;
  }

  @CalledByNative
  public void requestSuspend() {
    Activity activity = mActivityHolder.get();
    if (activity != null) {
      activity.moveTaskToBack(false);
    }
  }

  /* Immediate shutdown, used at least by StandalonePlayerActivity. */
  @CalledByNative
  public void requestStop(int errorLevel) {
    applicationStopping();
    Activity activity = mActivityHolder.get();
    if (activity != null) {
      activity.finishAndRemoveTask();
    }
  }

  public boolean onSearchRequested() {
    return false;
  }

  @CalledByNative
  void raisePlatformError(int errorType, long data, String url, boolean disableDismiss) {}

  @CalledByNative
  public boolean isPlatformErrorShowing() {
    return false;
  }

  protected Holder<Activity> getActivityHolder() {
    return mActivityHolder;
  }

  @CalledByNative
  protected String[] getArgs() {
    if (mArgs == null) {
      throw new IllegalArgumentException("mArgs cannot be null");
    }
    return mArgs;
  }

  // Initialize the platform's AudioTrackAudioSink. This must be done after the browser client
  // loads in the feature list and field trials.
  public void initializePlatformAudioSink() {
    mNatives.initializePlatformAudioSink();
  }

  /** Sends an event to the web app to navigate to the given URL */
  public void handleDeepLink(String url) {
    mNatives.handleDeepLink(url, mApplicationStarted);
  }

  public AssetManager getAssetsFromContext() {
    return mAppContext.getAssets();
  }

  public String getNativeLibraryDir() {
    return mAppContext.getApplicationInfo().nativeLibraryDir;
  }

  /**
   * Returns the absolute path to the directory where application specific files should be written.
   * May be overridden for use cases that need to segregate storage.
   */
  protected String getFilesAbsolutePath() {
    return mAppContext.getFilesDir().getAbsolutePath();
  }

  /**
   * Returns the canonical path to the directory where application specific files should be written,
   * resolving symbolic links. nplb tests compare against resolved paths.
   */
  protected String getFilesCanonicalPath() {
    try {
      return mAppContext.getFilesDir().getCanonicalPath();
    } catch (IOException e) {
      Log.w(TAG, "Failed to get canonical path", e);
      return getFilesAbsolutePath();
    }
  }

  /**
   * Returns the absolute path to the application specific cache directory on the filesystem. May be
   * overridden for use cases that need to segregate storage.
   */
  protected String getCacheAbsolutePath() {
    return mAppContext.getCacheDir().getAbsolutePath();
  }

  /**
   * Returns the canonical path to the application specific cache directory, resolving symbolic
   * links. nplb tests compare against resolved paths.
   */
  protected String getCacheCanonicalPath() {
    try {
      return mAppContext.getCacheDir().getCanonicalPath();
    } catch (IOException e) {
      Log.w(TAG, "Failed to get canonical path", e);
      return getCacheAbsolutePath();
    }
  }

  // TODO: (cobalt b/372559388) remove or migrate JNI?
  // Used in starboard/android/shared/speech_synthesis_speak.cc
  @CalledByNative
  CobaltTextToSpeechHelper getTextToSpeechHelper() {
    if (mTtsHelper == null) {
      throw new IllegalArgumentException("mTtsHelper cannot be null for native code");
    }
    return mTtsHelper;
  }

  /**
   * @return A new CaptionSettings object with the current system caption settings.
   */
  @CalledByNative
  CaptionSettings getCaptionSettings() {
    CaptioningManager cm =
        (CaptioningManager) mAppContext.getSystemService(Context.CAPTIONING_SERVICE);
    return new CaptionSettings(cm);
  }

  /** Java-layer implementation of SbSystemGetLocaleId. */
  @CalledByNative
  String systemGetLocaleId() {
    return Locale.getDefault().toLanguageTag();
  }

  @CalledByNative
  String getTimeZoneId() {
    Locale locale = Locale.getDefault();
    Calendar calendar = Calendar.getInstance(locale);
    TimeZone timeZone = DEFAULT_TIME_ZONE;
    if (calendar != null) {
      timeZone = calendar.getTimeZone();
    }
    return timeZone.getID();
  }

  @CalledByNative
  DisplayUtil.DisplayDpi getDisplayDpi() {
    return DisplayUtil.getDisplayDpi();
  }

  @CalledByNative
  Size getDisplaySize() {
    android.util.Size size = DisplayUtil.getSystemDisplaySize();
    return new Size(size.getWidth(), size.getHeight());
  }

  @CalledByNative
  public ResourceOverlay getResourceOverlay() {
    if (mResourceOverlay == null) {
      throw new IllegalArgumentException("mResourceOverlay cannot be null for native code");
    }
    return mResourceOverlay;
  }

  @Nullable
  private static String getSystemProperty(String name) {
    try {
      @SuppressLint("PrivateApi")
      Class<?> systemProperties = Class.forName("android.os.SystemProperties");
      Method getMethod = systemProperties.getMethod("get", String.class);
      return (String) getMethod.invoke(systemProperties, name);
    } catch (Exception e) {
      Log.e(TAG, "Failed to read system property " + name, e);
      return null;
    }
  }

  /**
   * Checks if there is no microphone connected to the system.
   *
   * @return true if no device is connected.
   */
  @SuppressWarnings("unused")
  @CalledByNative
  public boolean isMicrophoneDisconnected() {
    // A check specifically for microphones is not available before API 28, so it is assumed
    // that a
    // connected input audio device is a microphone.
    AudioManager audioManager = (AudioManager) mAppContext.getSystemService(AUDIO_SERVICE);
    AudioDeviceInfo[] devices = audioManager.getDevices(GET_DEVICES_INPUTS);
    if (devices.length > 0) {
      return false;
    }

    // fallback to check for BT voice capable RCU
    InputManager inputManager = (InputManager) mAppContext.getSystemService(Context.INPUT_SERVICE);
    final int[] inputDeviceIds = inputManager.getInputDeviceIds();
    for (int inputDeviceId : inputDeviceIds) {
      final InputDevice inputDevice = inputManager.getInputDevice(inputDeviceId);
      final boolean hasMicrophone = inputDevice.hasMicrophone();
      if (hasMicrophone) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks if the microphone is muted.
   *
   * @return true if the microphone mute is on.
   */
  @SuppressWarnings("unused")
  @CalledByNative
  public boolean isMicrophoneMute() {
    AudioManager audioManager = (AudioManager) mAppContext.getSystemService(AUDIO_SERVICE);
    return audioManager.isMicrophoneMute();
  }

  /** Returns string for kSbSystemPropertyUserAgentAuxField */
  @CalledByNative
  protected String getUserAgentAuxField() {
    StringBuilder sb = new StringBuilder();

    String packageName = mAppContext.getApplicationInfo().packageName;
    sb.append(packageName);
    sb.append('/');

    try {
      if (android.os.Build.VERSION.SDK_INT < 33) {
        sb.append(mAppContext.getPackageManager().getPackageInfo(packageName, 0).versionName);
      } else {
        sb.append(
            mAppContext
                .getPackageManager()
                .getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName);
      }
    } catch (PackageManager.NameNotFoundException ex) {
      // Should never happen
      Log.e(TAG, "Can't find our own package", ex);
    }

    return sb.toString();
  }

  // TODO: (cobalt b/372559388) remove or migrate JNI?
  // Used in starboard/android/shared/system_get_property.cc
  /** Returns string for kSbSystemPropertyAdvertisingId */
  @CalledByNative
  protected String getAdvertisingId() {
    return mAdvertisingId.getId();
  }

  // TODO: (cobalt b/372559388) remove or migrate JNI?
  // Used in starboard/android/shared/system_get_property.cc
  /** Returns boolean for kSbSystemPropertyLimitAdTracking */
  @CalledByNative
  protected boolean getLimitAdTracking() {
    return mAdvertisingId.isLimitAdTrackingEnabled();
  }

  @CalledByNative
  protected String getFriendlyName() {
    String deviceName = null;
    try {
      deviceName =
          android.provider.Settings.Global.getString(
              mAppContext.getContentResolver(), android.provider.Settings.Global.DEVICE_NAME);
    } catch (SecurityException e) {
      Log.w(TAG, "SecurityException reading DEVICE_NAME setting", e);
    }
    if (deviceName == null || deviceName.isEmpty()) {
      deviceName = android.os.Build.MODEL;
    }
    if (deviceName == null || deviceName.isEmpty()) {
      deviceName = DEFAULT_DEVICE_NAME;
    }
    return deviceName;
  }

  @CalledByNative
  protected double getScreenDiagonal() {
    android.util.Size size = DisplayUtil.getSystemDisplaySize();
    DisplayUtil.DisplayDpi dpi = DisplayUtil.getDisplayDpi();
    if (size == null || dpi == null || dpi.getX() < 0.1f || dpi.getY() < 0.1f) {
      Log.e(TAG, "getScreenDiagonal: Invalid display size or DPI values");
      return 0.0;
    }
    double widthInches = size.getWidth() / (double) dpi.getX();
    double heightInches = size.getHeight() / (double) dpi.getY();
    return Math.sqrt(widthInches * widthInches + heightInches * heightInches);
  }

  @CalledByNative
  AudioOutputManager getAudioOutputManager() {
    if (mAudioOutputManager == null) {
      throw new IllegalArgumentException("mAudioOutputManager cannot be null for native code");
    }
    return mAudioOutputManager;
  }

  /** Returns Java layer implementation for AudioPermissionRequester */
  @SuppressWarnings("unused")
  @CalledByNative
  AudioPermissionRequester getAudioPermissionRequester() {
    return mAudioPermissionRequester;
  }

  void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    mAudioPermissionRequester.onRequestPermissionsResult(requestCode, permissions, grantResults);
  }

  /**
   * Called when a VideoSurfaceView creates its underlying Surface.
   *
   * <p>Note: Starboard assumes a single active video surface at any given time for punch-out video
   * decoding. During activity recreation or overlapping transitions, the newest activity's surface
   * supersedes any previous surface as the active target.
   */
  public void onVideoSurfaceCreated(Surface surface) {
    Log.i(TAG, String.format("onVideoSurfaceCreated: surface=%s", surface));
    synchronized (mVideoSurfaceLock) {
      mVideoSurface = surface;
    }
    VideoSurfaceView.notifyVideoSurfaceChanged(surface);
  }

  /**
   * Called when a VideoSurfaceView destroys its underlying Surface.
   *
   * <p>If this destruction event is for an old surface that was already superseded by a newer
   * activity's surface, it is safely ignored so that the active video decoding target is not torn
   * down prematurely.
   */
  public void onVideoSurfaceDestroyed(Surface surface) {
    Log.i(TAG, String.format("onVideoSurfaceDestroyed: surface=%s", surface));
    boolean shouldNotify = false;
    synchronized (mVideoSurfaceLock) {
      if (isActivityLifecycleCoordinationEnabled()) {
        // If this destruction is for a surface that is no longer active (e.g. from an old activity
        // during an overlapping activity transition), ignore it to avoid tearing down the new
        // surface.
        if (mVideoSurface != surface) {
          Log.i(
              TAG,
              String.format(
                  "onVideoSurfaceDestroyed: Ignoring destroyed surface %s from stale activity;"
                      + " current surface is %s",
                  surface, mVideoSurface));
          return;
        }
      }
      mVideoSurface = null;
      shouldNotify = true;
    }
    if (shouldNotify) {
      VideoSurfaceView.notifyVideoSurfaceChanged(null);
    }
  }

  public Surface getVideoSurface() {
    synchronized (mVideoSurfaceLock) {
      return mVideoSurface;
    }
  }

  @SuppressWarnings("unused")
  @CalledByNative
  public void resetVideoSurface() {
    Activity activity = mActivityHolder.get();
    if (activity instanceof BaseCobaltActivity) {
      ((BaseCobaltActivity) activity).resetVideoSurface();
    }
  }

  @SuppressWarnings("unused")
  @CalledByNative
  public void setVideoSurfaceBounds(final int x, final int y, final int width, final int height) {
    Activity activity = mActivityHolder.get();
    if (activity instanceof BaseCobaltActivity) {
      ((BaseCobaltActivity) activity).setVideoSurfaceBounds(x, y, width, height);
    }
  }

  public void registerCobaltService(CobaltService.Factory factory) {
    mCobaltServiceFactories.put(factory.getServiceName(), factory);
  }

  public void unregisterCobaltService(String serviceName) {
    mCobaltServiceFactories.remove(serviceName);
  }

  public void clearCobaltServiceFactories() {
    mCobaltServiceFactories.clear();
  }

  @CalledByNative
  public boolean hasCobaltService(String serviceName) {
    return mCobaltServiceFactories.get(serviceName) != null;
  }

  protected void onServiceCreated(CobaltService service) {
    service.receiveBaseStarboardBridge(this);
  }

  // Explicitly pass activity as parameter.
  // Avoid using mActivityHolder.get(), because onActivityStop() can set it to null.
  @CalledByNative
  public CobaltService openCobaltService(long nativeService, String serviceName) {
    final CobaltService.Factory factory = mCobaltServiceFactories.get(serviceName);
    if (factory == null) {
      Log.e(TAG, String.format("Cannot open unregistered service %s", serviceName));
      return null;
    }
    CobaltService service = factory.createCobaltService(nativeService);
    if (service != null) {
      service.setServiceName(serviceName);
      service.setNativeService(nativeService);
      onServiceCreated(service);
      mCobaltServices.put(nativeService, service);
      Log.i(
          TAG,
          String.format("Opened platform service %s [handle: 0x%x].", serviceName, nativeService));
    }
    return service;
  }

  public CobaltService getOpenedCobaltService(String serviceName) {
    if (serviceName == null) {
      return null;
    }
    CobaltService matchedService = null;
    // Count matching instances across open handles to detect and warn if name-based lookup is
    // ambiguous.
    int count = 0;
    for (CobaltService service : mCobaltServices.values()) {
      if (!serviceName.equals(service.getServiceName())) {
        continue;
      }
      if (matchedService == null) {
        matchedService = service;
      }
      count++;
    }
    if (count > 1) {
      Log.w(
          TAG,
          String.format(
              "Multiple open CobaltService instances (%d) found for %s; returning first instance.",
              count, serviceName));
    }
    return matchedService;
  }

  @CalledByNative
  public void closeCobaltService(long nativeService) {
    CobaltService service = mCobaltServices.remove(nativeService);
    if (service != null) {
      service.afterStopped();
      service.onClose();
      Log.i(
          TAG,
          String.format(
              "Closed platform service %s [handle: 0x%x].",
              service.getServiceName(), nativeService));
    }
  }

  @CalledByNative
  public void closeAllCobaltService() {
    for (Long nativeService : new ArrayList<>(mCobaltServices.keySet())) {
      closeCobaltService(nativeService);
    }
  }

  public byte[] sendToCobaltService(String serviceName, byte[] data) {
    CobaltService service = getOpenedCobaltService(serviceName);
    if (service == null) {
      Log.e(TAG, String.format("Service not opened: %s", serviceName));
      return null;
    }
    CobaltService.ResponseToClient response = service.receiveFromClient(data);
    if (response == null || response.invalidState) {
      Log.e(TAG, String.format("Service %s received invalid data, closing.", serviceName));
      closeCobaltService(service.getNativeService());
      return null;
    }
    return response.data;
  }

  /** Returns the application start timestamp. */
  protected void measureAppStartTimestamp() {
    if (mAppStartTimestamp != 0) {
      return;
    }
    Activity activity = mActivityHolder.get();
    if (!(activity instanceof BaseCobaltActivity)) {
      return;
    }
    long javaStartTimestamp = ((BaseCobaltActivity) activity).getAppStartTimestamp();
    long javaStopTimestamp = System.nanoTime();
    long appStartDuration =
        (javaStopTimestamp - javaStartTimestamp) / mTimeNanosecondsPerMicrosecond;

    long cppTimestamp = mNatives.currentMonotonicTime();
    mAppStartTimestamp = cppTimestamp - appStartDuration;
  }

  // Returns the app start timestamp in microseconds.
  @CalledByNative
  protected long getAppStartTimestamp() {
    Activity activity = mActivityHolder.get();
    if (activity instanceof BaseCobaltActivity) {
      BaseCobaltActivity baseActivity = (BaseCobaltActivity) activity;
      if (baseActivity.useStarboardLifeCycle()) {
        return baseActivity.getAppStartTimestamp() / 1000L;
      }
      if (mAppStartTimestamp != 0) {
        return mAppStartTimestamp;
      }
      return baseActivity.getAppStartTimestamp() / 1000L;
    }
    return mAppStartTimestamp;
  }

  @CalledByNative
  void reportFullyDrawn() {
    Activity activity = mActivityHolder.get();
    if (activity != null) {
      activity.reportFullyDrawn();
    }
  }

  @CalledByNative
  public void setCrashContext(String key, String value) {
    CrashContext.INSTANCE.setCrashContext(key, value);
  }

  public HashMap<String, String> getCrashContext() {
    return CrashContext.INSTANCE.getCrashContext();
  }

  public void registerCrashContextUpdateHandler(CrashContextUpdateHandler handler) {
    CrashContext.INSTANCE.registerCrashContextUpdateHandler(handler);
  }

  @CalledByNative
  protected boolean getIsAmatiDevice() {
    return mIsAmatiDevice;
  }

  @CalledByNative
  protected String getBuildFingerprint() {
    return Build.FINGERPRINT;
  }

  @CalledByNative
  protected long getPlayServicesVersion() {
    try {
      if (android.os.Build.VERSION.SDK_INT < 28) {
        return mAppContext
            .getPackageManager()
            .getPackageInfo(GOOGLE_PLAY_SERVICES_PACKAGE, 0)
            .versionCode;
      } else if (android.os.Build.VERSION.SDK_INT < 33) {
        return mAppContext
            .getPackageManager()
            .getPackageInfo(GOOGLE_PLAY_SERVICES_PACKAGE, 0)
            .getLongVersionCode();
      } else {
        return mAppContext
            .getPackageManager()
            .getPackageInfo(GOOGLE_PLAY_SERVICES_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            .getLongVersionCode();
      }
    } catch (Exception e) {
      Log.w(TAG, "Unable to query Google Play Services package version", e);
      return 0;
    }
  }

  @CalledByNative
  public void closeApp() {
    Activity activity = mActivityHolder.get();
    if (activity instanceof BaseCobaltActivity) {
      ((BaseCobaltActivity) activity).finishAffinity();
    }
  }

  @CalledByNative
  protected boolean installAppFromURL(String url) {
    Activity activity = mActivityHolder.get();
    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
    try {
      UpdateDownloader.validateUrl(url);
    } catch (java.io.IOException error) {
      return false;
    }
    activity.runOnUiThread(() -> {
      try {
        activity.startActivity(new Intent(activity, CobaltUpdateActivity.class)
            .putExtra(CobaltUpdateActivity.EXTRA_URL, url));
      } catch (RuntimeException error) {
        android.widget.Toast.makeText(activity, R.string.tizentube_update_start_failed,
            android.widget.Toast.LENGTH_LONG).show();
      }
    });
    return true;
  }

  @CalledByNative
  protected String getVersion() {
    try {
      android.content.pm.PackageInfo packageInfo =
          mAppContext.getPackageManager().getPackageInfo(mAppContext.getPackageName(), 0);
      return packageInfo.versionName;
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      Log.e(TAG, "Failed to get package info", e);
      return "unknown";
    }
  }

  @CalledByNative
  protected String getBrandAndModel() {
    return Settings.Global.getString(mAppContext.getContentResolver(), "device_name");
  }

  @CalledByNative
  protected String getArchitecture() {
    return Build.SUPPORTED_ABIS[0];
  }

  @CalledByNative
  protected void setFrameRate(float frameRate) {
    if (Build.VERSION.SDK_INT < 30) {
      return;
    }

    Activity activity = mActivityHolder.get();
    if (activity == null) {
      return;
    }

    Display defaultDisplay = activity.getWindowManager().getDefaultDisplay();
    DisplayManager displayManager =
        (DisplayManager) mAppContext.getSystemService(Context.DISPLAY_SERVICE);

    boolean canBeSeamless = false;
    int strategy = 1;
    if (Build.VERSION.SDK_INT >= 31) {
        float[] supportedRefreshRates = defaultDisplay.getMode().getAlternativeRefreshRates();
        for (float rate : supportedRefreshRates) {
          if (Math.abs(rate - frameRate) < 0.1f || isMultiple(rate, frameRate, 0.1f)) {
            canBeSeamless = true;
            break;
          }
        }

        int contentPreference = displayManager.getMatchContentFrameRateUserPreference();
        if (contentPreference == DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER) return;
        else if (contentPreference == DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY) {
          if (canBeSeamless) {
            strategy = Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS;
          }
        } else if (contentPreference == DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS) {
          strategy = Surface.CHANGE_FRAME_RATE_ALWAYS;
        } else {
          strategy = Surface.CHANGE_FRAME_RATE_ALWAYS;
        }
      }

    if (activity instanceof CobaltActivity) {
      if (Build.VERSION.SDK_INT == 30) {
        ((CobaltActivity) activity).setFrameRate(frameRate, 0);
      } else if (Build.VERSION.SDK_INT >= 31) {
        ((CobaltActivity) activity).setFrameRate(frameRate, strategy);
      }
    }
  }

  @CalledByNative
  protected void enterPIP() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Activity activity = mActivityHolder.get();
      if (activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
        Rational aspectRatio = new Rational(1920, 1080);
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build();
            Boolean result = activity.enterPictureInPictureMode(params);
      }
    }
  }

  @CalledByNative
  protected boolean hasSystemFeature(String featureName) {
    return mAppContext.getPackageManager().hasSystemFeature(featureName);
  }

  @CalledByNative
  protected void showAlertDialog(String title, String message) {
    Activity activity = mActivityHolder.get();
    if (activity == null) return;
    activity.runOnUiThread(() -> {
      new AlertDialog.Builder(activity)
          .setTitle(title)
          .setMessage(message)
          .setPositiveButton(android.R.string.ok, null)
          .show();
    });
  }

  private static boolean isMultiple(float refreshRate, float fps, float tolerance) {
    float ratio = refreshRate / fps;
    float nearestInt = Math.round(ratio);
    return Math.abs(ratio - nearestInt) < (tolerance / fps);
  }

  /** A wrapper of the android.util.Size class to be used by JNI. */
  public static class Size {
    private final int mWidth;
    private final int mHeight;

    public Size(int width, int height) {
      mWidth = width;
      mHeight = height;
    }

    @CalledByNative("Size")
    public int getWidth() {
      return mWidth;
    }

    @CalledByNative("Size")
    public int getHeight() {
      return mHeight;
    }
  }

  @CalledByNative
  protected void hideSplashScreen() {}

  @CalledByNative
  protected void setStartupMilestone(int milestone) {}

  @CalledByNative
  protected void setStartupDiagnosisInfo(String key, String value) {}

  @CalledByNative
  public boolean getWasLowMemoryKilled() {
    if (sWasLowMemoryKilledForTesting != null) {
      return sWasLowMemoryKilledForTesting;
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return false;
    }
    if (mAppContext == null) {
      return false;
    }
    ActivityManager am = (ActivityManager) mAppContext.getSystemService(Context.ACTIVITY_SERVICE);
    if (am == null) {
      return false;
    }
    return ApiHelperForR.getWasLowMemoryKilled(am);
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private static final class ApiHelperForR {
    private ApiHelperForR() {}

    static boolean getWasLowMemoryKilled(ActivityManager am) {
      try {
        // Query the latest process exit reason for this package (pid <= 0).
        List<ApplicationExitInfo> reasons =
            am.getHistoricalProcessExitReasons(
                /* package_name= */ null, /* pid= */ 0, /* maxNum= */ 1);
        if (reasons == null || reasons.isEmpty() || reasons.get(0) == null) {
          return false;
        }
        return reasons.get(0).getReason() == ApplicationExitInfo.REASON_LOW_MEMORY;
      } catch (RuntimeException e) {
        Log.w(TAG, "Failed to get historical process exit reasons", e);
        return false;
      }
    }
  }

  @VisibleForTesting
  public static void setWasLowMemoryKilledForTesting(@Nullable Boolean wasKilled) {
    sWasLowMemoryKilledForTesting = wasKilled;
  }
}
