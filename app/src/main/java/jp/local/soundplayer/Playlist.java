package jp.local.soundplayer;

public final class Playlist {
    public final long id;
    public final String name;
    public final int trackCount;
    public long durationMs;

    public Playlist(long id, String name, int trackCount) {
        this.id = id;
        this.name = name;
        this.trackCount = trackCount;
    }
}
