// Copyright 2026 The Cobalt Authors. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// https://www.apache.org/licenses/LICENSE-2.0

package dev.cobalt.coat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads a single APK. It never opens the installer or reports installation success. */
final class UpdateDownloader {
  static final long MAX_BYTES = 512L * 1024 * 1024;
  static final int MAX_ATTEMPTS = 3;

  interface Listener {
    boolean isCancelled();
    void onProgress(long bytes, long total, int attempt);
    void onRetry(int nextAttempt);
  }

  interface ConnectionFactory {
    HttpURLConnection open(URL url) throws IOException;
  }

  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  enum Failure { NETWORK, HTTP, INVALID_URL, TOO_LARGE, STORAGE, CANCELLED }

  static final class DownloadException extends IOException {
    final Failure failure;
    final boolean retryable;

    DownloadException(Failure failure, boolean retryable) {
      super(failure.name());
      this.failure = failure;
      this.retryable = retryable;
    }
  }

  private final ConnectionFactory mConnections;
  private final Sleeper mSleeper;

  UpdateDownloader() {
    this(url -> (HttpURLConnection) url.openConnection(), Thread::sleep);
  }

  UpdateDownloader(ConnectionFactory connections, Sleeper sleeper) {
    mConnections = connections;
    mSleeper = sleeper;
  }

  static URL validateUrl(String value) throws DownloadException {
    try {
      URL url = new URL(value);
      if (!"https".equals(url.getProtocol()) || url.getHost().isEmpty()
          || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)) {
        throw new IOException();
      }
      return url;
    } catch (Exception error) {
      throw new DownloadException(Failure.INVALID_URL, false);
    }
  }

  File download(String value, File destination, Listener listener) throws IOException {
    URL url = validateUrl(value);
    File partial = new File(destination.getPath() + ".part");
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      checkCancelled(listener);
      try {
        downloadAttempt(url, partial, listener, attempt);
        checkCancelled(listener);
        if ((destination.exists() && !destination.delete()) || !partial.renameTo(destination)) {
          throw new DownloadException(Failure.STORAGE, false);
        }
        return destination;
      } catch (IOException error) {
        checkCancelled(listener);
        boolean retryable = !(error instanceof DownloadException)
            || ((DownloadException) error).retryable;
        if (!retryable || attempt == MAX_ATTEMPTS) throw error;
        listener.onRetry(attempt + 1);
        try {
          mSleeper.sleep(1000L * attempt);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new DownloadException(Failure.CANCELLED, false);
        }
      } finally {
        // Neither a failed transfer nor cancellation leaves an installable partial APK.
        partial.delete();
      }
    }
    throw new DownloadException(Failure.NETWORK, true);
  }

  private void downloadAttempt(URL initialUrl, File partial, Listener listener, int attempt)
      throws IOException {
    HttpURLConnection connection = null;
    try {
      URL url = initialUrl;
      for (int redirect = 0; ; redirect++) {
        checkCancelled(listener);
        connection = mConnections.open(url);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept-Encoding", "identity");
        int status = connection.getResponseCode();
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
          if (redirect >= 5) throw new DownloadException(Failure.HTTP, false);
          String location = connection.getHeaderField("Location");
          if (location == null) throw new DownloadException(Failure.HTTP, false);
          URL next = validateUrl(new URL(url, location).toString());
          connection.disconnect();
          connection = null;
          url = next;
          continue;
        }
        if (status != HttpURLConnection.HTTP_OK) {
          throw new DownloadException(Failure.HTTP,
              status == 408 || status == 429 || (status >= 500 && status <= 599));
        }
        break;
      }

      long total = connection.getContentLengthLong();
      if (total > MAX_BYTES) throw new DownloadException(Failure.TOO_LARGE, false);
      File parent = partial.getParentFile();
      if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
        throw new DownloadException(Failure.STORAGE, false);
      }
      long available = parent.getUsableSpace();
      if (total > 0 && available > 0 && total > available - 4L * 1024 * 1024) {
        throw new DownloadException(Failure.STORAGE, false);
      }
      FileOutputStream output;
      try {
        output = new FileOutputStream(partial);
      } catch (IOException error) {
        throw new DownloadException(Failure.STORAGE, false);
      }
      try (FileOutputStream out = output; InputStream in = connection.getInputStream()) {
        byte[] buffer = new byte[32768];
        long bytes = 0;
        long lastProgress = 0;
        listener.onProgress(0, total, attempt);
        for (int count; (count = in.read(buffer)) != -1; ) {
          checkCancelled(listener);
          bytes += count;
          if (total >= 0 && bytes > total) throw new DownloadException(Failure.NETWORK, true);
          if (bytes > MAX_BYTES) throw new DownloadException(Failure.TOO_LARGE, false);
          try {
            out.write(buffer, 0, count);
          } catch (IOException error) {
            throw new DownloadException(Failure.STORAGE, false);
          }
          long now = System.nanoTime();
          if (now - lastProgress >= 250000000L) {
            listener.onProgress(bytes, total, attempt);
            lastProgress = now;
          }
        }
        if (bytes == 0 || (total >= 0 && bytes != total)) {
          throw new DownloadException(Failure.NETWORK, true);
        }
        listener.onProgress(bytes, total, attempt);
      }
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private static void checkCancelled(Listener listener) throws DownloadException {
    if (listener.isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new DownloadException(Failure.CANCELLED, false);
    }
  }
}
