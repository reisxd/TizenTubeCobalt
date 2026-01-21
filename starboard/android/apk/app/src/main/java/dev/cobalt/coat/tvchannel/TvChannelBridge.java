// Copyright 2026 TizenTube Contributors
// SPDX-License-Identifier: Apache-2.0

package dev.cobalt.coat.tvchannel;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge between JavaScript and Android TV Channels.
 * This class provides methods that can be called from the web layer
 * to update home screen channels.
 */
public class TvChannelBridge {
    private static final String TAG = "TvChannelBridge";

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
        Log.d(TAG, "Marking video as watched: " + videoId);
        channelManager.removeFromContinueWatching(videoId);
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
        VideoItem.Builder builder = new VideoItem.Builder(json.getString("videoId"));

        if (json.has("title")) {
            builder.setTitle(json.getString("title"));
        }
        if (json.has("description")) {
            builder.setDescription(json.getString("description"));
        }
        if (json.has("thumbnailUrl")) {
            builder.setThumbnailUrl(json.getString("thumbnailUrl"));
        }
        if (json.has("channelName")) {
            builder.setChannelName(json.getString("channelName"));
        }
        if (json.has("durationMs")) {
            builder.setDurationMs(json.getInt("durationMs"));
        }
        if (json.has("positionMs")) {
            builder.setPositionMs(json.getInt("positionMs"));
        }

        return builder.build();
    }
}
