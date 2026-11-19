package dev.cobalt.coat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.util.Base64;
import dev.cobalt.coat.javabridge.CobaltJavaScriptInterface;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.chromium.content_public.browser.JavascriptInjector;
import org.chromium.content_public.browser.MessagePayload;
import org.chromium.content_public.browser.WebContents;
import org.json.JSONException;
import org.json.JSONObject;

/** SponsorBlock and DeArrow GET requests for servers approved on this TV. */
public final class TizenTubeServers {
  private static final String ORIGIN = "https://www.youtube.com";
  private static final String API = "sponsorBlockServer";
  private static final String IMAGES = "deArrowThumbnailServer";
  private static final int LIMIT = 2 * 1024 * 1024;
  private final Activity activity;
  private final WebContents contents;
  private final SharedPreferences servers;
  private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 20, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(96));
  private boolean prompting;

  private TizenTubeServers(Activity activity, WebContents contents) {
    this.activity = activity;
    this.contents = contents;
    servers = activity.getSharedPreferences("tizentube-servers", Activity.MODE_PRIVATE);
    workers.allowCoreThreadTimeOut(true);
  }

  public static TizenTubeServers install(Activity activity, WebContents contents) {
    TizenTubeServers client = new TizenTubeServers(activity, contents);
    JavascriptInjector.fromWebContents(contents).addPossiblyUnsafeInterface(client,
        "TizenTubeServers", CobaltJavaScriptInterface.class, Collections.singletonList(ORIGIN));
    return client;
  }

  public void close() {
    workers.shutdownNow();
  }

  private void reply(String id, Object value) {
    activity.runOnUiThread(() -> {
      if (contents.isDestroyed() || activity.isDestroyed()) return;
      try {
        String message = new JSONObject().put("tizenTubeRequest", id)
            .put("value", value == null ? JSONObject.NULL : value).toString();
        contents.postMessageToMainFrame(new MessagePayload(message), ORIGIN, ORIGIN, null);
      } catch (JSONException ignored) {
        // Only primitive values are sent across the bridge.
      }
    });
  }

  private static boolean validBase(String base) {
    try {
      URI uri = URI.create(base);
      return base.length() <= 2048 && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
          && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
          && uri.getRawFragment() == null && !base.endsWith("/");
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private volatile long apiGeneration;
  private volatile long imageGeneration;

  // A missing preference distinguishes a userscript-only installation from an explicit reset.
  @CobaltJavaScriptInterface
  public String selectedServer(String key) {
    return API.equals(key) || IMAGES.equals(key) ? servers.getString(key, null) : null;
  }

  private void save(String key, String base) {
    if (API.equals(key)) apiGeneration++;
    else imageGeneration++;
    servers.edit().putString(key, base).apply();
  }

  @CobaltJavaScriptInterface
  public void configure(String id, String key, String base) {
    if (!API.equals(key) && !IMAGES.equals(key) || base == null || id == null || id.length() > 80) return;
    if (!base.isEmpty() && !validBase(base)) { reply(id, false); return; }
    activity.runOnUiThread(() -> {
      if (activity.isDestroyed() || activity.isFinishing()) return;
      if (base.isEmpty() || base.equals(servers.getString(key, ""))) {
        save(key, base);
        reply(id, true);
        return;
      }
      if (prompting) { reply(id, false); return; }
      prompting = true;
      AlertDialog dialog = new AlertDialog.Builder(activity)
          .setTitle(API.equals(key) ? "Use this SponsorBlock and DeArrow server?" : "Use this thumbnail server?")
          .setMessage(base)
          .setPositiveButton(android.R.string.ok, (d, which) -> {
            save(key, base);
            reply(id, true);
          })
          .setNegativeButton(android.R.string.cancel, (d, which) -> reply(id, false))
          .setOnCancelListener(d -> reply(id, false)).create();
      dialog.setOnDismissListener(d -> prompting = false);
      dialog.show();
    });
  }

  @CobaltJavaScriptInterface
  public void segments(String id, String prefix) {
    String categories = "%5B%22sponsor%22%2C%22intro%22%2C%22outro%22%2C%22interaction%22"
        + "%2C%22selfpromo%22%2C%22preview%22%2C%22filler%22%2C%22music_offtopic%22%2C%22poi_highlight%22%5D";
    request(id, API, prefix != null && prefix.matches("[0-9a-f]{4}")
        ? "/api/skipSegments/" + prefix + "?categories=" + categories : null);
  }

  @CobaltJavaScriptInterface
  public void branding(String id, String videoId) {
    request(id, API, validVideo(videoId) ? "/api/branding?videoID=" + videoId : null);
  }

  @CobaltJavaScriptInterface
  public void thumbnail(String id, String videoId, String time) {
    request(id, IMAGES, validVideo(videoId) && time != null && time.matches("[0-9]{1,10}([.][0-9]{1,12})?")
        ? "/api/v1/getThumbnail?videoID=" + videoId + "&time=" + time : null);
  }

  private static boolean validVideo(String id) {
    return id != null && id.matches("[A-Za-z0-9_-]{11}");
  }

  private long generation(String key) {
    return API.equals(key) ? apiGeneration : imageGeneration;
  }

  private void request(String id, String key, String path) {
    if (id == null || id.length() > 80) return;
    // Snapshot the destination on the same thread as configuration changes.
    activity.runOnUiThread(() -> {
      long version = generation(key);
      String base = selectedServer(key);
      if (path == null || base == null || base.isEmpty()) { reply(id, null); return; }
      try {
        workers.execute(() -> {
          String result = null;
          if (version == generation(key)) {
            try {
              result = read(base + path, IMAGES.equals(key));
            } catch (Exception ignored) {
              // A missing service leaves the original video or card in place.
            }
          }
          String value = result;
          activity.runOnUiThread(() -> reply(id, version == generation(key) ? value : null));
        });
      } catch (RejectedExecutionException e) {
        reply(id, null);
      }
    });
  }

  private static String read(String url, boolean image) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
    try {
      connection.setConnectTimeout(6000);
      connection.setReadTimeout(8000);
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setRequestProperty("Origin", ORIGIN);
      if (connection.getResponseCode() != 200 || connection.getContentLength() > LIMIT) return null;
      String type = connection.getContentType();
      if (type == null) return null;
      type = type.split(";", 2)[0].trim();
      String cors = connection.getHeaderField("Access-Control-Allow-Origin");
      if (!("*".equals(cors) || ORIGIN.equals(cors))) return null;
      if (image) {
        if (!type.matches("image/(jpeg|png|webp|avif)")) return null;
      } else {
        if (!"application/json".equals(type)) return null;
      }
      try (InputStream in = connection.getInputStream()) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int n; (n = in.read(chunk)) != -1;) {
          if (bytes.size() + n > LIMIT) return null;
          bytes.write(chunk, 0, n);
        }
        return image ? "data:" + type + ";base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            : bytes.toString(StandardCharsets.UTF_8.name());
      }
    } finally {
      connection.disconnect();
    }
  }
}
