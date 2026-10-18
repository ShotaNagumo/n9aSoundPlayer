package jp.local.soundplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Owns the platform player and the current in-memory queue. */
public final class PlaybackController {
    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;
    private final Context context;
    private final SharedPreferences state;
    private final ArrayList<MusicTrack> queue = new ArrayList<>();
    private MediaPlayer player;
    private int index = -1;
    private int repeatMode;
    private Listener listener;
    private boolean prepared;
    private boolean counted;

    public interface Listener { void onPlaybackChanged(); void onPlaybackError(String message); }

    public PlaybackController(Context context) {
        this.context = context.getApplicationContext();
        state = context.getSharedPreferences("playback", Context.MODE_PRIVATE);
        repeatMode = state.getInt("repeat", REPEAT_OFF);
    }
    public void setListener(Listener listener) { this.listener = listener; }
    public List<MusicTrack> queue() { return new ArrayList<>(queue); }
    public MusicTrack current() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }
    public int repeatMode() { return repeatMode; }
    public boolean isPlaying() { return prepared && player != null && player.isPlaying(); }
    public int position() { try { return !prepared || player == null ? 0 : player.getCurrentPosition(); } catch (IllegalStateException e) { return 0; } }
    public int duration() { try { return !prepared || player == null ? (current() == null ? 0 : (int) current().durationMs) : player.getDuration(); } catch (IllegalStateException e) { return 0; } }

    public void play(List<MusicTrack> newQueue, int newIndex) {
        if (newQueue == null || newQueue.isEmpty()) return;
        queue.clear(); queue.addAll(newQueue); index = Math.max(0, Math.min(newIndex, queue.size() - 1));
        startCurrent(0);
    }

    public void toggle() { if (isPlaying()) pause(); else if (player != null) resume(); else if (current() != null) startCurrent(0); }
    public void pause() { if (isPlaying()) { player.pause(); persist(); changed(); } }
    public void resume() { if (prepared && player != null) { player.start(); countStart(); persist(); changed(); } }
    public void seekBy(int milliseconds) { if (player != null) { seekTo(position() + milliseconds); } }
    public void seekTo(int milliseconds) { if (prepared && player != null) { player.seekTo(Math.max(0, Math.min(milliseconds, duration()))); persist(); } }
    public void previous() { if (position() > 3000) { seekTo(0); return; } if (queue.isEmpty()) return; index = index > 0 ? index - 1 : (repeatMode == REPEAT_ALL ? queue.size() - 1 : 0); startCurrent(0); }
    public void next() { if (queue.isEmpty()) return; if (index < queue.size() - 1) { index++; startCurrent(0); } else if (repeatMode == REPEAT_ALL) { index = 0; startCurrent(0); } else { pause(); seekTo(0); } }
    public void cycleRepeat() { repeatMode = (repeatMode + 1) % 3; state.edit().putInt("repeat", repeatMode).apply(); changed(); }

    public void restore(List<MusicTrack> library) {
        String uri = state.getString("uri", null); int savedPosition = state.getInt("position", 0);
        if (uri == null || !queue.isEmpty()) return;
        for (MusicTrack track : library) if (uri.equals(track.uri)) { queue.add(track); index = 0; startCurrent(savedPosition, false); return; }
    }

    private void startCurrent(int fromMs) { startCurrent(fromMs, true); }

    private void startCurrent(int fromMs, boolean autoPlay) {
        MusicTrack track = current(); if (track == null) return;
        releasePlayer();
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        counted = false;
        player.setOnPreparedListener(mp -> { prepared = true; if (fromMs > 0) mp.seekTo(fromMs); if (autoPlay) { mp.start(); countStart(); } persist(); changed(); });
        player.setOnCompletionListener(mp -> { if (repeatMode == REPEAT_ONE) startCurrent(0); else next(); });
        player.setOnErrorListener((mp, what, extra) -> { error("再生できませんでした"); return true; });
        try { player.setDataSource(context, track.asUri()); player.prepareAsync(); changed(); }
        catch (IOException | IllegalArgumentException | SecurityException e) { error("ファイルにアクセスできませんでした"); releasePlayer(); }
    }

    public void release() { persist(); releasePlayer(); }
    private void countStart() { if (!counted && current() != null) { TrackStats.started(context, current().uri); counted = true; } }
    private void releasePlayer() { prepared = false; if (player != null) { try { player.reset(); player.release(); } catch (IllegalStateException ignored) { } player = null; } }
    private void persist() { MusicTrack track = current(); if (track != null) state.edit().putString("uri", track.uri).putInt("position", position()).putInt("repeat", repeatMode).commit(); }
    private void changed() { if (listener != null) listener.onPlaybackChanged(); }
    private void error(String text) { if (listener != null) listener.onPlaybackError(text); changed(); }
}
