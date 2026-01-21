// Copyright 2026 TizenTube Contributors
// SPDX-License-Identifier: Apache-2.0

package dev.cobalt.coat.tvchannel;

/**
 * Represents a video item for TV channel display.
 */
public class VideoItem {
    private final String videoId;
    private final String title;
    private final String description;
    private final String thumbnailUrl;
    private final String channelName;
    private final int durationMs;
    private final int positionMs;

    private VideoItem(Builder builder) {
        this.videoId = builder.videoId;
        this.title = builder.title;
        this.description = builder.description;
        this.thumbnailUrl = builder.thumbnailUrl;
        this.channelName = builder.channelName;
        this.durationMs = builder.durationMs;
        this.positionMs = builder.positionMs;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public String getChannelName() {
        return channelName;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public int getPositionMs() {
        return positionMs;
    }

    public static class Builder {
        private String videoId;
        private String title;
        private String description;
        private String thumbnailUrl;
        private String channelName;
        private int durationMs;
        private int positionMs;

        public Builder(String videoId) {
            this.videoId = videoId;
        }

        public Builder setTitle(String title) {
            this.title = title;
            return this;
        }

        public Builder setDescription(String description) {
            this.description = description;
            return this;
        }

        public Builder setThumbnailUrl(String thumbnailUrl) {
            this.thumbnailUrl = thumbnailUrl;
            return this;
        }

        public Builder setChannelName(String channelName) {
            this.channelName = channelName;
            return this;
        }

        public Builder setDurationMs(int durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder setPositionMs(int positionMs) {
            this.positionMs = positionMs;
            return this;
        }

        public VideoItem build() {
            return new VideoItem(this);
        }
    }
}
