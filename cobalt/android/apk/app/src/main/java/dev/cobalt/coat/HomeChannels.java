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

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Publishes the rows and Continue Watching cards sent by the web app (window.h5vcc.tizentube
 * .PublishHomeScreen) to the Android TV home screen, through the platform TvProvider.
 *
 * <p>Rows are app channels ("preview channels"), one per row key; cards are preview programs.
 * Continue Watching cards are Watch Next programs. Every update is a reconcile: only cards that
 * were added, removed or changed are written, so launchers keep the user's place. Anything the user
 * removes from the home screen stays removed: the launcher marks it not browsable, and the next
 * update records that and never brings it back (Watch Next cards return only once watched again).
 * There is deliberately no exported receiver for the removal broadcasts, so no other app can reach
 * this code.
 *
 * <p>All input comes from page script, so it is validated here: ids must look like YouTube ids,
 * images must be on YouTube's image hosts, numbers must be in range, and every card opens a
 * youtube.com URL in this app. Work is bounded: one update runs at a time and at most two wait.
 */
public final class HomeChannels {
  private static final String TAG = "TizenTubeHome";
  private static final String PREFS = "tizentube_home_channels";
  private static final String PREF_ACCOUNT = "account";
  private static final String PREF_CHANNEL = "channel.";
  private static final String PREF_REMOVED = "removed.";
  private static final String WATCH_NEXT_ROW = "#watch_next";
  private static final String PREF_BROWSABLE_ASKED = "browsable_asked";

  private static final int MAX_JSON = 512 * 1024;
  private static final int MAX_ROWS = 12;
  private static final int MAX_ITEMS = 50;
  private static final int MAX_WATCH_NEXT = 10;
  private static final int MAX_TEXT = 200;
  private static final long REMOVED_KEEP_MS = 30L * 24 * 60 * 60 * 1000;

  private static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,32}");
  private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z0-9]{1,64}");
  private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
  private static final Pattern PLAYLIST_ID = Pattern.compile("[A-Za-z0-9_-]{2,64}");
  private static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");
  private static final String[] IMAGE_HOSTS = {"ytimg.com", "ggpht.com", "googleusercontent.com"};
  private static final long MAX_DURATION_SECONDS = 7L * 24 * 60 * 60;

  private static final String[] PROGRAM_COLUMNS = {
    TvContract.PreviewPrograms._ID,
    TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
    TvContract.PreviewPrograms.COLUMN_BROWSABLE,
    TvContract.PreviewPrograms.COLUMN_TITLE,
    TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
    TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
    TvContract.PreviewPrograms.COLUMN_INTENT_URI,
    TvContract.PreviewPrograms.COLUMN_WEIGHT,
    TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
    TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
    TvContract.PreviewPrograms.COLUMN_LIVE,
  };

  private static final String[] WATCH_NEXT_COLUMNS = {
    TvContract.WatchNextPrograms._ID,
    TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
    TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
    TvContract.WatchNextPrograms.COLUMN_TITLE,
    TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
    TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
    TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
    TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
    TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
    TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
    TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
  };

  // One thread, so updates never overlap, and the caller (the renderer's JNI thread) never waits.
  // At most two updates wait; when full, the oldest waiting one is dropped. Every payload carries
  // the full state of what it covers, so a newer one supersedes it.
  private static final ThreadPoolExecutor EXECUTOR =
      new ThreadPoolExecutor(
          1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2),
          new ThreadPoolExecutor.DiscardOldestPolicy());

  static {
    EXECUTOR.allowCoreThreadTimeOut(true);
  }

  private HomeChannels() {}

  /** True when this device has a TvProvider to publish to (Android TV and Google TV, 8.0+). */
  public static boolean isSupported(Context context) {
    Boolean known = sSupported;
    if (known == null) {
      known = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
          && context.getPackageManager().resolveContentProvider(TvContract.AUTHORITY, 0) != null;
      sSupported = known;
    }
    return known;
  }

  private static volatile Boolean sSupported;

  /** Queues one payload from the web app. Never throws. */
  public static void publish(Context context, String json) {
    if (context == null || json == null || json.length() > MAX_JSON) return;
    final Context app = context.getApplicationContext();
    if (!isSupported(app)) return;
    EXECUTOR.execute(
        () -> {
          try {
            apply(app, new JSONObject(json));
          } catch (SecurityException e) {
            Log.w(TAG, "TvProvider refused the update; is WRITE_EPG_DATA declared?", e);
          } catch (Exception e) {
            Log.w(TAG, "Home screen update skipped", e);
          }
        });
  }

  // ---------- payload ----------

  private static void apply(Context context, JSONObject payload) throws Exception {
    if (payload.optInt("v") != 1) return;
    SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    if (payload.optBoolean("clear")) {
      clearAll(context, prefs, true);
      return;
    }

    // Another account or profile: nothing of the last one may stay on screen.
    String account = payload.optString("account", "");
    if (!account.isEmpty() && ACCOUNT.matcher(account).matches()) {
      String previous = prefs.getString(PREF_ACCOUNT, "");
      if (!previous.isEmpty() && !previous.equals(account)) clearAll(context, prefs, false);
      prefs.edit().putString(PREF_ACCOUNT, account).apply();
    }

    JSONArray rows = payload.optJSONArray("rows");
    if (rows != null) syncRows(context, prefs, rows);
    JSONArray watchNext = payload.optJSONArray("watchNext");
    if (watchNext != null) syncWatchNext(context, prefs, watchNext);
  }

  /** One card, validated. Null when it can't be shown or opened. */
  private static final class Card {
    String key; // stable identity within a row: "video:<id>"
    String title;
    String subtitle;
    String image;
    Uri link;
    int type = TvContract.PreviewPrograms.TYPE_CLIP;
    boolean square;
    boolean live;
    long durationMs;
    long positionMs;
    long engagedAt;

    static Card from(JSONObject o, boolean watchNext) {
      if (o == null) return null;
      Card c = new Card();
      String type = watchNext ? "video" : o.optString("type");
      String id = o.optString("id");
      c.title = clean(o.optString("title"));
      c.subtitle = clean(o.optString("subtitle"));
      c.image = o.optString("thumb");
      if (c.title.isEmpty() || !isImage(c.image)) return null;
      long seconds = o.optLong("duration");
      if (seconds < 0 || seconds > MAX_DURATION_SECONDS) seconds = 0;
      long duration = seconds * 1000;
      c.durationMs = duration;
      c.live = o.optBoolean("live");

      Uri.Builder url = new Uri.Builder().scheme("https").authority("www.youtube.com");
      switch (type) {
        case "video":
          if (!VIDEO_ID.matcher(id).matches()) return null;
          url.path("watch").appendQueryParameter("v", id);
          String list = o.optString("playlistId");
          if (!watchNext && PLAYLIST_ID.matcher(list).matches()) url.appendQueryParameter("list", list);
          if (watchNext) {
            long position = o.optLong("position");
            if (duration <= 0 || position < 0 || position >= seconds) return null;
            c.positionMs = position * 1000;
            c.engagedAt = Math.min(o.optLong("engagedAt"), System.currentTimeMillis());
            if (c.engagedAt <= 0) return null;
            // TizenTube resumes at t=<seconds> (no "s" suffix).
            url.appendQueryParameter("t", Long.toString(position));
          } else if (!c.live && duration > 0) {
            int progress = Math.max(0, Math.min(100, o.optInt("progress")));
            c.positionMs = duration * progress / 100;
          }
          break;
        case "playlist":
          if (!PLAYLIST_ID.matcher(id).matches()) return null;
          url.path("playlist").appendQueryParameter("list", id);
          c.type = TvContract.PreviewPrograms.TYPE_PLAYLIST;
          break;
        case "channel":
          if (!CHANNEL_ID.matcher(id).matches()) return null;
          url.path("channel/" + id);
          c.type = TvContract.PreviewPrograms.TYPE_CHANNEL;
          c.square = true;
          break;
        default:
          return null;
      }
      c.key = type + ":" + id;
      c.link = url.build();
      return c;
    }
  }

  /** An https image on YouTube's image hosts, with no user info or unusual port. */
  static boolean isImage(String url) {
    if (url == null || url.length() > 1000) return false;
    Uri uri = Uri.parse(url);
    String host = uri.getHost();
    if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null) return false;
    if (uri.getPort() != -1 && uri.getPort() != 443) return false;
    host = host.toLowerCase(java.util.Locale.ROOT);
    for (String allowed : IMAGE_HOSTS) {
      if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
    }
    return false;
  }

  private static String clean(String s) {
    if (s == null) return "";
    s = s.replaceAll("\\s+", " ").trim();
    if (s.length() <= MAX_TEXT) return s;
    int end = MAX_TEXT - 1;
    if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
    return s.substring(0, end) + "…";
  }

  private static String intentUri(Context context, Uri link) {
    Intent intent = new Intent(Intent.ACTION_VIEW, link);
    intent.setPackage(context.getPackageName());
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    return intent.toUri(Intent.URI_INTENT_SCHEME);
  }

  // ---------- rows (preview channels) ----------

  private static void syncRows(Context context, SharedPreferences prefs, JSONArray rows)
      throws Exception {
    ContentResolver resolver = context.getContentResolver();
    Set<String> wanted = new HashSet<>();
    for (int i = 0; i < Math.min(rows.length(), MAX_ROWS); i++) {
      JSONObject row = rows.optJSONObject(i);
      if (row == null) continue;
      String key = row.optString("key");
      if (!KEY.matcher(key).matches()) continue;
      wanted.add(key);
      String title = clean(row.optString("title"));
      if (title.isEmpty()) title = key;
      long channelId = ensureChannel(context, prefs, key, title);
      if (channelId < 0) continue;
      // items: null means "unchanged" (that source failed to load this time).
      JSONArray items = row.isNull("items") ? null : row.optJSONArray("items");
      if (items != null) syncPrograms(context, prefs, key, channelId, items);
    }

    // Rows that were switched off.
    SharedPreferences.Editor edit = prefs.edit();
    for (String pref : new HashSet<>(prefs.getAll().keySet())) {
      if (!pref.startsWith(PREF_CHANNEL)) continue;
      String key = pref.substring(PREF_CHANNEL.length());
      if (wanted.contains(key)) continue;
      long id = prefs.getLong(pref, -1);
      if (id >= 0) resolver.delete(TvContract.buildChannelUri(id), null, null);
      edit.remove(pref).remove(PREF_REMOVED + key);
    }
    edit.apply();
  }

  /** The channel for a row key, created or renamed as needed. -1 if it can't be made. */
  private static long ensureChannel(
      Context context, SharedPreferences prefs, String key, String title) {
    ContentResolver resolver = context.getContentResolver();
    long id = prefs.getLong(PREF_CHANNEL + key, -1);
    if (id >= 0) {
      try (Cursor c =
          resolver.query(
              TvContract.buildChannelUri(id),
              new String[] {TvContract.Channels.COLUMN_DISPLAY_NAME},
              null,
              null,
              null)) {
        if (c != null && c.moveToFirst()) {
          if (!title.equals(c.getString(0))) {
            ContentValues v = new ContentValues();
            v.put(TvContract.Channels.COLUMN_DISPLAY_NAME, title);
            resolver.update(TvContract.buildChannelUri(id), v, null, null);
          }
          return id;
        }
      }
      // The channel is gone (cleared app data in the provider, or deleted); make a new one.
    }

    ContentValues v = new ContentValues();
    v.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW);
    v.put(TvContract.Channels.COLUMN_DISPLAY_NAME, title);
    v.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, key);
    Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
    if (launch == null) {
      launch = context.getPackageManager().getLeanbackLaunchIntentForPackage(context.getPackageName());
    }
    if (launch != null) {
      v.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, launch.toUri(Intent.URI_INTENT_SCHEME));
    }
    Uri uri = resolver.insert(TvContract.Channels.CONTENT_URI, v);
    if (uri == null) return -1;
    id = ContentUris.parseId(uri);
    prefs.edit().putLong(PREF_CHANNEL + key, id).remove(PREF_REMOVED + key).apply();
    storeLogo(context, id);
    if (!prefs.getBoolean(PREF_BROWSABLE_ASKED, false)) {
      // The stock launcher shows an app's first requested channel; others are added by the user.
      try {
        TvContract.requestChannelBrowsable(context, id);
      } catch (Exception e) {
        Log.w(TAG, "Channel not made browsable", e);
      }
      prefs.edit().putBoolean(PREF_BROWSABLE_ASKED, true).apply();
    }
    return id;
  }

  private static void storeLogo(Context context, long channelId) {
    try {
      Drawable icon = context.getPackageManager().getApplicationIcon(context.getPackageName());
      int size = 256;
      Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(bitmap);
      icon.setBounds(0, 0, size, size);
      icon.draw(canvas);
      try (OutputStream out =
          context.getContentResolver().openOutputStream(TvContract.buildChannelLogoUri(channelId))) {
        if (out != null) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      bitmap.recycle();
    } catch (Exception e) {
      Log.w(TAG, "Channel logo not stored", e);
    }
  }

  private static void syncPrograms(
      Context context, SharedPreferences prefs, String rowKey, long channelId, JSONArray items)
      throws Exception {
    ContentResolver resolver = context.getContentResolver();
    Removed removed = new Removed(prefs, PREF_REMOVED + rowKey);

    // What is on screen now.
    Map<String, ContentValues> existing = new HashMap<>();
    Map<String, Long> existingIds = new HashMap<>();
    Set<String> hidden = new HashSet<>();
    List<Long> strays = new ArrayList<>();
    try (Cursor c =
        resolver.query(
            TvContract.buildPreviewProgramsUriForChannel(channelId), PROGRAM_COLUMNS, null, null, null)) {
      while (c != null && c.moveToNext()) {
        long id = c.getLong(0);
        String key = c.getString(1);
        if (key == null || existingIds.containsKey(key)) {
          strays.add(id);
          continue;
        }
        if (c.getInt(2) == 0) { // Hidden by the user from the home screen.
          removed.add(key, 0);
          hidden.add(key);
        }
        existingIds.put(key, id);
        existing.put(key, valuesOf(c, PROGRAM_COLUMNS, 3, PROGRAM_COLUMNS.length));
      }
    }

    // What should be.
    List<Card> cards = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < items.length() && cards.size() < MAX_ITEMS; i++) {
      Card card = Card.from(items.optJSONObject(i), false);
      if (card == null || keys.contains(card.key) || removed.contains(card.key, 0)) continue;
      keys.add(card.key);
      cards.add(card);
    }

    ArrayList<ContentProviderOperation> ops = new ArrayList<>();
    for (long id : strays) ops.add(ContentProviderOperation.newDelete(TvContract.buildPreviewProgramUri(id)).build());
    for (Map.Entry<String, Long> e : existingIds.entrySet()) {
      if (!keys.contains(e.getKey()) && !removed.contains(e.getKey(), 0)) {
        ops.add(ContentProviderOperation.newDelete(TvContract.buildPreviewProgramUri(e.getValue())).build());
      }
    }
    for (int i = 0; i < cards.size(); i++) {
      Card card = cards.get(i);
      ContentValues v = programValues(context, card, cards.size() - i);
      Long id = existingIds.get(card.key);
      if (id != null && hidden.contains(card.key)) {
        // Hidden by an old removal that has since expired: a new card, since browsable can't be reset.
        ops.add(ContentProviderOperation.newDelete(TvContract.buildPreviewProgramUri(id)).build());
        id = null;
      }
      if (id == null) {
        v.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId);
        v.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, card.key);
        v.put(TvContract.PreviewPrograms.COLUMN_CONTENT_ID, card.key);
        ops.add(ContentProviderOperation.newInsert(TvContract.PreviewPrograms.CONTENT_URI).withValues(v).build());
      } else if (!same(existing.get(card.key), v)) {
        ops.add(ContentProviderOperation.newUpdate(TvContract.buildPreviewProgramUri(id)).withValues(v).build());
      }
    }
    run(resolver, ops);
    removed.save();
  }

  private static ContentValues programValues(Context context, Card card, int weight) {
    ContentValues v = new ContentValues();
    v.put(TvContract.PreviewPrograms.COLUMN_TYPE, card.type);
    v.put(TvContract.PreviewPrograms.COLUMN_TITLE, card.title);
    v.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, card.subtitle);
    v.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, card.image);
    v.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
        card.square ? TvContract.PreviewPrograms.ASPECT_RATIO_1_1 : TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
    v.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, intentUri(context, card.link));
    v.put(TvContract.PreviewPrograms.COLUMN_WEIGHT, weight);
    v.put(TvContract.PreviewPrograms.COLUMN_LIVE, card.live ? 1 : 0);
    v.put(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, card.durationMs);
    v.put(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, card.positionMs);
    return v;
  }

  // ---------- Continue Watching (Watch Next) ----------

  private static void syncWatchNext(Context context, SharedPreferences prefs, JSONArray items)
      throws Exception {
    ContentResolver resolver = context.getContentResolver();
    Removed removed = new Removed(prefs, PREF_REMOVED + WATCH_NEXT_ROW);

    Map<String, ContentValues> existing = new HashMap<>();
    Map<String, Long> existingIds = new HashMap<>();
    Set<String> hidden = new HashSet<>();
    List<Long> strays = new ArrayList<>();
    // Non-system apps only ever see their own Watch Next rows.
    try (Cursor c =
        resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT_COLUMNS, null, null, null)) {
      while (c != null && c.moveToNext()) {
        // Only our own Continue Watching cards; ones the user added ("Watch Next", watchlist) stay theirs.
        if (c.isNull(10) || c.getInt(10) != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE) continue;
        long id = c.getLong(0);
        String key = c.getString(1);
        if (key == null || existingIds.containsKey(key)) {
          strays.add(id);
          continue;
        }
        if (c.getInt(2) == 0) {
          removed.add(key, c.getLong(9));
          hidden.add(key);
        }
        existingIds.put(key, id);
        existing.put(key, valuesOf(c, WATCH_NEXT_COLUMNS, 3, 10));
      }
    }

    List<Card> cards = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < items.length() && cards.size() < MAX_WATCH_NEXT; i++) {
      Card card = Card.from(items.optJSONObject(i), true);
      // A removed card comes back only once it has been watched again since.
      if (card == null || keys.contains(card.key) || removed.contains(card.key, card.engagedAt)) continue;
      keys.add(card.key);
      cards.add(card);
    }

    ArrayList<ContentProviderOperation> ops = new ArrayList<>();
    for (long id : strays) ops.add(ContentProviderOperation.newDelete(TvContract.buildWatchNextProgramUri(id)).build());
    for (Map.Entry<String, Long> e : existingIds.entrySet()) {
      if (!keys.contains(e.getKey())) {
        ops.add(ContentProviderOperation.newDelete(TvContract.buildWatchNextProgramUri(e.getValue())).build());
      }
    }
    for (Card card : cards) {
      removed.forget(card.key);
      ContentValues v = new ContentValues();
      v.put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_CLIP);
      v.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
          TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
      v.put(TvContract.WatchNextPrograms.COLUMN_TITLE, card.title);
      v.put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, card.subtitle);
      v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, card.image);
      v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
          TvContract.WatchNextPrograms.ASPECT_RATIO_16_9);
      v.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intentUri(context, card.link));
      v.put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, card.durationMs);
      v.put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, card.positionMs);
      v.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, card.engagedAt);
      Long id = existingIds.get(card.key);
      if (id != null && hidden.contains(card.key)) {
        // Removed earlier and watched again since: a new card, since browsable can't be reset.
        ops.add(ContentProviderOperation.newDelete(TvContract.buildWatchNextProgramUri(id)).build());
        id = null;
      }
      if (id == null) {
        v.put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, card.key);
        v.put(TvContract.WatchNextPrograms.COLUMN_CONTENT_ID, card.key);
        ops.add(ContentProviderOperation.newInsert(TvContract.WatchNextPrograms.CONTENT_URI).withValues(v).build());
      } else if (!same(existing.get(card.key), v)) {
        // Only cards the user interacted with change, so the launcher's ordering stays put.
        ops.add(ContentProviderOperation.newUpdate(TvContract.buildWatchNextProgramUri(id)).withValues(v).build());
      }
    }
    run(resolver, ops);
    removed.save();
  }

  // ---------- removal tracking ----------

  /** Card keys the user removed from a row, with when (or the engagement time they were removed at). */
  static final class Removed {
    private final SharedPreferences mPrefs;
    private final String mPref;
    private final Map<String, Long> mEntries = new HashMap<>();
    private boolean mChanged;

    Removed(SharedPreferences prefs, String pref) {
      mPrefs = prefs;
      mPref = pref;
      long oldest = System.currentTimeMillis() - REMOVED_KEEP_MS;
      for (String entry : prefs.getStringSet(pref, new HashSet<>())) {
        int bar = entry.lastIndexOf('|');
        if (bar <= 0) continue;
        try {
          long at = Long.parseLong(entry.substring(bar + 1));
          if (at >= oldest) mEntries.put(entry.substring(0, bar), at);
          else mChanged = true;
        } catch (NumberFormatException ignored) {
          mChanged = true;
        }
      }
    }

    /** `engagedAt` 0 records the removal time; otherwise the engagement it was removed at. */
    void add(String key, long engagedAt) {
      if (mEntries.containsKey(key)) return;
      mEntries.put(key, engagedAt > 0 ? engagedAt : System.currentTimeMillis());
      mChanged = true;
    }

    /** Removed, unless `engagedAt` shows it was watched again after the removal. */
    boolean contains(String key, long engagedAt) {
      Long at = mEntries.get(key);
      return at != null && (engagedAt <= 0 || engagedAt <= at);
    }

    void forget(String key) {
      if (mEntries.remove(key) != null) mChanged = true;
    }

    void save() {
      if (!mChanged) return;
      Set<String> out = new HashSet<>();
      for (Map.Entry<String, Long> e : mEntries.entrySet()) out.add(e.getKey() + "|" + e.getValue());
      mPrefs.edit().putStringSet(mPref, out).apply();
      mChanged = false;
    }
  }

  // ---------- helpers ----------

  /** Removes all our channels and Continue Watching cards. `keepRemovals` for the same user. */
  private static void clearAll(Context context, SharedPreferences prefs, boolean keepRemovals) {
    ContentResolver resolver = context.getContentResolver();
    // Only this app's own channels and Watch Next rows are visible to it, so this can't touch others.
    try (Cursor c =
        resolver.query(
            TvContract.Channels.CONTENT_URI, new String[] {TvContract.Channels._ID}, null, null, null)) {
      while (c != null && c.moveToNext()) {
        resolver.delete(TvContract.buildChannelUri(c.getLong(0)), null, null);
      }
    }
    try (Cursor c =
        resolver.query(
            TvContract.WatchNextPrograms.CONTENT_URI,
            new String[] {TvContract.WatchNextPrograms._ID},
            null,
            null,
            null)) {
      while (c != null && c.moveToNext()) {
        resolver.delete(TvContract.buildWatchNextProgramUri(c.getLong(0)), null, null);
      }
    }
    SharedPreferences.Editor edit = prefs.edit();
    for (String pref : prefs.getAll().keySet()) {
      if (keepRemovals && (pref.startsWith(PREF_REMOVED) || pref.equals(PREF_ACCOUNT))) continue;
      if (pref.equals(PREF_BROWSABLE_ASKED)) continue;
      edit.remove(pref);
    }
    edit.apply();
  }

  private static ContentValues valuesOf(Cursor c, String[] columns, int from, int to) {
    ContentValues v = new ContentValues();
    for (int i = from; i < to; i++) {
      if (c.isNull(i)) v.putNull(columns[i]);
      else v.put(columns[i], c.getString(i));
    }
    return v;
  }

  /** True when every column we would write already holds the same value. */
  private static boolean same(ContentValues current, ContentValues wanted) {
    if (current == null) return false;
    for (String column : wanted.keySet()) {
      if (!current.containsKey(column)) continue; // Not read back (e.g. type); compare what we can.
      Object a = current.get(column);
      Object b = wanted.get(column);
      if (!Objects.equals(a == null ? null : a.toString(), b == null ? null : b.toString())) return false;
    }
    return true;
  }

  /** Applies all operations in one batch, falling back to one at a time if the batch fails. */
  private static void run(ContentResolver resolver, ArrayList<ContentProviderOperation> ops) {
    if (ops.isEmpty()) return;
    try {
      resolver.applyBatch(TvContract.AUTHORITY, ops);
      return;
    } catch (Exception e) {
      Log.w(TAG, "Batch update failed; applying one at a time", e);
    }
    for (ContentProviderOperation op : ops) {
      try {
        ArrayList<ContentProviderOperation> one = new ArrayList<>();
        one.add(op);
        resolver.applyBatch(TvContract.AUTHORITY, one);
      } catch (Exception e) {
        Log.w(TAG, "Home screen operation failed: " + op.getUri(), e);
      }
    }
  }
}
