// Copyright 2026 TizenTube Contributors
// SPDX-License-Identifier: Apache-2.0

package dev.cobalt.coat.tvchannel;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.tvprovider.media.tv.Channel;
import androidx.tvprovider.media.tv.ChannelLogoUtils;
import androidx.tvprovider.media.tv.PreviewProgram;
import androidx.tvprovider.media.tv.TvContractCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages Android TV home screen channels for TizenTube.
 * Provides "Continue Watching" and "Recommended" rows on the home screen.
 */
public class TvChannelManager {
    private static final String TAG = "TvChannelManager";
    private static final String PREFS_NAME = "tizentube_tv_channels";
    private static final String PREF_CONTINUE_WATCHING_CHANNEL_ID = "continue_watching_channel_id";
    private static final String PREF_RECOMMENDED_CHANNEL_ID = "recommended_channel_id";

    private static final String CHANNEL_CONTINUE_WATCHING = "Continue Watching";
    private static final String CHANNEL_RECOMMENDED = "Recommended";

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService executor;
    private final boolean isTvProviderAvailable;

    private static TvChannelManager instance;

    public static synchronized TvChannelManager getInstance(Context context) {
        if (instance == null) {
            instance = new TvChannelManager(context.getApplicationContext());
        }
        return instance;
    }

    private TvChannelManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.executor = Executors.newSingleThreadExecutor();
        this.isTvProviderAvailable = checkTvProviderAvailable();

        if (!isTvProviderAvailable) {
            Log.i(TAG, "TV Provider not available on this device, channels feature disabled");
        }
    }

    /**
     * Check if TV Provider is available on this device.
     * It's only available on Android TV (Leanback) devices with API 26+.
     */
    private boolean checkTvProviderAvailable() {
        // TV Provider requires API 26+ for preview channels
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }

        // Check if this is an Android TV device (has Leanback feature)
        PackageManager pm = context.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return false;
        }

        // Verify TV Provider is accessible (properly close cursor to prevent leak)
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    TvContractCompat.Channels.CONTENT_URI,
                    new String[]{TvContractCompat.Channels._ID},
                    null, null, null);
            return cursor != null;
        } catch (Exception e) {
            Log.w(TAG, "TV Provider not accessible", e);
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Check if TV channels feature is available.
     */
    public boolean isAvailable() {
        return isTvProviderAvailable;
    }

    /**
     * Initialize channels on first launch or app update.
     */
    public void initializeChannels() {
        if (!isTvProviderAvailable) {
            return;
        }
        executor.execute(() -> {
            try {
                createOrUpdateChannel(CHANNEL_CONTINUE_WATCHING, PREF_CONTINUE_WATCHING_CHANNEL_ID);
                createOrUpdateChannel(CHANNEL_RECOMMENDED, PREF_RECOMMENDED_CHANNEL_ID);
                Log.i(TAG, "TV Channels initialized successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize TV channels", e);
            }
        });
    }

    private long createOrUpdateChannel(String channelName, String prefKey) {
        long existingChannelId = prefs.getLong(prefKey, -1);

        // Check if channel still exists
        if (existingChannelId != -1 && !channelExists(existingChannelId)) {
            existingChannelId = -1;
        }

        if (existingChannelId == -1) {
            // Create new channel
            Channel.Builder builder = new Channel.Builder()
                    .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                    .setDisplayName(channelName)
                    .setAppLinkIntentUri(Uri.parse("tizentube://home"));

            Uri channelUri = context.getContentResolver().insert(
                    TvContractCompat.Channels.CONTENT_URI,
                    builder.build().toContentValues());

            if (channelUri != null) {
                existingChannelId = Long.parseLong(channelUri.getLastPathSegment());
                prefs.edit().putLong(prefKey, existingChannelId).apply();

                // Set channel logo
                setChannelLogo(existingChannelId);

                // Request to make the channel visible (user must approve)
                TvContractCompat.requestChannelBrowsable(context, existingChannelId);

                Log.i(TAG, "Created channel: " + channelName + " with ID: " + existingChannelId);
            }
        }

        return existingChannelId;
    }

    private boolean channelExists(long channelId) {
        Uri channelUri = TvContractCompat.buildChannelUri(channelId);
        try (Cursor cursor = context.getContentResolver().query(
                channelUri, null, null, null, null)) {
            return cursor != null && cursor.getCount() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void setChannelLogo(long channelId) {
        try {
            // Use app icon as channel logo
            // Note: Don't recycle bitmap immediately as ChannelLogoUtils may use it asynchronously
            Bitmap logo = BitmapFactory.decodeResource(
                    context.getResources(),
                    context.getApplicationInfo().icon);
            if (logo != null) {
                ChannelLogoUtils.storeChannelLogo(context, channelId, logo);
                // Logo will be garbage collected when no longer needed
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to set channel logo", e);
        }
    }

    /**
     * Add a video to the Continue Watching channel.
     */
    public void addToContinueWatching(VideoItem video) {
        if (!isTvProviderAvailable) {
            return;
        }
        executor.execute(() -> {
            try {
                long channelId = prefs.getLong(PREF_CONTINUE_WATCHING_CHANNEL_ID, -1);
                if (channelId == -1) {
                    Log.w(TAG, "Continue watching channel not found");
                    return;
                }

                // Remove existing entry for this video
                removeProgram(channelId, video.getVideoId());

                // Add new entry
                PreviewProgram.Builder builder = createProgramBuilder(video, channelId)
                        .setLastPlaybackPositionMillis(video.getPositionMs())
                        .setDurationMillis(video.getDurationMs());

                context.getContentResolver().insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        builder.build().toContentValues());

                Log.d(TAG, "Added to continue watching: " + video.getTitle());
            } catch (Exception e) {
                Log.e(TAG, "Failed to add to continue watching", e);
            }
        });
    }

    /**
     * Update recommended videos channel.
     */
    public void updateRecommendations(List<VideoItem> videos) {
        if (!isTvProviderAvailable) {
            return;
        }
        executor.execute(() -> {
            try {
                long channelId = prefs.getLong(PREF_RECOMMENDED_CHANNEL_ID, -1);
                if (channelId == -1) {
                    Log.w(TAG, "Recommended channel not found");
                    return;
                }

                // Clear existing programs
                clearChannel(channelId);

                // Add new programs
                for (VideoItem video : videos) {
                    PreviewProgram.Builder builder = createProgramBuilder(video, channelId);

                    context.getContentResolver().insert(
                            TvContractCompat.PreviewPrograms.CONTENT_URI,
                            builder.build().toContentValues());
                }

                Log.d(TAG, "Updated recommendations with " + videos.size() + " videos");
            } catch (Exception e) {
                Log.e(TAG, "Failed to update recommendations", e);
            }
        });
    }

    /**
     * Remove a video from Continue Watching when playback completes.
     */
    public void removeFromContinueWatching(String videoId) {
        if (!isTvProviderAvailable) {
            return;
        }
        executor.execute(() -> {
            try {
                long channelId = prefs.getLong(PREF_CONTINUE_WATCHING_CHANNEL_ID, -1);
                if (channelId != -1) {
                    removeProgram(channelId, videoId);
                    Log.d(TAG, "Removed from continue watching: " + videoId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove from continue watching", e);
            }
        });
    }

    private PreviewProgram.Builder createProgramBuilder(VideoItem video, long channelId) {
        // Use Uri.Builder to prevent URI injection
        Uri intentUri = new Uri.Builder()
                .scheme("https")
                .authority("www.youtube.com")
                .path("/watch")
                .appendQueryParameter("v", video.getVideoId())
                .build();

        PreviewProgram.Builder builder = new PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                .setTitle(video.getTitle())
                .setDescription(video.getDescription())
                .setIntentUri(intentUri)
                .setInternalProviderId(video.getVideoId());

        if (video.getThumbnailUrl() != null) {
            builder.setPosterArtUri(Uri.parse(video.getThumbnailUrl()));
        }

        if (video.getDurationMs() > 0) {
            builder.setDurationMillis(video.getDurationMs());
        }

        if (video.getChannelName() != null) {
            builder.setAuthor(video.getChannelName());
        }

        return builder;
    }

    private void removeProgram(long channelId, String videoId) {
        String selection = TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID + "=? AND " +
                TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID + "=?";
        String[] selectionArgs = {String.valueOf(channelId), videoId};

        context.getContentResolver().delete(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                selection,
                selectionArgs);
    }

    private void clearChannel(long channelId) {
        String selection = TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID + "=?";
        String[] selectionArgs = {String.valueOf(channelId)};

        context.getContentResolver().delete(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                selection,
                selectionArgs);
    }

    /**
     * Get the Continue Watching channel ID.
     */
    public long getContinueWatchingChannelId() {
        return prefs.getLong(PREF_CONTINUE_WATCHING_CHANNEL_ID, -1);
    }

    /**
     * Get the Recommended channel ID.
     */
    public long getRecommendedChannelId() {
        return prefs.getLong(PREF_RECOMMENDED_CHANNEL_ID, -1);
    }

    /**
     * Shutdown the executor service.
     * Should be called when the app is being destroyed.
     */
    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            Log.i(TAG, "TvChannelManager executor shut down");
        }
    }
}
