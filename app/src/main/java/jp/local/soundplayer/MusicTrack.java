package jp.local.soundplayer;

import android.net.Uri;

/** A local WAV or MP3 item returned by MediaStore. */
public final class MusicTrack {
    public final long id;
    public final String title;
    public final String artist;
    public final String uri;
    public final long durationMs;

    public MusicTrack(long id, String title, String artist, String uri, long durationMs) {
        this.id = id;
        this.title = title == null || title.trim().isEmpty() ? "名称不明" : title;
        this.artist = artist == null || artist.trim().isEmpty() ? "アーティスト不明" : artist;
        this.uri = uri;
        this.durationMs = Math.max(0, durationMs);
    }

    public Uri asUri() {
        return Uri.parse(uri);
    }
}
