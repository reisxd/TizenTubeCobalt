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

package dev.cobalt.media;

import static dev.cobalt.media.Log.TAG;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import dev.cobalt.util.Log;
import java.util.HashSet;
import java.util.Set;

/**
 * A Surface view to be used by the video decoder. It informs the Starboard application when the
 * surface is available so that the decoder can get a reference to it.
 */
public class VideoSurfaceView extends SurfaceView {

  private static Surface currentSurface = null;

  private static final Set<String> needResetSurfaceList = new HashSet<>();

  static {
    needResetSurfaceList.add("Nexus Player");

    // Reset video surface on nexus player to avoid b/159073388.
    if (needResetSurfaceList.contains(Build.MODEL)) {
      nativeSetNeedResetSurface();
    }
  }

  public VideoSurfaceView(Context context) {
    super(context);
    initialize(context);
  }

  public VideoSurfaceView(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize(context);
  }

  public VideoSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize(context);
  }

  public VideoSurfaceView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    initialize(context);
  }

  private void initialize(Context context) {
    setBackgroundColor(Color.TRANSPARENT);
    getHolder().addCallback(new SurfaceHolderCallback());

    // TODO: Avoid recreating the surface when the player bounds change.
    // Recreating the surface is time-consuming and complicates synchronizing
    // punch-out video when the position / size is animated.
  }

  private static native void nativeOnVideoSurfaceChanged(Surface surface);

  private static native void nativeSetNeedResetSurface();

  private class SurfaceHolderCallback implements SurfaceHolder.Callback {

    private int lastWidth = 0;
    private int lastHeight = 0;
    private boolean sawInitialChange = false;

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
      currentSurface = holder.getSurface();
      nativeOnVideoSurfaceChanged(currentSurface);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
      // Only log warning if the surface format/size unexpectedly changed after initial creation.
      // Size changes during mini-player/PiP animations are expected and should not spam logs.
      if (sawInitialChange && (width != lastWidth || height != lastHeight)) {
        Log.w(TAG, "Video surface size changed from " + lastWidth + "x" + lastHeight
            + " to " + width + "x" + height + "; this may affect decoding");
      }
      lastWidth = width;
      lastHeight = height;
      sawInitialChange = true;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
      currentSurface = null;
      nativeOnVideoSurfaceChanged(currentSurface);
    }
  }

  public static Surface getCurrentSurface() {
    return currentSurface;
  }
}
