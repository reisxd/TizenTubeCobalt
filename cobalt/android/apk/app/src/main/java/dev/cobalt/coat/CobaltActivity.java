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

import static dev.cobalt.util.Log.TAG;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import dev.cobalt.browser.CobaltContentBrowserClient;
import dev.cobalt.coat.javabridge.CobaltJavaScriptAndroidObject;
import dev.cobalt.coat.javabridge.CobaltJavaScriptInterface;
import dev.cobalt.coat.javabridge.HTMLMediaElementExtension;
import dev.cobalt.dial.DIALServer;
import dev.cobalt.dial.DiscoveryServer;
import dev.cobalt.media.AudioOutputManager;
import dev.cobalt.media.MediaCodecCapabilitiesLogger;
import dev.cobalt.media.VideoSurfaceView;
import dev.cobalt.shell.Shell;
import dev.cobalt.shell.ShellManager;
import dev.cobalt.shell.ShellManagerJni;
import dev.cobalt.shell.StartupGuard;
import dev.cobalt.util.DisplayUtil;
import dev.cobalt.util.JavaSwitches;
import dev.cobalt.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.chromium.base.CommandLine;
import org.chromium.base.library_loader.LibraryLoader;
import org.chromium.base.library_loader.LibraryProcessType;
import org.chromium.base.memory.MemoryPressureMonitor;
import org.chromium.base.memory.MemoryPressureUma;
import org.chromium.base.metrics.RecordHistogram;
import org.chromium.content.browser.input.ImeAdapterImpl;
import org.chromium.content_public.browser.BrowserStartupController;
import org.chromium.content_public.browser.DeviceUtils;
import org.chromium.content_public.browser.JavascriptInjector;
import org.chromium.content_public.browser.WebContents;
import org.chromium.net.NetworkChangeNotifier;
import org.chromium.ui.base.ActivityWindowAndroid;
import org.chromium.ui.base.IntentRequestTracker;

/* Abstract activity used by AndroidTV. Extends the base with the Chromium content-shell wiring. */
public abstract class CobaltActivity extends BaseCobaltActivity {
  private static final String META_DATA_ENABLE_SPLASH_SCREEN = "cobalt.ENABLE_SPLASH_SCREEN";
  private static final String META_DATA_ENABLE_FEATURES = "cobalt.ENABLE_FEATURES";
  private static final String YOUTUBE_URL = "https://www.youtube.com/tv";
  private static final String COBALT_USING_ANDROID_OVERLAY = "cobalt-using-android-overlay";

  // How many seconds before the app exits if it fails to land YouTube home page.
  private static final int DEFAULT_HANG_APP_CRASH_TIMEOUT_SECONDS = 120;

  // Maintain the list of JavaScript-exposed objects as a member variable
  // to prevent them from being garbage collected prematurely.
  private final List<CobaltJavaScriptAndroidObject> mJavaScriptAndroidObjectList =
      new ArrayList<>();
  private final Map<String, String> mJavaSwitches = new HashMap<>();

  @SuppressWarnings("unused")
  private CobaltA11yHelper mA11yHelper;

  private VideoSurfaceView mVideoSurfaceView;

  private boolean mForceCreateNewVideoSurfaceView;

  private ShellManager mShellManager;
  private ActivityWindowAndroid mWindowAndroid;
  private Intent mLastSentIntent;
  private String mStartupUrl;
  private IntentRequestTracker mIntentRequestTracker;
  private long mSyntheticBackDownTimeMs;
  private int mVideoSurfaceX;
  private int mVideoSurfaceY;
  private int mVideoSurfaceWidth;
  private int mVideoSurfaceHeight;
  // Tracks the status of the FLAG_KEEP_SCREEN_ON window flag.
  private Boolean mIsKeepScreenOnEnabled = false;
  private Runnable mFreezeRunnable;
  private final Handler mHandler = new Handler(Looper.getMainLooper());

  private boolean mIsCobaltUsingAndroidOverlay;

  private NetworkChangeNotifier.ConnectionTypeObserver mNetworkRecoveryObserver;
  private boolean mIsNetworkRecoveryObserverRegistered = false;

  private volatile boolean mHasHiddenSplashScreen = false;

  public boolean hasHiddenSplashScreen() {
    return mHasHiddenSplashScreen;
  }

  private static final long MIN_RETRY_INTERVAL_MS = 1000L;
  private long mLastRetryTimestampMs = 0L;

  private static final String RETRY_PARAM_KEY = "netdialog_retry";
  private static final AtomicInteger sRetryCount = new AtomicInteger(0);

  private String mStartDeepLink;

  private Object mBackInvokedCallback;

  // Tracks if a physical/hardware back button press is currently in progress.
  // On Android 13+ (API >= 33) with predictive back gesture enabled, the OS dispatches physical
  // back button presses as both key events (onKeyDown/onKeyUp) and OnBackInvokedCallback.
  // To prevent double-triggering back navigation in the web application (which maps back keys
  // to Escape), this flag is used by OnBackInvokedCallback to detect physical key presses and
  // bypass simulated key dispatching.
  private boolean mPhysicalBackKeyPressed = false;
  private final DisplayUtil.Listener mDisplayListener =
      new DisplayUtil.Listener() {
        @Override
        public void onDisplayChanged(int displayId) {
          if (displayId == Display.DEFAULT_DISPLAY) {
            checkDisplayState();
          }
        }
      };
  private boolean mWasDisplayOn = true;
  private static boolean sIsMemoryPressureInitialized = false;

  @VisibleForTesting
  static void appendMetaDataArgs(@NonNull List<String> args, @Nullable Bundle metaData) {
    if (metaData == null) {
      return;
    }

    boolean enableSplashScreen = metaData.getBoolean(META_DATA_ENABLE_SPLASH_SCREEN, true);
    if (!enableSplashScreen) {
      args.add("--enable-features=DisableSplashScreen");
    }

    String enableFeatures = metaData.getString(META_DATA_ENABLE_FEATURES);
    if (TextUtils.isEmpty(enableFeatures)) {
      return;
    }

    // CommandLineOverrideHelper will merge this with other --enable-features flags
    // It also accepts semi-colon-separated list of features.
    // https://github.com/youtube/cobalt/blob/6407cbdf6573f0b5fcae4a8fa6f46a3198b3d42b/cobalt/android/apk/app/src/main/java/dev/cobalt/coat/CommandLineOverrideHelper.java#L139-L167
    args.add("--enable-features=" + enableFeatures);
  }

  private void appendIntentArgs(@NonNull List<String> args) {
    if (isReleaseBuild()) {
      return;
    }

    String[] intentArgs = getCommandLineParamsFromIntent(getIntent(), COMMAND_LINE_ARGS_KEY);
    if (intentArgs == null) {
      return;
    }
    Collections.addAll(args, intentArgs);
  }

  @VisibleForTesting
  @NonNull
  List<String> getCommandLineArgs() {
    List<String> args = new ArrayList<>();
    if (isDevelopmentBuild()) {
      args.add("--remote-allow-origins=https://chrome-devtools-frontend.appspot.com");
    }
    appendMetaDataArgs(args, getActivityMetaData());
    args.addAll(JavaSwitches.getExtraCommandLineArgs(getJavaSwitches()));
    appendIntentArgs(args);
    return args;
  }

  // Initially copied from ContentShellActiviy.java
  protected void createContent(final Bundle savedInstanceState) {
    StartupGuard.getInstance().setStartupMilestone(StartupGuard.JAVA_ACTIVITY_CREATED);

    // Initializing the command line must occur before loading the library.
    if (!CommandLine.isInitialized()) {
      CommandLine.init(null);
      CommandLineOverrideHelper.getFlagOverrides(getCommandLineArgs());
    }
    mIsCobaltUsingAndroidOverlay =
        CommandLine.getInstance().hasSwitch(COBALT_USING_ANDROID_OVERLAY);

    DeviceUtils.updateDeviceSpecificUserAgentSwitch(this);

    StartupGuard.getInstance().setStartupMilestone(StartupGuard.PRE_LIBRARY_LOADER_INIT);
    // This initializes JNI and ends up calling JNI_OnLoad in native code
    LibraryLoader.getInstance().ensureInitialized();
    StartupGuard.getInstance().setStartupMilestone(StartupGuard.POST_LIBRARY_LOADER_INIT);

    // StarboardBridge initialization must happen right after library loading,
    // before Browser/Content module is started. It currently tracks its own JNI state
    // variables, and needs to set up an early copy.

    // TODO(b/374147993): how to handle deeplink in Chrobalt?
    mStartDeepLink = getIntentUrlAsString(getIntent());
    if (mStartDeepLink == null) {
      Log.w(TAG, "startDeepLink cannot be null, set it to empty string.");
      mStartDeepLink = "";
    }

    StartupGuard.getInstance().setStartupMilestone(StartupGuard.PRE_STARBOARD_BRIDGE_INIT);
    if (getStarboardBridge() == null) {
      // Cold start - Instantiate the singleton StarboardBridge.
      RecordHistogram.recordBooleanHistogram("Cobalt.Android.ColdStart", true);
      if (CommandLine.getInstance().hasSwitch("use-custom-android-fonts-xml")) {
        FontUtil.copyFontsXml(getApplicationContext());
      }
      StarboardBridge starboardBridge = createStarboardBridge(getArgs(), mStartDeepLink);
      ((StarboardBridge.HostApplication) getApplication()).setStarboardBridge(starboardBridge);
      starboardBridge.onActivityCreate(this);
    } else {
      // Warm start - Pass the deep link to the running Starboard app.
      if (savedInstanceState == null) {
        RecordHistogram.recordBooleanHistogram("Cobalt.Android.ColdStart", false);
      }
      getStarboardBridge().handleDeepLink(mStartDeepLink);
    }
    StartupGuard.getInstance().setStartupMilestone(StartupGuard.POST_STARBOARD_BRIDGE_INIT);

    mShellManager = new ShellManager(this);
    final boolean listenToActivityState = true;
    mIntentRequestTracker = IntentRequestTracker.createFromActivity(this);
    mWindowAndroid =
        new ActivityWindowAndroid(
            this,
            listenToActivityState,
            mIntentRequestTracker,
            /* insetObserver= */ null,
            /* trackOcclusion= */ false);
    mIntentRequestTracker.restoreInstanceState(savedInstanceState);
    mShellManager.setWindow(mWindowAndroid);
    // Set up the animation placeholder to be the SurfaceView. This disables the
    // SurfaceView's 'hole' clipping during animations that are notified to the window.
    mWindowAndroid.setAnimationPlaceholderView(
        mShellManager.getContentViewRenderView().getAnchorView());
    mA11yHelper =
        new CobaltA11yHelper(this, mShellManager.getContentViewRenderView().getAnchorView());

    maybeRegisterNetworkRecoveryObserver();

    if (mStartupUrl == null || mStartupUrl.isEmpty()) {
      String[] args = getStarboardBridge().getArgs();
      mStartupUrl =
          Arrays.stream(args)
              .filter(line -> line.contains(URL_ARG))
              .findAny()
              .map(arg -> arg.substring(arg.indexOf(URL_ARG) + URL_ARG.length()))
              .orElse(null);
    }

    // META_DATA_APP_URL is configured to be the same as hardcoded YOUTUBE_URL.
    // If the app is used to start other web applications e.g google.com, because there will have no
    // Kabuki web application code to call h5vcc.system.HideSplashScreen().
    // We should disarm Startup Guard now.
    if (TextUtils.isEmpty(mStartupUrl) || !mStartupUrl.startsWith(YOUTUBE_URL)) {
      Log.i(TAG, "Non-Youtube startup URL detected.");
      StartupGuard.getInstance().disarm();
    }

    StartupGuard.getInstance().setStartupMilestone(StartupGuard.ACTIVITY_WINDOW_CONFIGURED);
    // TODO(b/377025559): Bring back WebTests launch capability
    if (useStarboardLifeCycle()) {
      AppEventBridge.handleStartEvent(
          getStarboardBridge().getArgs(), mStartDeepLink, mTimeInNanoseconds / 1000L);
      // NOTE: This log message is hard-coded in smoke tests to detect browser startup success.
      // See ManekiBaseDeviceUtil.CHROBALT_BROWSER_READY_REGEX in the internal test suite.
      Log.i(TAG, "Browser process init succeeded");

      if (isDestroyed() || isFinishing()) {
        Log.w(TAG, "Activity is finishing or destroyed; skipping finishInitialization.");
        return;
      }
      finishInitialization(savedInstanceState);
    } else {
      BrowserStartupController.getInstance()
          .startBrowserProcessesAsync(
              LibraryProcessType.PROCESS_BROWSER,
              false, // Do not start a separate GPU process
              // TODO(b/377025565): Figure out what this means
              false, // Do not start in "minimal" or paused mode
              new BrowserStartupController.StartupCallback() {
                @Override
                public void onSuccess() {
                  // NOTE: This log message is hard-coded in smoke tests to detect browser startup
                  // success.
                  // See ManekiBaseDeviceUtil.CHROBALT_BROWSER_READY_REGEX in the internal test
                  // suite.
                  Log.i(TAG, "Browser process init succeeded");

                  if (isDestroyed() || isFinishing()) {
                    Log.w(
                        TAG, "Activity is finishing or destroyed; skipping finishInitialization.");
                    return;
                  }

                  finishInitialization(savedInstanceState);
                  getStarboardBridge().measureAppStartTimestamp();
                }

                @Override
                public void onFailure() {
                  Log.e(TAG, "Browser process init failed");
                  initializationFailed();
                }
              });
    }
  }

  // Initially copied from ContentShellActiviy.java
  private void finishInitialization(Bundle savedInstanceState) {

    // Initialize the platform's AudioSinkImpl. This is called here as this must come after the
    // browser client's feature list and field trials are initialized. The feature list and field
    // trials are initialized in CobaltContentBrowserClient::CreateFeatureListAndFieldTrials().
    getStarboardBridge().initializePlatformAudioSink();

    // Load an empty page to let shell create WebContents. Override Shell.java's
    // onWebContentsReady()
    // to only continue with initializeJavaBridge() and setting the webContents once it's confirmed
    // that the webContents are correctly created not null.
    mShellManager.launchShell(
        "",
        mStartDeepLink,
        new Shell.OnWebContentsReadyListener() {
          @Override
          public void onWebContentsReady() {
            // Inject JavaBridge objects to the WebContents.
            initializeJavaBridge();
            getStarboardBridge().setWebContents(getActiveWebContents());

            // Load the `url` with the same shell we created above.
            mStartupUrl = ShellManagerJni.get().appendMigrationStatus(mStartupUrl);
            Log.i(TAG, "shellManager load url:" + mStartupUrl);
            mShellManager.getActiveShell().loadUrl(mStartupUrl);

            // Load splash screen.
            mShellManager.getActiveShell().loadSplashScreenWebContents();
          }
        });
  }

  // Initially copied from ContentShellActiviy.java
  private void initializationFailed() {
    Log.e(TAG, "ContentView initialization failed.");
    Toast.makeText(
            CobaltActivity.this, R.string.browser_process_initialization_failed, Toast.LENGTH_SHORT)
        .show();
    finish();
  }

  protected boolean dispatchKeyEventToIme(KeyEvent event) {
    ImeAdapterImpl imeAdapter = getImeAdapterImpl();
    return imeAdapter != null && imeAdapter.dispatchKeyEvent(event);
  }

  protected boolean dispatchKeyEventToIme(int keyCode, int action) {
    // |KeyEvent| needs to be created with |downTime| and |eventTime| set. If they are not set the
    // app closes.
    long eventTime = SystemClock.uptimeMillis();
    long downTime = eventTime;
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      if (action == KeyEvent.ACTION_DOWN) {
        mSyntheticBackDownTimeMs = eventTime;
      } else if (action == KeyEvent.ACTION_UP && mSyntheticBackDownTimeMs != 0) {
        downTime = mSyntheticBackDownTimeMs;
      }
    }

    KeyEvent syntheticEvent = new KeyEvent(downTime, eventTime, action, keyCode, 0);
    boolean handled = dispatchKeyEventToIme(syntheticEvent);
    if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_UP) {
      mSyntheticBackDownTimeMs = 0;
    }
    return handled;
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      mPhysicalBackKeyPressed = true;
    }
    // If input is a from a gamepad button, it shouldn't be dispatched to IME which incorrectly
    // consumes the event as a VKEY_UNKNOWN
    if (KeyEvent.isGamepadButton(keyCode) || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || KeyboardUtils.isPartnerFallbackKey(keyCode)) {
      return super.onKeyDown(keyCode, event);
    }
    return dispatchKeyEventToIme(event) || super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (KeyEvent.isGamepadButton(keyCode) || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || KeyboardUtils.isPartnerFallbackKey(keyCode)) {
      return super.onKeyUp(keyCode, event);
    }
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      mPhysicalBackKeyPressed = false;
    }
    return dispatchKeyEventToIme(event) || super.onKeyUp(keyCode, event);
  }

  // Initially copied from ContentShellActiviy.java
  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    mIntentRequestTracker.onActivityResult(requestCode, resultCode, data);
  }

  // Initially copied from ContentShellActiviy.java
  @Override
  public void startActivity(Intent i) {
    mLastSentIntent = i;
    super.startActivity(i);
  }

  public Intent getLastSentIntent() {
    return mLastSentIntent;
  }

  /**
   * @return The {@link ShellManager} configured for the activity or null if it has not been created
   *     yet.
   */
  public ShellManager getShellManager() {
    return mShellManager;
  }

  /**
   * @return The currently visible {@link Shell} or null if one is not showing.
   */
  public Shell getActiveShell() {
    return mShellManager != null ? mShellManager.getActiveShell() : null;
  }

  /**
   * @return The {@link WebContents} owned by the currently visible {@link Shell} or null if one is
   *     not showing.
   */
  @Nullable
  public WebContents getActiveWebContents() {
    Shell shell = getActiveShell();
    return shell != null ? shell.getWebContents() : null;
  }

  @Nullable
  protected ImeAdapterImpl getImeAdapterImpl() {
    WebContents webContents = getActiveWebContents();
    return webContents != null ? ImeAdapterImpl.fromWebContents(webContents) : null;
  }

  @VisibleForTesting
  void setupStartupGuard() {
    Map<String, String> switches = getJavaSwitches();
    if (switches.containsKey(JavaSwitches.DISABLE_STARTUP_GUARD)) {
      Log.i(TAG, "StartupGuard is disabled by Java switch.");
      return;
    }

    long timeout = DEFAULT_HANG_APP_CRASH_TIMEOUT_SECONDS;
    String intervalStr = switches.get(JavaSwitches.STARTUP_GUARD_INTERVAL_IN_SECONDS);
    if (intervalStr != null) {
      try {
        long parsedTimeout = Long.parseLong(intervalStr);
        if (parsedTimeout > 0) {
          timeout = parsedTimeout;
        } else {
          Log.w(TAG, "Invalid StartupGuard interval (must be positive): " + intervalStr);
        }
      } catch (NumberFormatException e) {
        Log.w(TAG, "Invalid StartupGuard interval string: " + intervalStr);
      }
    }
    Log.i(TAG, "Arming StartupGuard with " + timeout + " second timeout.");
    StartupGuard.getInstance().scheduleCrash(timeout);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    startDIALServers();
    
    setupStartupGuard();
    createContent(savedInstanceState);
    if (!NetworkChangeNotifier.isInitialized()) {
      NetworkChangeNotifier.init();
      NetworkChangeNotifier.setAutoDetectConnectivityState(true);
    }
    if (!sIsMemoryPressureInitialized) {
      MemoryPressureUma.initializeForBrowser();
      MemoryPressureMonitor.INSTANCE.registerComponentCallbacks();
      sIsMemoryPressureInitialized = true;
    }

    if (!mIsCobaltUsingAndroidOverlay) {
      mVideoSurfaceView = new VideoSurfaceView(this);
      // The player reports its bounds after the SurfaceView is attached. Keep
      // its initial full-screen layout from covering the web UI before then.
      mVideoSurfaceView.setAlpha(0f);
      addContentView(
          mVideoSurfaceView,
          new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
      setVideoSurfaceBounds(
          mVideoSurfaceX, mVideoSurfaceY, mVideoSurfaceWidth, mVideoSurfaceHeight);
    } else {
      Log.i(TAG, "Do not create VideoSurfaceView.");
    }
    StartupGuard.getInstance().setStartupMilestone(StartupGuard.ACTIVITY_ON_START);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      mBackInvokedCallback = OnBackInvokedHelper.register(this);
    }
  }

  /**
   * Initializes the Java Bridge to allow communication between Java and JavaScript. This method
   * injects Java objects into the WebView and loads corresponding JavaScript code.
   */
  private void initializeJavaBridge() {
    Log.i(TAG, "initializeJavaBridge");

    WebContents webContents = getActiveWebContents();
    if (webContents == null) {
      throw new RuntimeException(
          "webContents is null in initializeJavaBridge. This should never happen.");
    }

    // --- Initialize the Java Bridge ---

    // 1. Gather all Java objects that need to be exposed to JavaScript.
    // TODO(b/379701165): consider to refine the way to add JavaScript interfaces.
    mJavaScriptAndroidObjectList.add(new HTMLMediaElementExtension(this));

    // 2. Use JavascriptInjector to inject Java objects into the WebContents.
    //    This makes the annotated methods in these objects accessible from JavaScript.
    JavascriptInjector javascriptInjector = JavascriptInjector.fromWebContents(webContents);

    javascriptInjector.setAllowInspection(true);
    for (CobaltJavaScriptAndroidObject javascriptAndroidObject : mJavaScriptAndroidObjectList) {
      Log.d(
          TAG,
          "Add JavaScriptAndroidObject:" + javascriptAndroidObject.getJavaScriptInterfaceName());
      javascriptInjector.addPossiblyUnsafeInterface(
          javascriptAndroidObject,
          javascriptAndroidObject.getJavaScriptInterfaceName(),
          CobaltJavaScriptInterface.class,
          /* originAllowlist= */ new ArrayList<String>());
    }
  }

  /**
   * Starts the DIAL servers (HTTP and UDP discovery) as a fallback mechanism.
   * This is called in onStart() to ensure the servers are running even if the
   * BootCompleteReceiver didn't trigger or if the app is started before boot completed.
   */
  private void startDIALServers() {
    try {
      DIALServer.getInstance().start(this);
      DiscoveryServer.getInstance().start(this);
      Log.i(TAG, "DIAL servers started");
    } catch (Exception e) {
      Log.e(TAG, "Failed to start DIAL servers: " + e.getMessage());
    }
  }

  /**
   * Instantiates the StarboardBridge. Apps not supporting sign-in should inject an instance of
   * NoopUserAuthorizer. Apps may subclass StarboardBridge if they need to override anything.
   */
  protected abstract StarboardBridge createStarboardBridge(String[] args, String startDeepLink);

  @Override
  protected StarboardBridge getStarboardBridge() {
    return ((StarboardBridge.HostApplication) getApplication()).getStarboardBridge();
  }

  @Override
  protected void onStart() {
    StartupGuard.getInstance().setStartupMilestone(StartupGuard.ACTIVITY_ON_RESUME);
    
    // Start DIAL servers for casting discovery
    startDIALServers();
    
    if (isDevelopmentBuild()) {
      getStarboardBridge().getAudioOutputManager().dumpAllOutputDevices();
      MediaCodecCapabilitiesLogger.dumpAllDecoders();
    }
    if (mIsCobaltUsingAndroidOverlay) {
      Log.i(TAG, "Use AndroidOverlay for Video SurfaceView.");
    } else if (mForceCreateNewVideoSurfaceView) {
      Log.w(TAG, "Force to create a new video surface.");
      createNewSurfaceView();
    }

    DisplayUtil.cacheDefaultDisplay(this);
    DisplayUtil.addDisplayListener(this);
    AudioOutputManager.addAudioDeviceListener(this);

    if (isNvidiaShield()) {
      mWasDisplayOn = isDisplayOn();
      registerDisplayListener();
    }

    super.onStart();

    if (isNvidiaShield() && mFreezeRunnable != null) {
      mHandler.removeCallbacks(mFreezeRunnable);
      mFreezeRunnable = null;
    }

    if (useStarboardLifeCycle()) {
      AppEventBridge.handleRevealEvent(System.nanoTime() / 1000L);
    } else {
      WebContents webContents = getActiveWebContents();
      if (webContents != null
          && (isNvidiaShield() || getJavaSwitches().containsKey(JavaSwitches.ENABLE_FREEZE))) {
        // document.onresume event
        webContents.onResume();
      }
      // visibility:visible event
      if (isNvidiaShield()) {
        updateShellActivityVisible(mWasDisplayOn);
      } else {
        updateShellActivityVisible(true);
      }
    }
    MemoryPressureMonitor.INSTANCE.enablePolling(false);

    StartupGuard.getInstance().setStartupMilestone(StartupGuard.ACTIVITY_ON_PAUSE);
  }

  @Override
  protected void onPause() {
    mPhysicalBackKeyPressed = false;
    if (useStarboardLifeCycle()) {
      AppEventBridge.handleBlurEvent(System.nanoTime() / 1000L);
    } else {
      CobaltContentBrowserClient.dispatchBlur();
    }
    super.onPause();
  }

  @Override
  protected void onStop() {
    long stopTimestamp = System.nanoTime() / 1000L;
    if (isNvidiaShield()) {
      unregisterDisplayListener();
    }
    super.onStop();

    if (useStarboardLifeCycle()) {
      if (getJavaSwitches().containsKey(JavaSwitches.ENABLE_FREEZE)) {
        // If ENABLE_FREEZE is specified, fire freeze event immediately
        AppEventBridge.handleFreezeEvent(stopTimestamp);
      } else {
        AppEventBridge.handleConcealEvent(stopTimestamp);
      }
    } else {
      // visibility:hidden event
      updateShellActivityVisible(false);
      WebContents webContents = getActiveWebContents();
      if (webContents != null) {
        if (isNvidiaShield()) {
          if (mFreezeRunnable != null) {
            mHandler.removeCallbacks(mFreezeRunnable);
          }
          mFreezeRunnable =
              new Runnable() {
                @Override
                public void run() {
                  WebContents currentWebContents = getActiveWebContents();
                  if (currentWebContents != null) {
                    currentWebContents.onFreeze();
                  }
                  mFreezeRunnable = null;
                }
              };
          mHandler.postDelayed(mFreezeRunnable, 1500);
        } else if (getJavaSwitches().containsKey(JavaSwitches.ENABLE_FREEZE)) {
          // If ENABLE_FREEZE is specified, fire freeze event immediately
          webContents.onFreeze();
        }
      }
    }

    // Persist pending cookie and DOM storage writes so they are not lost if the
    // process is killed while the app is in the background.
    CobaltContentBrowserClient.flushCookiesAndLocalStorage();

    if (VideoSurfaceView.getCurrentSurface() != null) {
      mForceCreateNewVideoSurfaceView = true;
    }
    MemoryPressureMonitor.INSTANCE.disablePolling();

    // Set the SurfaceView to fullscreen.
    View rootView = getWindow().getDecorView();
    setVideoSurfaceBounds(0, 0, rootView.getWidth(), rootView.getHeight());
  }

  @Override
  protected void onResume() {
    super.onResume();
    StartupGuard.getInstance().setStartupMilestone(StartupGuard.ACTIVITY_ON_STOP);
    checkAndRetryOnNetworkOnline();
    View rootView = getWindow().getDecorView().getRootView();
    if (rootView != null && rootView.isAttachedToWindow() && !rootView.hasFocus()) {
      rootView.requestFocus();
      Log.i(TAG, "Request focus on the root view on resume.");
    }
    if (useStarboardLifeCycle()) {
      AppEventBridge.handleFocusEvent(System.nanoTime() / 1000L);
    } else {
      CobaltContentBrowserClient.dispatchFocus();
    }
    StartupGuard.getInstance().setStartupMilestone(StartupGuard.ACTIVITY_ON_DESTROY);
  }

  @Override
  protected void onDestroy() {
    unregisterNetworkRecoveryObserver();
    if (isNvidiaShield()) {
      unregisterDisplayListener();
      if (mFreezeRunnable != null) {
        mHandler.removeCallbacks(mFreezeRunnable);
        mFreezeRunnable = null;
      }
    }
    if (mShellManager != null) {
      mShellManager.destroy();
    }
    if (mWindowAndroid != null) {
      mWindowAndroid.destroy();
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      OnBackInvokedHelper.unregister(this, mBackInvokedCallback);
      mBackInvokedCallback = null;
    }
    super.onDestroy();
  }

  public void onSplashScreenHidden() {
    mHasHiddenSplashScreen = true;
    unregisterNetworkRecoveryObserver();
  }

  private void maybeRegisterNetworkRecoveryObserver() {
    if (mIsNetworkRecoveryObserverRegistered || mHasHiddenSplashScreen) {
      return;
    }
    if (mNetworkRecoveryObserver == null) {
      mNetworkRecoveryObserver =
          new NetworkChangeNotifier.ConnectionTypeObserver() {
            @Override
            public void onConnectionTypeChanged(int connectionType) {
              checkAndRetryOnNetworkOnline();
            }
          };
    }
    NetworkChangeNotifier.init();
    NetworkChangeNotifier.addConnectionTypeObserver(mNetworkRecoveryObserver);
    mIsNetworkRecoveryObserverRegistered = true;
  }

  private void unregisterNetworkRecoveryObserver() {
    if (!mIsNetworkRecoveryObserverRegistered || mNetworkRecoveryObserver == null) {
      return;
    }
    mIsNetworkRecoveryObserverRegistered = false;
    NetworkChangeNotifier.removeConnectionTypeObserver(mNetworkRecoveryObserver);
  }

  public void checkAndRetryOnNetworkOnline() {
    if (mHasHiddenSplashScreen) {
      unregisterNetworkRecoveryObserver();
      return;
    }
    if (!NetworkChangeNotifier.isOnline()) {
      return;
    }
    WebContents webContents = getActiveWebContents();
    if (webContents != null && webContents.isLoading()) {
      return;
    }

    long now = SystemClock.elapsedRealtime();
    if (now - mLastRetryTimestampMs < MIN_RETRY_INTERVAL_MS) {
      // Avoid Network Flapping Retry Storms
      return;
    }
    mLastRetryTimestampMs = now;

    StarboardBridge bridge = getStarboardBridge();
    if (bridge != null && bridge.getPlatformError() != null) {
      Log.i(TAG, "Network is online and platform error is active; retrying URL load.");
      bridge.getPlatformError().retry();
    } else {
      Log.i(TAG, "Network is online and splash screen never hidden; reloading URL.");
      reloadUrl(null);
    }
  }

  /** Performs reload of the target/current URL or active WebContents. */
  public void reloadUrl(@Nullable String targetUrl) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mHandler.post(() -> reloadUrl(targetUrl));
      return;
    }

    WebContents webContents = getActiveWebContents();
    String currentUrl = targetUrl != null ? targetUrl : "";
    if (currentUrl.isEmpty() && webContents != null && webContents.getVisibleUrl() != null) {
      Log.i(TAG, "No URL provided, using visible URL");
      currentUrl = webContents.getVisibleUrl().getSpec();
    }

    if ((currentUrl.isEmpty() || "about:blank".equals(currentUrl))
        && !TextUtils.isEmpty(mStartupUrl)) {
      Log.i(TAG, "URL is blank or empty, falling back to startup URL: " + mStartupUrl);
      currentUrl = mStartupUrl;
    }

    int retryCount = sRetryCount.incrementAndGet();

    if (currentUrl.isEmpty()) {
      if (webContents != null) {
        Log.i(TAG, "Visible URL and fallback URL are empty, reloading without adding retry param");
        webContents.getNavigationController().reload(/* checkForRepost= */ true);
      }
    } else {
      if (getActiveShell() != null) {
        getActiveShell().loadUrl(addRetryUrlParam(currentUrl, retryCount));
      }
    }
  }

  /**
   * Adds a retry param to the URL if not already present to differentiate bootstrap requests that
   * originate from a network dialog retry. Note: Uri.Builder handles appending query parameters
   * before the fragment (hash) correctly.
   */
  @VisibleForTesting
  static String addRetryUrlParam(String url, int count) {
    Uri parsedUri = Uri.parse(url);
    if (!parsedUri.isHierarchical()) {
      return url;
    }
    Uri.Builder uriBuilder = parsedUri.buildUpon();

    uriBuilder.query(null);
    boolean retryParamAdded = false;

    for (String key : parsedUri.getQueryParameterNames()) {
      if (RETRY_PARAM_KEY.equals(key)) {
        if (!retryParamAdded) {
          uriBuilder.appendQueryParameter(key, String.valueOf(count));
          retryParamAdded = true;
        }
      } else {
        for (String value : parsedUri.getQueryParameters(key)) {
          uriBuilder.appendQueryParameter(key, value);
        }
      }
    }

    if (!retryParamAdded) {
      uriBuilder.appendQueryParameter(RETRY_PARAM_KEY, String.valueOf(count));
    }

    String result = uriBuilder.build().toString();
    Log.i(TAG, "Reloading URL with retry param: " + result);
    return result;
  }

  @VisibleForTesting
  static void resetRetryCount() {
    sRetryCount.set(0);
  }

  /** Overridden by Kimono to provide specific Java switch configurations. */
  protected Map<String, String> getJavaSwitches() {
    return this.mJavaSwitches;
  }

  @Override
  public boolean useStarboardLifeCycle() {
    return getJavaSwitches().containsKey(JavaSwitches.USE_STARBOARD_LIFECYCLE)
        || (CommandLine.isInitialized()
            && CommandLine.getInstance().hasSwitch(JavaSwitches.USE_STARBOARD_LIFECYCLE_SWITCH));
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    getStarboardBridge().onRequestPermissionsResult(requestCode, permissions, grantResults);
  }

  public void resetVideoSurface() {
    if (mIsCobaltUsingAndroidOverlay) {
      return;
    }
    runOnUiThread(
        new Runnable() {
          @Override
          public void run() {
            createNewSurfaceView();
          }
        });
  }

  public void setVideoSurfaceBounds(final int x, final int y, final int width, final int height) {
    if (mIsCobaltUsingAndroidOverlay) {
      return;
    }
    final boolean videoSurfaceVisible = width > 0 && height > 0;
    runOnUiThread(
        new Runnable() {
          @Override
          public void run() {
            mVideoSurfaceX = x;
            mVideoSurfaceY = y;
            mVideoSurfaceWidth = width;
            mVideoSurfaceHeight = height;
            if (mVideoSurfaceView == null) {
              return;
            }
            // Keep the decoder's Surface alive, but close its visible hole when
            // the player has no video bounds. Otherwise the previous video
            // rectangle can remain over the web UI and hide its controls.
            mVideoSurfaceView.setAlpha(videoSurfaceVisible ? 1f : 0f);
            if (!videoSurfaceVisible) {
              return;
            }

            LayoutParams layoutParams = mVideoSurfaceView.getLayoutParams();
            // Since mVideoSurfaceView is added directly to the Activity's content view, which is a
            // FrameLayout, we expect its layout params to become FrameLayout.LayoutParams.
            if (layoutParams instanceof FrameLayout.LayoutParams frameLayoutParams) {
              frameLayoutParams.setMargins(x, y, x + width, y + height);
            } else {
              Log.w(
                  TAG,
                  "Unexpected video surface layout params class "
                      + layoutParams.getClass().getName());
            }
            layoutParams.width = width;
            layoutParams.height = height;
            // Even though as a NativeActivity we're not using the Android UI framework, by setting
            // the  layout params it will force a layout to be requested. That will cause the
            // SurfaceView to position its underlying Surface to match the screen coordinates of
            // where the view would be in a UI layout and to set the surface transform matrix to
            // match the view's size.
            mVideoSurfaceView.setLayoutParams(layoutParams);
          }
        });
  }

  private void createNewSurfaceView() {
    if (mIsCobaltUsingAndroidOverlay) {
      return;
    }
    ViewParent parent = mVideoSurfaceView.getParent();
    if (parent instanceof FrameLayout frameLayout) {
      int index = frameLayout.indexOfChild(mVideoSurfaceView);
      frameLayout.removeView(mVideoSurfaceView);
      Log.i(TAG, "removed mVideoSurfaceView at index:" + index);

      mVideoSurfaceView = new VideoSurfaceView(this);
      mVideoSurfaceView.setAlpha(0f);
      frameLayout.addView(
          mVideoSurfaceView,
          index,
          new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
      setVideoSurfaceBounds(
          mVideoSurfaceX, mVideoSurfaceY, mVideoSurfaceWidth, mVideoSurfaceHeight);
      Log.i(TAG, "inserted new mVideoSurfaceView at index:" + index);
    } else {
      Log.w(TAG, "Unexpected surface view parent class " + parent.getClass().getName());
    }
  }

  public void evaluateJavaScript(String jsCode) {
    // evaluateJavaScript must run on UI thread.
    runOnUiThread(
        new Runnable() {
          @Override
          public void run() {
            WebContents webContents = getActiveWebContents();
            if (webContents != null) {
              webContents.evaluateJavaScript(jsCode, null);
            }
          }
        });
  }

  public void toggleKeepScreenOn(boolean keepOn) {
    if (mIsKeepScreenOnEnabled != keepOn) {
      runOnUiThread(
          new Runnable() {
            @Override
            public void run() {
              if (keepOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                Log.i(TAG, "Screen keep-on enabled for video playback");
              } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                Log.i(TAG, "Screen keep-on disabled");
              }
            }
          });
      mIsKeepScreenOnEnabled = keepOn;
    }
  }

  private void updateShellActivityVisible(boolean isVisible) {
    if (mShellManager != null) {
      mShellManager.onActivityVisible(isVisible);
    }
  }

  private static boolean isNvidiaShield() {
    return "NVIDIA".equalsIgnoreCase(Build.MANUFACTURER)
        || "NVIDIA".equalsIgnoreCase(Build.BRAND)
        || (Build.MODEL != null && Build.MODEL.toLowerCase(Locale.US).contains("shield"));
  }

  private boolean isDisplayOn() {
    Display defaultDisplay = DisplayUtil.getDefaultDisplay();
    if (defaultDisplay == null) {
      DisplayUtil.cacheDefaultDisplay(this);
      defaultDisplay = DisplayUtil.getDefaultDisplay();
    }
    if (defaultDisplay == null) {
      // Fail-safe: assume display is ON if we cannot query it
      return true;
    }
    int state = defaultDisplay.getState();
    // If the state is unknown, we fail-safe to true (display ON) to prevent
    // rendering freezes or black screens on devices that do not report display state correctly.
    return state == Display.STATE_ON || state == Display.STATE_UNKNOWN;
  }

  private void checkDisplayState() {
    boolean isDisplayOn = isDisplayOn();
    if (isDisplayOn != mWasDisplayOn) {
      mWasDisplayOn = isDisplayOn;
      Log.i(TAG, "Display state changed: isDisplayOn = " + isDisplayOn);
      if (!useStarboardLifeCycle()) {
        updateShellActivityVisible(isDisplayOn);
      }
    }
  }

  private void registerDisplayListener() {
    DisplayUtil.registerListener(mDisplayListener);
  }

  private void unregisterDisplayListener() {
    DisplayUtil.unregisterListener(mDisplayListener);
  }

  @RequiresApi(api = 33)
  private static class OnBackInvokedHelper {
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    static Object register(final CobaltActivity activity) {
      OnBackInvokedCallback callback =
          new OnBackInvokedCallback() {
            @Override
            public void onBackInvoked() {
              if (activity.mPhysicalBackKeyPressed) {
                return; // Bypassed: physical key events are already driving this navigation
              }

              // 1. Dispatch keydown to initiate navigation immediately.
              activity.dispatchKeyEventToIme(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN);

              // 2. Simulate physical key release with a 100ms delay.
              // This mimics natural user latency and prevents the web page's focus manager
              // from receiving 'keyup' prematurely during asynchronous page transitions
              // (which would otherwise disrupt cursor/spatial navigation focus restoration).
              sHandler.postDelayed(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (activity.isDestroyed() || activity.isFinishing()) {
                        return; // Avoid memory leaks or dispatching keyup to a destroyed activity
                      }
                      activity.dispatchKeyEventToIme(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP);
                    }
                  },
                  100);
            }
          };
      activity
          .getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
      return callback;
    }

    static void unregister(CobaltActivity activity, Object callback) {
      if (callback instanceof OnBackInvokedCallback) {
        activity
            .getOnBackInvokedDispatcher()
            .unregisterOnBackInvokedCallback((OnBackInvokedCallback) callback);
      }
    }
  }

  public void setFrameRate(float frameRate, int strategy) {
    if (Build.VERSION.SDK_INT < 30) {
      return;
    }

    Runnable runnable =
        new Runnable() {
          @Override
          public void run() {
            Surface videoSurface = VideoSurfaceView.getCurrentSurface();
            if (Build.VERSION.SDK_INT == 30) videoSurface.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            else if (Build.VERSION.SDK_INT >= 31)
              videoSurface.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, strategy);
          }
        };

    runOnUiThread(runnable);
  }
}
