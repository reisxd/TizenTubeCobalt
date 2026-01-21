// Copyright 2026 TizenTube Contributors
// SPDX-License-Identifier: Apache-2.0

package dev.cobalt.coat.tvchannel;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Bridge between JavaScript and Android TV Channels.
 * This class provides methods that can be called from the web layer
 * to update home screen channels.
 */
public class TvChannelBridge {
    private static final String TAG = "TvChannelBridge";

    // YouTube video ID: 11 alphanumeric characters, dashes, underscores
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{11}$");
    // Only allow HTTPS URLs for thumbnails (YouTube domains)
    private static final Pattern THUMBNAIL_URL_PATTERN =
            Pattern.compile("^https://(i\\.ytimg\\.com|img\\.youtube\\.com|i[0-9]*\\.ytimg\\.com)/.*$");

    private final TvChannelManager channelManager;

    public TvChannelBridge(Context context) {
        this.channelManager = TvChannelManager.getInstance(context);
    }

    /**
     * Called from JavaScript when the app starts.
     * Initializes TV channels.
     */
    public void initialize() {
        Log.d(TAG, "Initializing TV Channels from JS bridge");
        channelManager.initializeChannels();
    }

    /**
     * Called from JavaScript when video playback progress updates.
     * Updates the "Continue Watching" channel.
     *
     * @param videoJson JSON string with video information:
     *                  {"videoId": "xxx", "title": "...", "description": "...",
     *                   "thumbnailUrl": "...", "channelName": "...",
     *                   "durationMs": 123456, "positionMs": 12345}
     */
    public void updateWatchProgress(String videoJson) {
        try {
            JSONObject json = new JSONObject(videoJson);
            VideoItem video = parseVideoItem(json);
            channelManager.addToContinueWatching(video);
        } catch (Exception e) {
            Log.e(TAG, "Failed to update watch progress", e);
        }
    }

    /**
     * Called from JavaScript when video playback completes.
     * Removes video from "Continue Watching" channel.
     *
     * @param videoId The YouTube video ID
     */
    public void markAsWatched(String videoId) {
        if (!isValidVideoId(videoId)) {
            Log.w(TAG, "Invalid video ID, ignoring markAsWatched");
            return;
        }
        Log.d(TAG, "Marking video as watched: " + videoId);
        channelManager.removeFromContinueWatching(videoId);
    }

    private boolean isValidVideoId(String videoId) {
        return videoId != null && VIDEO_ID_PATTERN.matcher(videoId).matches();
    }

    private boolean isValidThumbnailUrl(String url) {
        return url == null || THUMBNAIL_URL_PATTERN.matcher(url).matches();
    }

    /**
     * Called from JavaScript to update recommendations.
     *
     * @param videosJson JSON array string with video information
     */
    public void updateRecommendations(String videosJson) {
        try {
            JSONArray jsonArray = new JSONArray(videosJson);
            List<VideoItem> videos = new ArrayList<>();

            for (int i = 0; i < jsonArray.length() && i < 10; i++) {
                JSONObject json = jsonArray.getJSONObject(i);
                videos.add(parseVideoItem(json));
            }

            channelManager.updateRecommendations(videos);
        } catch (Exception e) {
            Log.e(TAG, "Failed to update recommendations", e);
        }
    }

    private VideoItem parseVideoItem(JSONObject json) throws Exception {
        String videoId = json.getString("videoId");
        if (!isValidVideoId(videoId)) {
            throw new IllegalArgumentException("Invalid video ID format");
        }

        VideoItem.Builder builder = new VideoItem.Builder(videoId);

        if (json.has("title")) {
            // Sanitize title - limit length to prevent memory issues
            String title = json.getString("title");
            if (title.length() > 200) {
                title = title.substring(0, 200);
            }
            builder.setTitle(title);
        }
        if (json.has("description")) {
            // Sanitize description - limit length
            String description = json.getString("description");
            if (description.length() > 500) {
                description = description.substring(0, 500);
            }
            builder.setDescription(description);
        }
        if (json.has("thumbnailUrl")) {
            String thumbnailUrl = json.getString("thumbnailUrl");
            // Only allow YouTube thumbnail URLs (HTTPS only)
            if (isValidThumbnailUrl(thumbnailUrl)) {
                builder.setThumbnailUrl(thumbnailUrl);
            } else {
                Log.w(TAG, "Invalid thumbnail URL rejected: " + thumbnailUrl);
            }
        }
        if (json.has("channelName")) {
            String channelName = json.getString("channelName");
            if (channelName.length() > 100) {
                channelName = channelName.substring(0, 100);
            }
            builder.setChannelName(channelName);
        }
        if (json.has("durationMs")) {
            int durationMs = json.getInt("durationMs");
            // Sanity check: max 24 hours
            if (durationMs > 0 && durationMs < 86400000) {
                builder.setDurationMs(durationMs);
            }
        }
        if (json.has("positionMs")) {
            int positionMs = json.getInt("positionMs");
            if (positionMs >= 0 && positionMs < 86400000) {
                builder.setPositionMs(positionMs);
            }
        }

        return builder.build();
    }
}
