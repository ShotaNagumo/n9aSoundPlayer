package jp.local.soundplayer;

import android.content.Context;
import android.content.SharedPreferences;

/** Counts successful starts; pause/resume and restoring a paused track do not count. */
public final class TrackStats {
    private TrackStats() { }
    public static long count(Context context, String uri) {
        return context.getSharedPreferences("track_stats", Context.MODE_PRIVATE).getLong(uri, 0);
    }
    public static void started(Context context, String uri) {
        SharedPreferences prefs = context.getSharedPreferences("track_stats", Context.MODE_PRIVATE);
        prefs.edit().putLong(uri, prefs.getLong(uri, 0) + 1).commit();
    }
}
