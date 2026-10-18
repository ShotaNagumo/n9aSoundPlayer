package jp.local.soundplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;

/** Foreground service keeps local audio playing while the activity is backgrounded. */
public final class PlaybackService extends Service implements PlaybackController.Listener {
    public static final String ACTION_TOGGLE = "jp.local.soundplayer.TOGGLE";
    public static final String ACTION_PREVIOUS = "jp.local.soundplayer.PREVIOUS";
    public static final String ACTION_NEXT = "jp.local.soundplayer.NEXT";
    private static final String CHANNEL = "local_playback";
    private static final int ID = 11;
    private final Binder binder = new LocalBinder();
    private PlaybackController controller;
    private Runnable uiListener;
    private boolean foreground;
    private boolean closed;

    public final class LocalBinder extends Binder { public PlaybackService service() { return PlaybackService.this; } }
    @Override public void onCreate() { super.onCreate(); controller = new PlaybackController(this); controller.setListener(this); createChannel(); }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(action)) controller.toggle(); else if (ACTION_PREVIOUS.equals(action)) controller.previous(); else if (ACTION_NEXT.equals(action)) controller.next();
        foreground = true;
        startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        return START_NOT_STICKY;
    }
    public PlaybackController controller() { return controller; }
    public void setUiListener(Runnable listener) { uiListener = listener; }
    @Override public void onPlaybackChanged() {
        if (foreground || controller.isPlaying()) {
            foreground = true;
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        }
        if (uiListener != null) uiListener.run();
    }
    @Override public void onPlaybackError(String message) { if (uiListener != null) uiListener.run(); }
    public void shutdown() {
        if (closed) return;
        closed = true;
        uiListener = null;
        controller.setListener(null);
        controller.release();
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).cancel(ID);
        stopSelf();
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        shutdown();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    @Override public void onDestroy() { shutdown(); super.onDestroy(); }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "n9a Music の再生", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("ローカル音源の再生コントロール");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private Notification notification() {
        MusicTrack track = controller.current();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getSharedPreferences("appearance",MODE_PRIVATE).getInt("accent",0xFFA78BFA))
                .setContentTitle(track == null ? "n9a Music" : track.title)
                .setContentText(track == null ? "再生する曲を選択してください" : track.artist)
                .setContentIntent(content)
                .setOngoing(controller.isPlaying())
                .addAction(action(android.R.drawable.ic_media_previous, "前の曲", ACTION_PREVIOUS))
                .addAction(action(controller.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, controller.isPlaying() ? "一時停止" : "再生", ACTION_TOGGLE))
                .addAction(action(android.R.drawable.ic_media_next, "次の曲", ACTION_NEXT))
                .build();
    }
    private Notification.Action action(int icon, String title, String command) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(command);
        PendingIntent pi = PendingIntent.getService(this, command.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(icon, title, pi).build();
    }
}
