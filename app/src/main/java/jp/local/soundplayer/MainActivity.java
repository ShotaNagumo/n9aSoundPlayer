package jp.local.soundplayer;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupWindow;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.window.OnBackInvokedDispatcher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Three-screen local audio player UI: library, now playing, and playlists. */
public final class MainActivity extends android.app.Activity {
    private static final int REQ_AUDIO = 100;
    private static final int DEFAULT_ACCENT = 0xFFA78BFA;
    private final int bg = Color.rgb(16, 19, 23), surface = Color.rgb(26, 31, 37), raised = Color.rgb(37, 44, 53), text = Color.rgb(243, 241, 246), muted = Color.rgb(180, 176, 188);
    private int accent = DEFAULT_ACCENT;
    private final ArrayDeque<String> pageHistory = new ArrayDeque<>();
    private long exitDeadline;
    private PopupWindow exitHint;
    private final Runnable hideExitHint = this::dismissExitHint;
    private final Handler handler = new Handler();
    private final ArrayList<MusicTrack> library = new ArrayList<>();
    private PlaylistStore playlists;
    private PlaybackService service;
    private LinearLayout root;
    private SeekBar playerSeek;
    private TextView playerPosition;
    private TextView playerDuration;
    private String screen = "library";
    private boolean recentFirst;
    private boolean selectingTracks;
    private ListView libraryList;
    private TrackAdapter libraryAdapter;
    private Button addSelectedButton;
    private final Set<String> selectedTrackUris = new HashSet<>();
    private final Runnable ticker = new Runnable() { @Override public void run() { updatePlayerProgress(); handler.postDelayed(this, 700); } };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).service();
            service.setUiListener(() -> runOnUiThread(MainActivity.this::renderCurrent));
            service.controller().restore(library);
            renderCurrent();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            screen = state.getString("screen", "library");
            ArrayList<String> history = state.getStringArrayList("pageHistory");
            if (history != null) pageHistory.addAll(history);
        }
        accent = getSharedPreferences("appearance", MODE_PRIVATE).getInt("accent", accent);
        getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
        playlists = new PlaylistStore(this);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(bg);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> { android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets; });
        root.requestApplyInsets();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        startService(new Intent(this, PlaybackService.class));
        bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, REQ_AUDIO); else loadLibrary();
        handler.post(ticker);
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        dismissExitHint();
        if (service != null) { service.setUiListener(null); if (isFinishing()) service.shutdown(); }
        unbindService(connection);
        playlists.close();
        if (isFinishing()) stopService(new Intent(this, PlaybackService.class));
        super.onDestroy();
        if (isFinishing()) android.os.Process.killProcess(android.os.Process.myPid());
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) { super.onRequestPermissionsResult(requestCode, permissions, grants); if (requestCode == REQ_AUDIO && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) loadLibrary(); else toast("音源を表示するには音楽と音声へのアクセスを許可してください"); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("screen", screen);
        state.putStringArrayList("pageHistory", new ArrayList<>(pageHistory));
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() { handleBack(); }

    private void loadLibrary() {
        library.clear();
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String[] projection = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME};
        String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0";
        try (Cursor c = getContentResolver().query(collection, projection, selection, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (c != null) while (c.moveToNext()) {
                String filename = c.getString(4);
                if (filename == null || (!filename.toLowerCase().endsWith(".mp3") && !filename.toLowerCase().endsWith(".wav"))) continue;
                long id = c.getLong(0);
                library.add(new MusicTrack(id, c.getString(1), c.getString(2), Uri.withAppendedPath(collection, String.valueOf(id)).toString(), c.getLong(3)));
            }
        }
        if (service != null) service.controller().restore(library);
        renderCurrent();
    }

    private void renderCurrent() { if ("settings".equals(screen)) renderSettings(); else if ("player".equals(screen)) renderPlayer(); else if ("playlists".equals(screen)) renderPlaylists(); else renderLibrary(); }
    private void enterPage(String target) {
        if (!target.equals(screen)) {
            pageHistory.push(screen);
            selectingTracks = false;
            selectedTrackUris.clear();
            screen = target;
            exitDeadline = 0;
            dismissExitHint();
        }
    }
    private void handleBack() {
        if (selectingTracks) { selectingTracks = false; selectedTrackUris.clear(); renderCurrent(); return; }
        if (!pageHistory.isEmpty()) { screen = pageHistory.pop(); dismissExitHint(); exitDeadline = 0; renderCurrent(); return; }
        long now = SystemClock.elapsedRealtime();
        if (exitDeadline > now) { dismissExitHint(); if (service != null) service.shutdown(); stopService(new Intent(this, PlaybackService.class)); finishAndRemoveTask(); return; }
        exitDeadline = now + 5000;
        dismissExitHint();
        TextView message = label("もう一度戻るとアプリを終了します", 14, text);
        message.setPadding(dp(20), dp(12), dp(20), dp(12)); message.setBackground(round(raised, dp(24)));
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        exitHint = new PopupWindow(message, -2, -2, false); exitHint.setTouchable(false);
        exitHint.showAtLocation(root, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(96));
        handler.postDelayed(hideExitHint, 5000);
    }
    private void dismissExitHint() { handler.removeCallbacks(hideExitHint); if (exitHint != null) { exitHint.dismiss(); exitHint = null; } }
    private void clear() { root.removeAllViews(); libraryList = null; libraryAdapter = null; addSelectedButton = null; }
    private LinearLayout beginScrollable() {
        LinearLayout outer = root;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        outer.addView(scroll,verticalWeighted(1)); root=body; return outer;
    }
    private void renderLibrary() {
        enterPage("library"); clear();
        LinearLayout toolbar = row(); toolbar.setPadding(dp(20), 0, dp(12), 0); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView libraryTitle = label("ライブラリ", 28, text);
        Button select = chip(selectingTracks ? "完了" : "選択", selectingTracks);
        select.setOnClickListener(v -> { selectingTracks = !selectingTracks; if (!selectingTracks) selectedTrackUris.clear(); renderLibrary(); });
        toolbar.addView(libraryTitle, weighted(1)); toolbar.addView(select, lp(dp(76), dp(48))); root.addView(toolbar, lp(-1, dp(72)));
        LinearLayout filters = row(); filters.setPadding(dp(16), 0, dp(16), dp(8));
        Button all = chip("すべての曲", !recentFirst); all.setOnClickListener(v -> { recentFirst = false; renderLibrary(); });
        Button recent = chip("最近追加", recentFirst); recent.setOnClickListener(v -> { recentFirst = true; renderLibrary(); });
        filters.addView(all, weighted(1)); filters.addView(space(8), lp(dp(8), 1)); filters.addView(recent, weighted(1)); root.addView(filters, lp(-1, dp(56)));
        if (library.isEmpty()) { root.addView(empty("WAV または MP3 の音源がありません\n端末のローカルストレージに音源を追加してください"), verticalWeighted(1)); }
        else {
            ArrayList<MusicTrack> shown = new ArrayList<>(library);
            if (recentFirst) Collections.reverse(shown);
            ListView list = new ListView(this); list.setDividerHeight(dp(1)); list.setDivider(new ColorDrawable(surface)); list.setBackgroundColor(bg);
            libraryList = list; libraryAdapter = new TrackAdapter(shown); list.setAdapter(libraryAdapter);
            list.setOnItemClickListener((p,v,pos,id) -> { if (selectingTracks) toggleTrackSelection(shown.get(pos)); else play(shown, pos); });
            list.setOnItemLongClickListener((p,v,pos,id) -> { if (selectingTracks) toggleTrackSelection(shown.get(pos)); else addToPlaylist(shown.get(pos)); return true; });
            root.addView(list, verticalWeighted(1));
        }
        if (selectingTracks) {
            Button addSelected = chip("選択した " + selectedTrackUris.size() + " 曲をプレイリストに追加", !selectedTrackUris.isEmpty());
            addSelectedButton = addSelected;
            addSelected.setEnabled(!selectedTrackUris.isEmpty());
            addSelected.setOnClickListener(v -> addSelectedTracksToPlaylist());
            LinearLayout.LayoutParams addParams = lp(-1, dp(52)); addParams.setMargins(dp(16), dp(8), dp(16), dp(8)); root.addView(addSelected, addParams);
        }
        addMiniPlayer(); addNavigation();
    }
    private void renderPlayer() {
        enterPage("player"); clear();
        LinearLayout outer = beginScrollable();
        LinearLayout top = row(); top.setPadding(dp(12), dp(8), dp(12), 0);
        Button back = textButton("‹"); back.setTextSize(34); back.setOnClickListener(v -> handleBack());
        TextView title = label("再生中", 18, text); title.setGravity(Gravity.CENTER); Button queue = textButton("キュー"); queue.setOnClickListener(v -> showQueue());
        top.addView(back, lp(dp(56), dp(56))); top.addView(title, weighted(1)); top.addView(queue, lp(dp(72), dp(56))); root.addView(top, lp(-1, dp(64)));
        MusicTrack track = service == null ? null : service.controller().current();
        if (track == null) { root.addView(empty("再生する曲を選択してください"), lp(-1,dp(220))); root=outer; addNavigation(); return; }
        TextView art = label("♫", 96, Color.WHITE); art.setGravity(Gravity.CENTER); art.setBackground(round(accent, 28)); LinearLayout.LayoutParams artLp = lp(dp(248), dp(248)); artLp.gravity = Gravity.CENTER_HORIZONTAL; artLp.topMargin = dp(24); root.addView(art, artLp);
        TextView song = label(track.title, 26, text); song.setGravity(Gravity.CENTER); song.setPadding(dp(20), dp(24), dp(20), 0); root.addView(song, lp(-1, dp(60)));
        TextView artist = label(track.artist, 16, muted); artist.setGravity(Gravity.CENTER); root.addView(artist, lp(-1, dp(36)));
        SeekBar seek = themedSeekBar(); seek.setMax(Math.max(1, service.controller().duration())); seek.setProgress(service.controller().position()); seek.setPadding(dp(26), 0, dp(26), 0); seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar s,int value,boolean fromUser){ if(fromUser && service != null) service.controller().seekTo(value); } public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){} }); playerSeek = seek; root.addView(seek, lp(-1, dp(44)));
        LinearLayout times = row(); times.setPadding(dp(28), 0, dp(28), 0); TextView now = label(time(service.controller().position()), 13, muted); TextView total = label(time(service.controller().duration()), 13, muted); playerPosition = now; playerDuration = total; total.setGravity(Gravity.RIGHT); times.addView(now, weighted(1)); times.addView(total, weighted(1)); root.addView(times, lp(-1, dp(24)));
        LinearLayout controls = row(); controls.setGravity(Gravity.CENTER); controls.setPadding(dp(12), dp(20), dp(12), dp(12));
        Button prev = roundButton("⏮", 29, raised); prev.setOnClickListener(v -> service.controller().previous());
        ImageButton minus = seekButton(R.drawable.ic_replay_10, "10秒戻る"); minus.setOnClickListener(v -> service.controller().seekBy(-10_000));
        Button play = roundButton(service.controller().isPlaying() ? "Ⅱ" : "▶", 37, accent); play.setOnClickListener(v -> service.controller().toggle());
        ImageButton plus = seekButton(R.drawable.ic_forward_10, "10秒進む"); plus.setOnClickListener(v -> service.controller().seekBy(10_000));
        Button next = roundButton("⏭", 29, raised); next.setOnClickListener(v -> service.controller().next());
        int widthDp = (int)((getResources().getDisplayMetrics().widthPixels - outer.getPaddingLeft() - outer.getPaddingRight()) / getResources().getDisplayMetrics().density);
        int side = widthDp >= 376 ? 56 : 48;
        int seekSize = widthDp >= 376 ? 64 : widthDp >= 336 ? 56 : 48;
        int playSize = widthDp >= 376 ? 80 : widthDp >= 336 ? 72 : 64;
        int gap = widthDp >= 336 ? 8 : 6;
        View[] buttons = {prev, minus, play, plus, next};
        int[] sizes = {side, seekSize, playSize, seekSize, side};
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams params = lp(dp(sizes[i]), dp(sizes[i]));
            if (i > 0) params.leftMargin = dp(gap);
            controls.addView(buttons[i], params);
        }
        root.addView(controls, lp(-1, dp(playSize + 36)));
        Button repeat = chip(repeatLabel(), service.controller().repeatMode() != PlaybackController.REPEAT_OFF); repeat.setOnClickListener(v -> service.controller().cycleRepeat()); LinearLayout.LayoutParams rlp = lp(dp(150), dp(48)); rlp.gravity = Gravity.CENTER_HORIZONTAL; rlp.topMargin = dp(12); root.addView(repeat, rlp);
        root=outer; addNavigation();
    }
    private void renderPlaylists() {
        enterPage("playlists"); clear();
        LinearLayout top = row(); top.setPadding(dp(16), 0, dp(12), 0); TextView title = label("プレイリスト", 28, text); Button add = textButton("＋"); add.setTextSize(30); add.setOnClickListener(v -> createPlaylist()); top.addView(title, weighted(1)); top.addView(add, lp(dp(64), dp(72))); root.addView(top, lp(-1, dp(72)));
        List<Playlist> data = playlists.getPlaylists();
        if (data.isEmpty()) root.addView(empty("プレイリストはまだありません\n右上の＋から作成できます"), verticalWeighted(1));
        else { ListView list = new ListView(this); list.setDividerHeight(dp(1)); list.setDivider(new ColorDrawable(surface)); list.setAdapter(new PlaylistAdapter(data)); list.setOnItemClickListener((p,v,pos,id) -> showPlaylist(data.get(pos))); list.setOnItemLongClickListener((p,v,pos,id) -> { playlistMenu(data.get(pos)); return true; }); root.addView(list, verticalWeighted(1)); }
        addMiniPlayer(); addNavigation();
    }

    private void renderSettings() {
        enterPage("settings"); clear();
        LinearLayout outer=beginScrollable();
        root.addView(header("設定", null), lp(-1,dp(72)));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20),dp(12),dp(20),dp(12));
        content.addView(label("n9a Music",24,text),lp(-1,dp(44)));
        content.addView(label("アクセントカラー",18,text),lp(-1,dp(44)));
        ColorPickerView picker = new ColorPickerView(this,accent); content.addView(picker,lp(-1,dp(220)));
        content.addView(label("色相",14,muted),lp(-1,dp(32)));
        SeekBar hue = themedSeekBar(); hue.setMax(359); hue.setProgress((int)picker.hue()); content.addView(hue,lp(-1,dp(48)));
        Button apply = chip("",true);
        Runnable updatePreview = () -> { int color = picker.color(); apply.setText(String.format(java.util.Locale.ROOT,"#%06X - この色を適用",color & 0xFFFFFF)); apply.setTextColor(contrast(color)); apply.setBackground(round(color,dp(14))); };
        picker.onChange(updatePreview); updatePreview.run();
        hue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar s,int p,boolean user){ if(user) picker.hue(p); } public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){} });
        apply.setOnClickListener(v -> applyAccent(picker.color()));
        LinearLayout.LayoutParams params = lp(-1,dp(52)); params.topMargin=dp(16); content.addView(apply,params);
        Button reset = chip("初期設定に戻す", false);
        reset.setOnClickListener(v -> applyAccent(DEFAULT_ACCENT));
        LinearLayout.LayoutParams resetParams = lp(-1,dp(52)); resetParams.topMargin=dp(12); content.addView(reset,resetParams);
        root.addView(content,lp(-1,-2)); root=outer; addNavigation();
    }
    private void applyAccent(int color) {
        accent = color;
        getSharedPreferences("appearance",MODE_PRIVATE).edit().putInt("accent",accent).commit();
        if (service != null) service.onPlaybackChanged();
        renderSettings();
    }
    private int contrast(int color) { return Color.luminance(color) > 0.45 ? Color.BLACK : Color.WHITE; }
    private SeekBar themedSeekBar() { SeekBar bar=new SeekBar(this); ColorStateList tint=ColorStateList.valueOf(accent); bar.setProgressTintList(tint); bar.setThumbTintList(tint); return bar; }
    private TextView metric(int icon, String value, String description) {
        TextView view=label(value,12,muted); android.graphics.drawable.Drawable drawable=getDrawable(icon); drawable.setTint(muted); drawable.setBounds(0,0,dp(16),dp(16)); view.setCompoundDrawables(drawable,null,null,null); view.setCompoundDrawablePadding(dp(5)); view.setContentDescription(description+" "+value); return view;
    }

    private void addMiniPlayer() { if (service == null || service.controller().current() == null) return; MusicTrack track = service.controller().current(); LinearLayout mini = row(); mini.setGravity(Gravity.CENTER_VERTICAL); mini.setPadding(dp(16), 0, dp(8), 0); mini.setBackgroundColor(surface); TextView info = label(track.title + "\n" + track.artist, 14, text); Button toggle = textButton(service.controller().isPlaying() ? "Ⅱ" : "▶"); toggle.setTextSize(25); toggle.setOnClickListener(v -> service.controller().toggle()); mini.setOnClickListener(v -> renderPlayer()); mini.addView(info, weighted(1)); mini.addView(toggle, lp(dp(64), dp(60))); root.addView(mini, lp(-1, dp(60))); }
    private void updatePlayerProgress() { if (!"player".equals(screen) || service == null || playerSeek == null) return; PlaybackController player = service.controller(); if (!playerSeek.isPressed()) { playerSeek.setMax(Math.max(1, player.duration())); playerSeek.setProgress(player.position()); } if (playerPosition != null) playerPosition.setText(time(player.position())); if (playerDuration != null) playerDuration.setText(time(player.duration())); }
    private void addNavigation() { LinearLayout nav = row(); nav.setBackgroundColor(surface); nav.setGravity(Gravity.CENTER); for (String[] item : new String[][]{{"ライブラリ","library"},{"再生中","player"},{"リスト","playlists"},{"設定","settings"}}) nav.addView(navButton(item[0], item[1]), weighted(1)); root.addView(nav, lp(-1, dp(62))); }
    private Button navButton(String title, String target) { Button button = textButton(title); button.setTextSize(13); button.setPadding(0,0,0,0); button.setTextColor(target.equals(screen) ? accent : muted); button.setOnClickListener(v -> { enterPage(target); renderCurrent(); }); return button; }
    private void play(List<MusicTrack> tracks, int index) { if (service == null) return; startPlaybackService(); service.controller().play(tracks, index); renderPlayer(); }
    private void startPlaybackService() { startForegroundService(new Intent(this, PlaybackService.class)); }
    private String repeatLabel() { int mode = service.controller().repeatMode(); return mode == PlaybackController.REPEAT_ONE ? "↻  1曲リピート" : mode == PlaybackController.REPEAT_ALL ? "↻  全曲リピート" : "↻  リピート オフ"; }
    private void showQueue() { if (service == null) return; List<MusicTrack> queue = service.controller().queue(); new AlertDialog.Builder(this).setTitle("再生キュー").setAdapter(new TrackAdapter(queue), (d, which) -> play(queue, which)).setNegativeButton("閉じる", null).show(); }
    private void addToPlaylist(MusicTrack track) { List<Playlist> data = playlists.getPlaylists(); if (data.isEmpty()) { toast("先にプレイリストを作成してください"); return; } String[] names = new String[data.size()]; for(int i=0;i<data.size();i++) names[i]=data.get(i).name; new AlertDialog.Builder(this).setTitle("プレイリストに追加").setItems(names, (d, which) -> { if (playlists.add(data.get(which).id, track)) toast("追加しました"); else toast("すでに登録されています"); }).show(); }
    private void toggleTrackSelection(MusicTrack track) {
        if (!selectedTrackUris.add(track.uri)) selectedTrackUris.remove(track.uri);
        if (libraryList != null && libraryAdapter != null) {
            int first = libraryList.getFirstVisiblePosition();
            View firstRow = libraryList.getChildAt(0);
            int offset = firstRow == null ? 0 : firstRow.getTop();
            libraryAdapter.notifyDataSetChanged();
            libraryList.setSelectionFromTop(first, offset);
        }
        if (addSelectedButton != null) {
            boolean hasSelection = !selectedTrackUris.isEmpty();
            addSelectedButton.setText("選択した " + selectedTrackUris.size() + " 曲をプレイリストに追加");
            addSelectedButton.setEnabled(hasSelection);
            addSelectedButton.setTextColor(hasSelection ? contrast(accent) : muted);
            addSelectedButton.setBackground(round(hasSelection ? accent : surface, dp(14)));
        }
    }
    private void addSelectedTracksToPlaylist() {
        List<Playlist> data = playlists.getPlaylists();
        if (data.isEmpty()) { toast("先にプレイリストを作成してください"); return; }
        ArrayList<MusicTrack> tracks = new ArrayList<>();
        for (MusicTrack track : library) if (selectedTrackUris.contains(track.uri)) tracks.add(track);
        String[] names = new String[data.size()]; for (int i = 0; i < data.size(); i++) names[i] = data.get(i).name;
        new AlertDialog.Builder(this).setTitle(tracks.size() + " 曲をプレイリストに追加").setItems(names, (d, which) -> {
            int added = 0;
            for (MusicTrack track : tracks) if (playlists.add(data.get(which).id, track)) added++;
            toast(added + " 曲を追加しました"); selectingTracks = false; selectedTrackUris.clear(); renderLibrary();
        }).show();
    }
    private void createPlaylist() { EditText input = new EditText(this); input.setHint("名前"); input.setSingleLine(); input.setInputType(InputType.TYPE_CLASS_TEXT); new AlertDialog.Builder(this).setTitle("プレイリストを作成").setView(input).setNegativeButton("キャンセル", null).setPositiveButton("作成", (d,w) -> { String name=input.getText().toString().trim(); if(name.isEmpty()) toast("名前を入力してください"); else if(playlists.create(name) == -1) toast("同じ名前のプレイリストがあります"); else renderPlaylists(); }).show(); }
    private void playlistMenu(Playlist playlist) { String[] actions = {"複製", "名前を変更", "削除"}; new AlertDialog.Builder(this).setTitle(playlist.name).setItems(actions, (d, which) -> { if(which==0) duplicatePlaylist(playlist); else if(which==1) renamePlaylist(playlist); else new AlertDialog.Builder(this).setTitle("プレイリストを削除しますか？").setMessage(playlist.name).setNegativeButton("キャンセル",null).setPositiveButton("削除",(x,y)->{ playlists.delete(playlist.id); renderPlaylists(); }).show(); }).show(); }
    private void duplicatePlaylist(Playlist playlist) { EditText input = new EditText(this); input.setText(playlist.name + " のコピー"); input.setSelectAllOnFocus(true); new AlertDialog.Builder(this).setTitle("プレイリストを複製").setView(input).setNegativeButton("キャンセル", null).setPositiveButton("複製", (d,w) -> { String name = input.getText().toString().trim(); if (name.isEmpty()) toast("名前を入力してください"); else if (playlists.duplicate(playlist.id, name) == -1) toast("同じ名前のプレイリストがあります"); else renderPlaylists(); }).show(); }
    private void renamePlaylist(Playlist playlist) { EditText input = new EditText(this); input.setText(playlist.name); input.setSelectAllOnFocus(true); new AlertDialog.Builder(this).setTitle("名前を変更").setView(input).setNegativeButton("キャンセル",null).setPositiveButton("保存",(d,w)->{String name=input.getText().toString().trim(); if(!name.isEmpty()) { playlists.rename(playlist.id,name); renderPlaylists(); }}).show(); }
    private void showPlaylist(Playlist playlist) {
        Dialog dialog = new Dialog(this); LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(16), dp(16), dp(16), dp(16)); page.setBackgroundColor(bg);
        TextView title = label(playlist.name, 24, text); page.addView(title, lp(-1, dp(54))); List<MusicTrack> tracks = playlists.tracks(playlist.id);
        long total = 0; for (MusicTrack track : tracks) total += track.durationMs;
        page.addView(label(time(total), 15, muted), lp(-1, dp(32)));
        Button playAll = chip("▶  このプレイリストを再生", true); playAll.setOnClickListener(v -> { if(!tracks.isEmpty()){ dialog.dismiss(); play(tracks,0); }}); page.addView(playAll, lp(-1, dp(48)));
        ListView list = new ListView(this); list.setAdapter(new TrackAdapter(tracks)); list.setOnItemClickListener((p,v,pos,id) -> { dialog.dismiss(); play(tracks,pos); }); list.setOnItemLongClickListener((p,v,pos,id) -> { editPlaylistTrack(dialog, playlist, tracks.get(pos), pos); return true; }); page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        Button close = textButton("閉じる"); close.setOnClickListener(v -> dialog.dismiss()); page.addView(close, lp(-1, dp(48))); dialog.setContentView(page); dialog.show();
    }
    private void editPlaylistTrack(Dialog parent, Playlist playlist, MusicTrack track, int position) { String[] choices = {"上へ移動", "下へ移動", "プレイリストから削除"}; new AlertDialog.Builder(this).setTitle(track.title).setItems(choices,(d,which)->{ List<MusicTrack> current=playlists.tracks(playlist.id); if(which==0) playlists.move(playlist.id,position,position-1); else if(which==1) playlists.move(playlist.id,position,position+1); else playlists.remove(playlist.id,track.uri); parent.dismiss(); showPlaylist(playlist); }).show(); }
    private TextView header(String title, View right) { TextView view = label(title, 28, text); view.setGravity(Gravity.CENTER_VERTICAL); view.setPadding(dp(20),0,dp(16),0); return view; }
    private TextView empty(String value) { TextView view = label(value, 16, muted); view.setGravity(Gravity.CENTER); return view; }
    private TextView label(String value, float size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private Button textButton(String value) { Button b=new Button(this); b.setText(value); b.setTextColor(text); b.setTextSize(15); b.setAllCaps(false); b.setBackgroundColor(Color.TRANSPARENT); return b; }
    private Button chip(String value, boolean selected) { Button b=textButton(value); b.setTextColor(selected?contrast(accent):muted); b.setBackground(round(selected?accent:surface,dp(14))); return b; }
    private Button roundButton(String value, float size, int color) { Button b=textButton(value); b.setText(value); b.setTextSize(size); b.setTextColor(contrast(color)); b.setGravity(Gravity.CENTER); b.setPadding(0,0,0,0); b.setBackground(round(color,dp(40))); return b; }
    private ImageButton seekButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(contrast(surface)));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setPadding(dp(10),dp(10),dp(10),dp(10));
        button.setBackground(round(surface,dp(40)));
        button.setContentDescription(description);
        return button;
    }
    private GradientDrawable round(int color, int radius) { GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(radius); return g; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private View space(int width) { View v=new View(this); v.setLayoutParams(lp(dp(width),1)); return v; }
    private LinearLayout.LayoutParams lp(int w,int h) { return new LinearLayout.LayoutParams(w,h); }
    private LinearLayout.LayoutParams weighted(float weight) { return new LinearLayout.LayoutParams(0,-1,weight); }
    private LinearLayout.LayoutParams verticalWeighted(float weight) { return new LinearLayout.LayoutParams(-1,0,weight); }
    private int dp(int amount) { return (int)(amount*getResources().getDisplayMetrics().density+.5f); }
    private String time(long milliseconds) { long seconds=Math.max(0,milliseconds/1000); return String.format(java.util.Locale.ROOT,"%02d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60); }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_SHORT).show(); }

    private final class TrackAdapter extends ArrayAdapter<MusicTrack> {
        TrackAdapter(List<MusicTrack> items) { super(MainActivity.this, android.R.layout.simple_list_item_2, items); }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            MusicTrack track=getItem(position); LinearLayout item=row(); item.setGravity(Gravity.CENTER_VERTICAL); item.setPadding(dp(16),dp(10),dp(12),dp(10));
            TextView note=label("♫",28,accent); note.setGravity(Gravity.CENTER); note.setBackground(round(raised,dp(14))); item.addView(note,lp(dp(50),dp(50)));
            LinearLayout details=new LinearLayout(MainActivity.this); details.setOrientation(LinearLayout.VERTICAL); details.setPadding(dp(14),0,0,0);
            TextView title=label(track.title,16,text); title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END); details.addView(title,lp(-1,dp(26)));
            TextView artist=label(track.artist,13,muted); artist.setMaxLines(1); details.addView(artist,lp(-1,dp(22)));
            LinearLayout metrics=row(); metrics.setGravity(Gravity.CENTER_VERTICAL);
            metrics.addView(metric(R.drawable.ic_schedule,time(track.durationMs),"再生時間"),lp(-2,dp(24)));
            TextView count=metric(R.drawable.ic_play_count,String.valueOf(TrackStats.count(MainActivity.this,track.uri)),"再生回数"); LinearLayout.LayoutParams countParams=lp(-2,dp(24)); countParams.leftMargin=dp(14); metrics.addView(count,countParams); details.addView(metrics,lp(-1,dp(24)));
            item.addView(details,new LinearLayout.LayoutParams(0,-2,1));
            if (selectingTracks && "library".equals(screen)) { TextView selected=label(selectedTrackUris.contains(track.uri)?"✓":"○",25,accent); selected.setGravity(Gravity.CENTER); item.addView(selected,lp(dp(44),dp(58))); } return item;
        }
    }
    private final class PlaylistAdapter extends ArrayAdapter<Playlist> {
        PlaylistAdapter(List<Playlist> items) { super(MainActivity.this, android.R.layout.simple_list_item_2, items); }
        @Override public View getView(int position, View convert, ViewGroup parent) { Playlist item=getItem(position); LinearLayout row=row(); row.setPadding(dp(16),dp(10),dp(16),dp(10)); row.setGravity(Gravity.CENTER_VERTICAL); TextView icon=label("☷",26,accent); icon.setGravity(Gravity.CENTER); icon.setBackground(round(raised,dp(14))); TextView data=label(item.name+"\n"+time(item.durationMs)+"  ·  "+item.trackCount+" 曲",17,text); data.setPadding(dp(14),0,0,0); row.addView(icon,lp(dp(56),dp(56))); row.addView(data,new LinearLayout.LayoutParams(0,dp(60),1)); return row; }
    }
}
