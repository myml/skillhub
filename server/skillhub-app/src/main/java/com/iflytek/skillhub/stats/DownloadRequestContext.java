package com.iflytek.skillhub.stats;

/**
 * Ambient request facts captured when a download happens.
 *
 * @param channel    coarse client channel (web / cli / compat / unknown)
 * @param actorId    authenticated user id, or {@code null} for anonymous downloads
 * @param clientHash salted hash of client address and user agent, never a raw IP
 */
public record DownloadRequestContext(String channel, String actorId, String clientHash) {

    public static final String CHANNEL_WEB = "web";
    public static final String CHANNEL_CLI = "cli";
    public static final String CHANNEL_COMPAT = "compat";
    public static final String CHANNEL_UNKNOWN = "unknown";

    public static DownloadRequestContext unknown() {
        return new DownloadRequestContext(CHANNEL_UNKNOWN, null, null);
    }
}
