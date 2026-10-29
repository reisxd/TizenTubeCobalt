// Copyright 2026 The Cobalt Authors. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// https://www.apache.org/licenses/LICENSE-2.0

package dev.cobalt.coat;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Native, remote-control accessible update UI. The Android installer still confirms installation. */
public final class CobaltUpdateActivity extends Activity {
  static final String EXTRA_URL = "dev.cobalt.coat.UPDATE_URL";
  private static final int INSTALL_REQUEST = 710;
  private static final int PERMISSION_REQUEST = 711;
  private Session mSession;
  private TextView mStatus;
  private ProgressBar mProgress;
  private Button mAction;
  private boolean mWaitingForPermission;
  private boolean mInstallerOpen;
  private int mMessage;

  @Override
  public void onCreate(Bundle savedState) {
    super.onCreate(savedState);
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setGravity(Gravity.CENTER_VERTICAL);
    int padding = (int) (48 * getResources().getDisplayMetrics().density);
    layout.setPadding(padding, padding / 2, padding, padding / 2);
    TextView title = new TextView(this);
    title.setText(R.string.tizentube_update_title);
    title.setTextSize(28);
    layout.addView(title);
    mStatus = new TextView(this);
    mStatus.setTextSize(20);
    mStatus.setPadding(0, 24, 0, 24);
    layout.addView(mStatus);
    mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    mProgress.setMax(100);
    layout.addView(mProgress, new LinearLayout.LayoutParams(-1, -2));
    mAction = new Button(this);
    mAction.setOnClickListener(view -> performAction());
    layout.addView(mAction);
    Button close = new Button(this);
    close.setText(android.R.string.cancel);
    close.setOnClickListener(view -> finish());
    layout.addView(close);
    setContentView(layout);

    mSession = (Session) getLastNonConfigurationInstance();
    if (mSession == null) {
      mSession = new Session(getApplicationContext(), getIntent().getStringExtra(EXTRA_URL),
          new File(getFilesDir(), "updates/update-" + UUID.randomUUID() + ".apk"));
      mSession.attach(this);
      mSession.start();
    } else {
      mSession.attach(this);
    }
    if (savedState != null) {
      mWaitingForPermission = savedState.getBoolean("waiting_permission");
      mInstallerOpen = savedState.getBoolean("installer_open");
      mMessage = savedState.getInt("message");
    }
    render();
    close.requestFocus();
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return mSession;
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putBoolean("waiting_permission", mWaitingForPermission);
    state.putBoolean("installer_open", mInstallerOpen);
    state.putInt("message", mMessage);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (mWaitingForPermission) {
      mWaitingForPermission = false;
      if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) {
        openInstaller();
      } else {
        mMessage = R.string.tizentube_update_permission;
        render();
      }
    }
  }

  @Override
  protected void onDestroy() {
    if (mSession != null) {
      mSession.detach(this);
      if (isFinishing()) mSession.cancel();
    }
    super.onDestroy();
  }

  private void render() {
    if (mSession == null || isFinishing()) return;
    boolean downloading = mSession.running;
    mProgress.setVisibility(downloading ? View.VISIBLE : View.GONE);
    mProgress.setIndeterminate(mSession.total <= 0);
    if (mSession.total > 0) {
      mProgress.setProgress((int) (100 * mSession.bytes / mSession.total));
    }
    if (downloading) {
      if (mSession.retrying) {
        mStatus.setText(getString(R.string.tizentube_update_retrying, mSession.attempt,
            UpdateDownloader.MAX_ATTEMPTS));
      } else if (mSession.total > 0) {
        mStatus.setText(getString(R.string.tizentube_update_progress,
            (int) (100 * mSession.bytes / mSession.total),
            formatSize(mSession.bytes), formatSize(mSession.total)));
      } else {
        mStatus.setText(getString(R.string.tizentube_update_unknown_size,
            formatSize(mSession.bytes)));
      }
    } else if (mMessage != 0) {
      mStatus.setText(mMessage);
    } else if (mSession.ready) {
      mStatus.setText(R.string.tizentube_update_ready);
    } else {
      mStatus.setText(mSession.error);
    }
    mAction.setVisibility(!downloading && !mInstallerOpen
        && (mSession.ready || mSession.retryable) ? View.VISIBLE : View.GONE);
    mAction.setText(mSession.ready ? R.string.tizentube_update_install
        : R.string.starboard_platform_retry);
  }

  private static String formatSize(long bytes) {
    return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
  }

  private void performAction() {
    mMessage = 0;
    if (!mSession.ready) {
      mSession.start();
      return;
    }
    if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
      mMessage = R.string.tizentube_update_permission;
      render();
      try {
        mWaitingForPermission = true;
        startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + getPackageName())), PERMISSION_REQUEST);
      } catch (ActivityNotFoundException error) {
        mWaitingForPermission = false;
        mMessage = R.string.tizentube_update_permission_unavailable;
        render();
      }
      return;
    }
    openInstaller();
  }

  private void openInstaller() {
    if (!mSession.ready || mInstallerOpen) return;
    if (!verifyApk(this, mSession.apk)) {
      mSession.ready = false;
      mSession.retryable = false;
      mSession.error = R.string.tizentube_update_invalid_apk;
      mMessage = 0;
      mSession.apk.delete();
      render();
      return;
    }
    try {
      Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", mSession.apk);
      Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
          .setDataAndType(uri, "application/vnd.android.package-archive")
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          .putExtra(Intent.EXTRA_RETURN_RESULT, true);
      mInstallerOpen = true;
      mMessage = R.string.tizentube_update_installing;
      render();
      startActivityForResult(intent, INSTALL_REQUEST);
    } catch (RuntimeException error) {
      mInstallerOpen = false;
      mMessage = R.string.tizentube_update_no_installer;
      render();
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == INSTALL_REQUEST) {
      mInstallerOpen = false;
      // Updating this app normally terminates its process. Never infer success from a launch.
      mMessage = result == RESULT_CANCELED ? R.string.tizentube_update_install_cancelled
          : R.string.tizentube_update_install_returned;
      render();
    }
  }

  @SuppressWarnings("deprecation")
  private static boolean verifyApk(Context context, File file) {
    try {
      int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES
          : PackageManager.GET_SIGNATURES;
      PackageManager pm = context.getPackageManager();
      PackageInfo archive = pm.getPackageArchiveInfo(file.getPath(), flags);
      PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
      if (archive == null || !context.getPackageName().equals(archive.packageName)
          || version(archive) <= version(installed)
          || (archive.applicationInfo != null
              && archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)) return false;
      Set<Signature> current = signatures(installed);
      Set<Signature> downloaded = signatures(archive);
      // Some TV firmware exposes archive certificates only through the legacy flag.
      if (current.isEmpty()) {
        current = signatures(pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES));
      }
      if (downloaded.isEmpty()) {
        PackageInfo legacy = pm.getPackageArchiveInfo(file.getPath(), PackageManager.GET_SIGNATURES);
        if (legacy != null) downloaded = signatures(legacy);
      }
      return !current.isEmpty() && current.equals(downloaded);
    } catch (RuntimeException | PackageManager.NameNotFoundException error) {
      return false;
    }
  }

  @SuppressWarnings("deprecation")
  private static long version(PackageInfo info) {
    return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
  }

  @SuppressWarnings("deprecation")
  private static Set<Signature> signatures(PackageInfo info) {
    Signature[] values = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
        ? info.signingInfo.getApkContentsSigners() : info.signatures;
    return values == null ? new HashSet<>() : new HashSet<>(Arrays.asList(values));
  }

  private static final class Session implements UpdateDownloader.Listener {
    final Context context;
    final String url;
    final File apk;
    final Handler main = new Handler(Looper.getMainLooper());
    WeakReference<CobaltUpdateActivity> activity = new WeakReference<>(null);
    volatile boolean cancelled;
    volatile boolean running;
    volatile boolean ready;
    volatile boolean retryable;
    volatile boolean retrying;
    volatile long bytes;
    volatile long total = -1;
    volatile int attempt = 1;
    volatile int error = R.string.tizentube_update_network_error;
    Thread worker;

    Session(Context context, String url, File apk) {
      this.context = context;
      this.url = url;
      this.apk = apk;
    }

    void attach(CobaltUpdateActivity value) { activity = new WeakReference<>(value); }
    void detach(CobaltUpdateActivity value) {
      if (activity.get() == value) activity.clear();
    }

    void changed() {
      main.post(() -> {
        CobaltUpdateActivity current = activity.get();
        if (current != null && !current.isDestroyed()) current.render();
      });
    }

    void start() {
      if (running || cancelled) return;
      running = true;
      ready = false;
      retrying = false;
      retryable = false;
      bytes = 0;
      total = -1;
      attempt = 1;
      changed();
      worker = new Thread(() -> {
        try {
          new UpdateDownloader().download(url, apk, this);
          boolean valid = !cancelled && verifyApk(context, apk);
          ready = valid && !cancelled;
          if (!ready) {
            error = R.string.tizentube_update_invalid_apk;
            apk.delete();
          }
        } catch (IOException | RuntimeException failure) {
          error = R.string.tizentube_update_network_error;
          retryable = true;
          if (failure instanceof UpdateDownloader.DownloadException) {
            UpdateDownloader.DownloadException detail = (UpdateDownloader.DownloadException) failure;
            retryable = detail.retryable;
            if (detail.failure == UpdateDownloader.Failure.STORAGE) {
              error = R.string.tizentube_update_storage_error;
              retryable = true;
            } else if (!detail.retryable) {
              error = R.string.tizentube_update_download_error;
            }
          }
        } finally {
          running = false;
          if (cancelled) apk.delete();
          changed();
        }
      }, "TizenTube-update-download");
      worker.start();
    }

    void cancel() {
      cancelled = true;
      if (worker != null) worker.interrupt();
      // The worker removes its partial file after closing the stream.
      if (!running) apk.delete();
    }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void onProgress(long value, long size, int number) {
      bytes = value;
      total = size;
      attempt = number;
      retrying = false;
      changed();
    }
    @Override public void onRetry(int nextAttempt) {
      attempt = nextAttempt;
      retrying = true;
      changed();
    }
  }
}
