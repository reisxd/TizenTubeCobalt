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

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.chromium.base.PathUtils;

/**
 * Utility class responsible for extracting Cobalt's font configuration table asset from the APK.
 *
 * <p>Reason for existence: Native C++ Skia (`SkFontMgr_Android_CustomFonts`) requires a raw
 * filesystem file path (`storage/cobalt_android_fonts.xml`) to read custom font definitions and
 * multilingual glyph fallbacks. This helper copies `fonts/fonts.xml` from the APK assets bundle
 * into the application's private data storage directory on cold start.
 *
 * <p>Expected lifetime/ownership: Stateless static utility class. It owns no long-lived references,
 * holds no internal instance state, and does not instantiate any objects beyond temporary I/O
 * streams during the copy operation.
 *
 * <p>Threading model: Synchronous utility method designed to be invoked from the main UI thread or
 * startup background threads during early initialization milestones (`CobaltActivity` cold start).
 * Performs blocking filesystem I/O to guarantee the XML file is persisted on disk before the native
 * Starboard/Chromium engine boots.
 */
public class FontUtil {
  private static final String TAG = "FontUtil";

  public static void copyFontsXml(Context context) {
    File storageDir = new File(PathUtils.getDataDirectory(), "storage");
    if (!storageDir.exists() && !storageDir.mkdirs()) {
      Log.e(TAG, "Failed to create directory: " + storageDir.getAbsolutePath());
      return;
    }
    String asset = "fonts/fonts.xml";
    String fileName = "cobalt_android_fonts.xml";
    File targetFile = new File(storageDir, fileName);
    try (InputStream is = context.getAssets().open(asset)) {
      // Equal file lengths do not imply equal font configuration contents.
      // Refresh on cold start so same-size asset updates reach existing installs.
      try (FileOutputStream fos = new FileOutputStream(targetFile)) {
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) {
          fos.write(buf, 0, len);
        }
      }
    } catch (IOException e) {
      Log.e(TAG, "Failed copying asset: " + asset, e);
    }
  }
}
