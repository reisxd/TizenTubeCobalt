// Copyright 2017 The Cobalt Authors. All Rights Reserved.
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
import android.app.Service;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.provider.Settings;
import android.util.Pair;
import android.util.Size;
import android.util.SizeF;
import android.view.Display;
import android.view.InputDevice;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.CaptioningManager;
import androidx.annotation.Nullable;
import dev.cobalt.coat.tvchannel.TvChannelBridge;
import dev.cobalt.media.AudioOutputManager;
import dev.cobalt.util.DisplayUtil;
import dev.cobalt.util.Holder;
import dev.cobalt.util.Log;
import dev.cobalt.util.UsedByNative;
import java.lang.reflect.Method;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;

/** Implementation of the required JNI methods called by the Starboard C++ code. */
public class StarboardBridge {

  /** Interface to be implemented by the Android Application hosting the starboard app. */
  public interface HostApplication {
    void setStarboardBridge(StarboardBridge starboardBridge);

    StarboardBridge getStarboardBridge();
  }

  private CobaltSystemConfigChangeReceiver sysConfigChangeReceiver;
  private CobaltTextToSpeechHelper ttsHelper;
  private AudioOutputManager audioOutputManager;
  private CobaltMediaSession cobaltMediaSession;
  private AudioPermissionRequester audioPermissionRequester;
  private NetworkStatus networkStatus;
  private ResourceOverlay resourceOverlay;
  private AdvertisingId advertisingId;
  private VolumeStateReceiver volumeStateReceiver;
  private CrashContextUpdateHandler crashContextUpdateHandler;

  static {
    // Even though NativeActivity already loads our library from C++,
    // we still have to load it from Java to make JNI calls into it.

    // GameActivity has code to load the libcobalt.so as well.
    // It reads the library name from the meta data field "android.app.lib_name" in the
    // AndroidManifest.xml
    System.loadLibrary("cobalt");
  }

  private final Context appContext;
  private final Holder<Activity> activityHolder;
  private final Holder<Service> serviceHolder;
  private final String[] args;
  private String startDeepLink;
  private final Runnable stopRequester =
      new Runnable() {
        @Override
        public void run() {
          requestStop(0);
        }
      };

  private volatile boolean starboardApplicationStopped = false;
  private volatile boolean starboardApplicationReady = false;

  private final HashMap<String, CobaltService.Factory> cobaltServiceFactories = new HashMap<>();
  private final HashMap<String, CobaltService> cobaltServices = new HashMap<>();
  private final HashMap<String, String> crashContext = new HashMap<>();

  // Cached TvChannelBridge instance to avoid creating new instances on each JNI call
  private TvChannelBridge tvChannelBridge;

  private static final String GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms";
  private static final String AMATI_EXPERIENCE_FEATURE =
      "com.google.android.feature.AMATI_EXPERIENCE";
  private final boolean isAmatiDevice;
  private static final TimeZone DEFAULT_TIME_ZONE = TimeZone.getTimeZone("America/Los_Angeles");
  private final long timeNanosecondsPerMicrosecond = 1000;

  public StarboardBridge(
      Context appContext,
      Holder<Activity> activityHolder,
      Holder<Service> serviceHolder,
      ArtworkDownloader artworkDownloader,
      String[] args,
      String startDeepLink) {

    // Make sure the JNI stack is properly initialized first as there is
    // race condition as soon as any of the following objects creates a new thread.
    nativeInitialize();

    this.appContext = appContext;
    this.activityHolder = activityHolder;
    this.serviceHolder = serviceHolder;
    this.args = args;
    this.startDeepLink = startDeepLink;
    this.sysConfigChangeReceiver = new CobaltSystemConfigChangeReceiver(appContext, stopRequester);
    this.ttsHelper = new CobaltTextToSpeechHelper(appContext);
    this.audioOutputManager = new AudioOutputManager(appContext);
    this.cobaltMediaSession =
        new CobaltMediaSession(appContext, activityHolder, audioOutputManager, artworkDownloader);
    this.audioPermissionRequester = new AudioPermissionRequester(appContext, activityHolder);
    this.networkStatus = new NetworkStatus(appContext);
    this.resourceOverlay = new ResourceOverlay(appContext);
    this.advertisingId = new AdvertisingId(appContext);
    this.isAmatiDevice = appContext.getPackageManager().hasSystemFeature(AMATI_EXPERIENCE_FEATURE);
  }

  private native boolean nativeInitialize();

  private native long nativeCurrentMonotonicTime();

  protected void onActivityStart(Activity activity) {
    activityHolder.set(activity);
    sysConfigChangeReceiver.setForeground(true);
  }

  protected void onActivityStop(Activity activity) {
    if (activityHolder.get() == activity) {
      activityHolder.set(null);
    }
    sysConfigChangeReceiver.setForeground(false);
  }

  protected void onActivityDestroy(Activity activity) {
    if (starboardApplicationStopped) {
      // We can't restart the starboard app, so kill the process for a clean start next time.
      Log.i(TAG, "Activity destroyed after shutdown; killing app.");
      System.exit(0);
    } else {
      Log.i(TAG, "Activity destroyed without shutdown; app suspended in background.");
    }
  }

  protected void onServiceStart(Service service) {
    serviceHolder.set(service);
  }

  protected void onServiceDestroy(Service service) {
    if (serviceHolder.get() == service) {
      serviceHolder.set(null);
    }
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected void beforeStartOrResume() {
    Log.i(TAG, "Prepare to resume");
    // Bring our platform services to life before resuming so that they're ready to deal with
    // whatever the web app wants to do with them as part of its start/resume logic.
    cobaltMediaSession.resume();
    networkStatus.beforeStartOrResume();
    for (CobaltService service : cobaltServices.values()) {
      service.beforeStartOrResume();
    }
    advertisingId.refresh();
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected void beforeSuspend() {
    try {
      Log.i(TAG, "Prepare to suspend");
      // We want the MediaSession to be deactivated immediately before suspending so that by the
      // time, the launcher is visible our "Now Playing" card is already gone. Then Cobalt and
      // the web app can take their time suspending after that.
      cobaltMediaSession.suspend();
      networkStatus.beforeSuspend();
      for (CobaltService service : cobaltServices.values()) {
        service.beforeSuspend();
      }
    } catch (Throwable e) {
      Log.i(TAG, "Caught exception in beforeSuspend: " + e.getMessage());
    }
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected void afterStopped() {
    starboardApplicationStopped = true;
    ttsHelper.shutdown();
    for (CobaltService service : cobaltServices.values()) {
      service.afterStopped();
    }
    Activity activity = activityHolder.get();
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

  @SuppressWarnings("unused")
  @UsedByNative
  protected void starboardApplicationStarted() {
    starboardApplicationReady = true;
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected void starboardApplicationStopping() {
    starboardApplicationReady = false;
    starboardApplicationStopped = true;
  }

  @SuppressWarnings("unused")
  @UsedByNative
  public void requestStop(int errorLevel) {
    if (starboardApplicationReady) {
      Log.i(TAG, "Request to stop");
      nativeStopApp(errorLevel);
    }
  }

  private native void nativeStopApp(int errorLevel);

  @SuppressWarnings("unused")
  @UsedByNative
  public void requestSuspend() {
    Activity activity = activityHolder.get();
    if (activity != null) {
      Log.i(TAG, "Request to suspend");
      activity.finish();
    }
  }

  public boolean onSearchRequested() {
    if (starboardApplicationReady) {
      return nativeOnSearchRequested();
    }
    return false;
  }

  private native boolean nativeOnSearchRequested();

  @SuppressWarnings("unused")
  @UsedByNative
  public Context getApplicationContext() {
    return appContext;
  }

  @SuppressWarnings("unused")
  @UsedByNative
  void raisePlatformError(@PlatformError.ErrorType int errorType, long data) {
    PlatformError error = new PlatformError(activityHolder, errorType, data);
    error.raise();
  }

  /** Returns true if the native code is compiled for release (i.e. 'gold' build). */
  public static boolean isReleaseBuild() {
    return nativeIsReleaseBuild();
  }

  private static native boolean nativeIsReleaseBuild();

  protected Holder<Activity> getActivityHolder() {
    return activityHolder;
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected String[] getArgs() {
    return args;
  }

  /** Returns the URL from the Intent that started the app. */
  @SuppressWarnings("unused")
  @UsedByNative
  protected String getStartDeepLink() {
    return startDeepLink;
  }

  /** Sends an event to the web app to navigate to the given URL */
  public void handleDeepLink(String url) {
    if (starboardApplicationReady) {
      nativeHandleDeepLink(url);
    } else {
      // If this deep link event is received before the starboard application
      // is ready, it replaces the start deep link.
      startDeepLink = url;
    }
  }

  private native void nativeHandleDeepLink(String url);

  /**
   * Returns the absolute path to the directory where application specific files should be written.
   * May be overridden for use cases that need to segregate storage.
   */
  @SuppressWarnings("unused")
  @UsedByNative
  protected String getFilesAbsolutePath() {
    return appContext.getFilesDir().getAbsolutePath();
  }

  /**
   * Returns the absolute path to the application specific cache directory on the filesystem. May be
   * overridden for use cases that need to segregate storage.
   */
  @SuppressWarnings("unused")
  @UsedByNative
  protected String getCacheAbsolutePath() {
    return appContext.getCacheDir().getAbsolutePath();
  }

  /**
   * Returns non-loopback network interface address and its netmask, or null if none.
   *
   * <p>A Java function to help implement Starboard's SbSocketGetLocalInterfaceAddress.
   */
  @SuppressWarnings("unused")
  @UsedByNative
  Pair<byte[], byte[]> getLocalInterfaceAddressAndNetmask(boolean wantIPv6) {
    try {
      Enumeration<NetworkInterface> it = NetworkInterface.getNetworkInterfaces();

      while (it.hasMoreElements()) {
        NetworkInterface ni = it.nextElement();
        if (ni.isLoopback()) {
          continue;
        }
        if (!ni.isUp()) {
          continue;
        }
        if (ni.isPointToPoint()) {
          continue;
        }

        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
          byte[] address = ia.getAddress().getAddress();
          boolean isIPv6 = (address.length > 4);
          if (isIPv6 == wantIPv6) {
            // Convert the network prefix length to a network mask.
            int prefix = ia.getNetworkPrefixLength();
            byte[] netmask = new byte[address.length];
            for (int i = 0; i < netmask.length; i++) {
              if (prefix == 0) {
                netmask[i] = 0;
              } else if (prefix >= 8) {
                netmask[i] = (byte) 0xFF;
                prefix -= 8;
              } else {
                netmask[i] = (byte) (0xFF << (8 - prefix));
                prefix = 0;
              }
            }
            return new Pair<>(address, netmask);
          }
        }
      }
    } catch (SocketException ex) {
      // TODO should we have a logging story that strips logs for production?
      Log.w(TAG, "sbSocketGetLocalInterfaceAddress exception", ex);
      return null;
    }
    return null;
  }

  @SuppressWarnings("unused")
  @UsedByNative
  CobaltTextToSpeechHelper getTextToSpeechHelper() {
    return ttsHelper;
  }

  /**
   * @return A new CaptionSettings object with the current system caption settings.
   */
  @SuppressWarnings("unused")
  @UsedByNative
  CaptionSettings getCaptionSettings() {
    CaptioningManager cm =
        (CaptioningManager) appContext.getSystemService(Context.CAPTIONING_SERVICE);
    return new CaptionSettings(cm);
  }

  /** Java-layer implementation of SbSystemGetLocaleId. */
  @SuppressWarnings("unused")
  @UsedByNative
  String systemGetLocaleId() {
    return Locale.getDefault().toLanguageTag();
  }

  @SuppressWarnings("unused")
  @UsedByNative
  String getTimeZoneId() {
    Locale locale = Locale.getDefault();
    Calendar calendar = Calendar.getInstance(locale);
    TimeZone timeZone = DEFAULT_TIME_ZONE;
    if (calendar != null) {
      timeZone = calendar.getTimeZone();
    }
    return timeZone.getID();
  }

  @SuppressWarnings("unused")
  @UsedByNative
  SizeF getDisplayDpi() {
    return DisplayUtil.getDisplayDpi();
  }

  @SuppressWarnings("unused")
  @UsedByNative
  Size getDisplaySize() {
    return DisplayUtil.getSystemDisplaySize();
  }

  @SuppressWarnings("unused")
  @UsedByNative
  public ResourceOverlay getResourceOverlay() {
    return resourceOverlay;
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

  @SuppressWarnings("unused")
  @UsedByNative
  Size getDeviceResolution() {
    String displaySize =
        android.os.Build.VERSION.SDK_INT < 28
            ? getSystemProperty("sys.display-size")
            : getSystemProperty("vendor.display-size");

    if (displaySize == null) {
      return getDisplaySize();
    }

    String[] sizes = displaySize.split("x");
    if (sizes.length != 2) {
      return getDisplaySize();
    }

    try {
      return new Size(Integer.parseInt(sizes[0]), Integer.parseInt(sizes[1]));
    } catch (NumberFormatException e) {
      return getDisplaySize();
    }
  }

  @SuppressWarnings("unused")
  @UsedByNative
  boolean isNetworkConnected() {
    return networkStatus.isConnected();
  }

  /**
   * Checks if there is no microphone connected to the system.
   *
   * @return true if no device is connected.
   */
  @SuppressWarnings("unused")
  @UsedByNative
  public boolean isMicrophoneDisconnected() {
    // A check specifically for microphones is not available before API 28, so it is assumed that a
    // connected input audio device is a microphone.
    AudioManager audioManager = (AudioManager) appContext.getSystemService(AUDIO_SERVICE);
    AudioDeviceInfo[] devices = audioManager.getDevices(GET_DEVICES_INPUTS);
    if (devices.length > 0) {
      return false;
    }

    // fallback to check for BT voice capable RCU
    InputManager inputManager = (InputManager) appContext.getSystemService(Context.INPUT_SERVICE);
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
  @UsedByNative
  public boolean isMicrophoneMute() {
    AudioManager audioManager = (AudioManager) appContext.getSystemService(AUDIO_SERVICE);
    return audioManager.isMicrophoneMute();
  }

  /**
   * @return true if we have an active network connection and it's on an wireless network.
   */
  @SuppressWarnings("unused")
  @UsedByNative
  boolean isCurrentNetworkWireless() {
    ConnectivityManager connMgr =
        (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    Network activeNetwork = connMgr.getActiveNetwork();
    if (activeNetwork == null) {
      return false;
    }
    NetworkCapabilities activeCapabilities = connMgr.getNetworkCapabilities(activeNetwork);
    if (activeCapabilities == null) {
      return false;
    }
    // Consider anything that's not definitely wired to be wireless.
    return !activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
  }

  /**
   * @return true if the user has enabled accessibility high contrast text in the operating system.
   */
  @SuppressWarnings("unused")
  @UsedByNative
  boolean isAccessibilityHighContrastTextEnabled() {
    AccessibilityManager am =
        (AccessibilityManager) appContext.getSystemService(Context.ACCESSIBILITY_SERVICE);

    try {
      Method m = AccessibilityManager.class.getDeclaredMethod("isHighTextContrastEnabled");

      return m.invoke(am) == Boolean.TRUE;
    } catch (ReflectiveOperationException ex) {
      return false;
    }
  }

  @SuppressWarnings("unused")
  @UsedByNative
  void updateMediaSession(
      int playbackState,
      long actions,
      long positionMs,
      float speed,
      String title,
      String artist,
      String album,
      MediaImage[] artwork,
      long duration) {
    cobaltMediaSession.updateMediaSession(
        playbackState, actions, positionMs, speed, title, artist, album, artwork, duration);
  }

  @SuppressWarnings("unused")
  @UsedByNative
  public void deactivateMediaSession() {
    cobaltMediaSession.deactivateMediaSession();
  }

  /** Returns string for kSbSystemPropertyUserAgentAuxField */
  @SuppressWarnings("unused")
  @UsedByNative
  protected String getUserAgentAuxField() {
    StringBuilder sb = new StringBuilder();
    // Spoof the package name
    sb.append("com.google.android.youtube.tv/5.30.301");
    return sb.toString();
  }

  /** Returns string for kSbSystemPropertyAdvertisingId */
  @SuppressWarnings("unused")
  @UsedByNative
  protected String getAdvertisingId() {
    return this.advertisingId.getId();
  }

  /** Returns boolean for kSbSystemPropertyLimitAdTracking */
  @SuppressWarnings("unused")
  @UsedByNative
  protected boolean getLimitAdTracking() {
    return this.advertisingId.isLimitAdTrackingEnabled();
  }

  @SuppressWarnings("unused")
  @UsedByNative
  AudioOutputManager getAudioOutputManager() {
    return audioOutputManager;
  }

  /** Returns Java layer implementation for AudioPermissionRequester */
  @SuppressWarnings("unused")
  @UsedByNative
  AudioPermissionRequester getAudioPermissionRequester() {
    return audioPermissionRequester;
  }

  void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    audioPermissionRequester.onRequestPermissionsResult(requestCode, permissions, grantResults);
  }

  @SuppressWarnings("unused")
  @UsedByNative
  public void resetVideoSurface() {
    Activity activity = activityHolder.get();
    if (activity instanceof CobaltActivity) {
      ((CobaltActivity) activity).resetVideoSurface();
    }
  }

  @SuppressWarnings("unused")
  @UsedByNative
  public void setVideoSurfaceBounds(final int x, final int y, final int width, final int height) {
    Activity activity = activityHolder.get();
    if (activity instanceof CobaltActivity) {
      ((CobaltActivity) activity).setVideoSurfaceBounds(x, y, width, height);
    }
  }

  /** Return supported hdr types. */
  @SuppressWarnings("unused")
  @UsedByNative
  public int[] getSupportedHdrTypes() {
    Display defaultDisplay = DisplayUtil.getDefaultDisplay();
    if (defaultDisplay == null) {
      return null;
    }

    Display.HdrCapabilities hdrCapabilities = defaultDisplay.getHdrCapabilities();
    if (hdrCapabilities == null) {
      return null;
    }

    return hdrCapabilities.getSupportedHdrTypes();
  }

  /** Return the CobaltMediaSession. */
  public CobaltMediaSession cobaltMediaSession() {
    return cobaltMediaSession;
  }

  public void registerCobaltService(CobaltService.Factory factory) {
    cobaltServiceFactories.put(factory.getServiceName(), factory);
  }

  @SuppressWarnings("unused")
  @UsedByNative
  boolean hasCobaltService(String serviceName) {
    return cobaltServiceFactories.get(serviceName) != null;
  }

  @SuppressWarnings("unused")
  @UsedByNative
  CobaltService openCobaltService(long nativeService, String serviceName) {
    if (cobaltServices.get(serviceName) != null) {
      // Attempting to re-open an already open service fails.
      Log.e(TAG, String.format("Cannot open already open service %s", serviceName));
      return null;
    }
    final CobaltService.Factory factory = cobaltServiceFactories.get(serviceName);
    if (factory == null) {
      Log.e(TAG, String.format("Cannot open unregistered service %s", serviceName));
      return null;
    }
    CobaltService service = factory.createCobaltService(nativeService);
    if (service != null) {
      service.receiveStarboardBridge(this);
      cobaltServices.put(serviceName, service);
    }
    return service;
  }

  public CobaltService getOpenedCobaltService(String serviceName) {
    return cobaltServices.get(serviceName);
  }

  @SuppressWarnings("unused")
  @UsedByNative
  void closeCobaltService(String serviceName) {
    cobaltServices.remove(serviceName);
  }

  /** Deprecated. Returns an incorrect calculation for the appStart for an existing metric. */
  @SuppressWarnings("unused")
  @UsedByNative
  protected long getIncorrectAppStartTimestamp() {
    Activity activity = activityHolder.get();
    if (activity instanceof CobaltActivity) {
      long javaStartTimestamp = ((CobaltActivity) activity).getAppStartTimestamp();
      long cppTimestamp = nativeCurrentMonotonicTime();
      long javaStopTimestamp = System.nanoTime();
      return cppTimestamp
          - (javaStartTimestamp - javaStopTimestamp) / timeNanosecondsPerMicrosecond;
    }
    return 0;
  }

  /** Returns the application start timestamp. */
  @SuppressWarnings("unused")
  @UsedByNative
  protected long getAppStartTimestamp() {
    Activity activity = activityHolder.get();
    if (activity instanceof CobaltActivity) {
      long javaStartTimestamp = ((CobaltActivity) activity).getAppStartTimestamp();
      long cppTimestamp = nativeCurrentMonotonicTime();
      long javaStopTimestamp = System.nanoTime();
      return cppTimestamp
          - (javaStopTimestamp - javaStartTimestamp) / timeNanosecondsPerMicrosecond;
    }
    return 0;
  }

  @SuppressWarnings("unused")
  @UsedByNative
  void reportFullyDrawn() {
    Activity activity = activityHolder.get();
    if (activity != null) {
      activity.reportFullyDrawn();
    }
  }

  @SuppressWarnings("unused")
  @UsedByNative
  public void setCrashContext(String key, String value) {
    crashContext.put(key, value);
    if (this.crashContextUpdateHandler != null) {
      this.crashContextUpdateHandler.onCrashContextUpdate();
    }
  }

  public HashMap<String, String> getCrashContext() {
    return this.crashContext;
  }

  public void registerCrashContextUpdateHandler(CrashContextUpdateHandler handler) {
    this.crashContextUpdateHandler = handler;
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected boolean getIsAmatiDevice() {
    return this.isAmatiDevice;
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected String getBuildFingerprint() {
    return Build.FINGERPRINT;
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected long getPlayServicesVersion() {
    try {
      if (android.os.Build.VERSION.SDK_INT < 28) {
        return appContext
            .getPackageManager()
            .getPackageInfo(GOOGLE_PLAY_SERVICES_PACKAGE, 0)
            .versionCode;
      } else if (android.os.Build.VERSION.SDK_INT < 33) {
        return appContext
            .getPackageManager()
            .getPackageInfo(GOOGLE_PLAY_SERVICES_PACKAGE, 0)
            .getLongVersionCode();
      } else {
        return appContext
            .getPackageManager()
            .getPackageInfo(GOOGLE_PLAY_SERVICES_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            .getLongVersionCode();
      }
    } catch (Exception e) {
      Log.w(TAG, "Unable to query Google Play Services package version", e);
      return 0;
    }
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected void setPreferMinimalPostProcessing(boolean value) {
    Activity activity = activityHolder.get();
    if (activity instanceof CobaltActivity) {
      ((CobaltActivity) activity).setPreferMinimalPostProcessing(value);
    }
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected boolean installAppFromURL(String url) {
    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    executor.execute(() -> {
      java.io.File apkFile = new java.io.File(appContext.getFilesDir(), "downloaded_app.apk");
      boolean success = false;
      try (java.io.InputStream in = new java.net.URL(url).openStream();
           java.io.OutputStream out = new java.io.FileOutputStream(apkFile)) {
        byte[] buffer = new byte[8192];
        int len;
        long total = 0;
        while ((len = in.read(buffer)) != -1) {
          out.write(buffer, 0, len);
          total += len;
        }
        out.flush();
        success = true;
      } catch (Exception e) {
      }
      if (success) {
        mainHandler.post(() -> {
          try {
            android.net.Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                appContext,
                appContext.getPackageName() + ".fileprovider",
                apkFile);
            android.content.Intent installIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            installIntent.setDataAndType(contentUri, "application/vnd.android.package-archive");
            installIntent.setFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = activityHolder.get();
            if (activity != null) {
              activity.startActivity(installIntent);
            } else {
              appContext.startActivity(installIntent);
            }
          } catch (Exception e) {
          }
        });
      } else {
      }
      executor.shutdown();
    });
    return true;
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected String getVersion() {
    try {
      android.content.pm.PackageInfo packageInfo =
          appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
      return packageInfo.versionName;
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      Log.e(TAG, "Failed to get package info", e);
      return "unknown";
    }
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected String getBrandAndModel() {
    return Settings.Global.getString(appContext.getContentResolver(), "device_name");
  }

  // Android TV Home Screen Channels API methods

  private synchronized TvChannelBridge getTvChannelBridge() {
    if (tvChannelBridge == null) {
      tvChannelBridge = new TvChannelBridge(appContext);
    }
    return tvChannelBridge;
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected void updateWatchProgress(String videoJson) {
    getTvChannelBridge().updateWatchProgress(videoJson);
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected void markAsWatched(String videoId) {
    getTvChannelBridge().markAsWatched(videoId);
  }

  @SuppressWarnings("unused")
  @UsedByNative
  protected void updateRecommendations(String videosJson) {
    getTvChannelBridge().updateRecommendations(videosJson);
  }
}
