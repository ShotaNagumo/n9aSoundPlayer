package jp.local.soundplayer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** Persists playlist metadata only. Audio files themselves stay in local storage. */
public final class PlaylistStore extends SQLiteOpenHelper {
    private static final String DB = "playlists.db";

    public PlaylistStore(Context context) {
        super(context, DB, null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE playlists (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE playlist_tracks (_id INTEGER PRIMARY KEY AUTOINCREMENT, playlist_id INTEGER NOT NULL, media_id INTEGER, title TEXT NOT NULL, artist TEXT NOT NULL, uri TEXT NOT NULL, duration INTEGER NOT NULL, position INTEGER NOT NULL, UNIQUE(playlist_id, uri))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public List<Playlist> getPlaylists() {
        List<Playlist> result = new ArrayList<>();
        String sql = "SELECT p._id,p.name,COUNT(t._id),COALESCE(SUM(t.duration),0) FROM playlists p LEFT JOIN playlist_tracks t ON p._id=t.playlist_id GROUP BY p._id,p.name ORDER BY p.name COLLATE NOCASE";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) { Playlist playlist = new Playlist(cursor.getLong(0), cursor.getString(1), cursor.getInt(2)); playlist.durationMs = cursor.getLong(3); result.add(playlist); }
        }
        return result;
    }

    public long create(String name) {
        ContentValues values = new ContentValues(); values.put("name", name.trim());
        return getWritableDatabase().insert("playlists", null, values);
    }

    /** Creates a new playlist with the same ordered local-file references as the source. */
    public long duplicate(long sourceId, String name) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues playlist = new ContentValues();
            playlist.put("name", name.trim());
            long copyId = db.insert("playlists", null, playlist);
            if (copyId == -1) return -1;
            try (Cursor cursor = db.query("playlist_tracks", new String[]{"media_id", "title", "artist", "uri", "duration", "position"}, "playlist_id=?", new String[]{String.valueOf(sourceId)}, null, null, "position ASC")) {
                while (cursor.moveToNext()) {
                    ContentValues track = new ContentValues();
                    track.put("playlist_id", copyId);
                    track.put("media_id", cursor.getLong(0));
                    track.put("title", cursor.getString(1));
                    track.put("artist", cursor.getString(2));
                    track.put("uri", cursor.getString(3));
                    track.put("duration", cursor.getLong(4));
                    track.put("position", cursor.getInt(5));
                    db.insert("playlist_tracks", null, track);
                }
            }
            db.setTransactionSuccessful();
            return copyId;
        } finally {
            db.endTransaction();
        }
    }

    public void rename(long id, String name) {
        ContentValues values = new ContentValues(); values.put("name", name.trim());
        getWritableDatabase().update("playlists", values, "_id=?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try { db.delete("playlist_tracks", "playlist_id=?", new String[]{String.valueOf(id)}); db.delete("playlists", "_id=?", new String[]{String.valueOf(id)}); db.setTransactionSuccessful(); } finally { db.endTransaction(); }
    }

    public List<MusicTrack> tracks(long playlistId) {
        List<MusicTrack> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("playlist_tracks", new String[]{"media_id","title","artist","uri","duration"}, "playlist_id=?", new String[]{String.valueOf(playlistId)}, null, null, "position ASC")) {
            while (cursor.moveToNext()) result.add(new MusicTrack(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4)));
        }
        return result;
    }

    public boolean add(long playlistId, MusicTrack track) {
        ContentValues values = new ContentValues();
        values.put("playlist_id", playlistId); values.put("media_id", track.id); values.put("title", track.title); values.put("artist", track.artist); values.put("uri", track.uri); values.put("duration", track.durationMs); values.put("position", tracks(playlistId).size());
        return getWritableDatabase().insertWithOnConflict("playlist_tracks", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public void remove(long playlistId, String uri) {
        getWritableDatabase().delete("playlist_tracks", "playlist_id=? AND uri=?", new String[]{String.valueOf(playlistId), uri});
        normalizePositions(playlistId);
    }

    public void move(long playlistId, int from, int to) {
        List<MusicTrack> items = tracks(playlistId);
        if (from < 0 || to < 0 || from >= items.size() || to >= items.size() || from == to) return;
        MusicTrack item = items.remove(from); items.add(to, item);
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { for (int i = 0; i < items.size(); i++) { ContentValues v = new ContentValues(); v.put("position", i); db.update("playlist_tracks", v, "playlist_id=? AND uri=?", new String[]{String.valueOf(playlistId), items.get(i).uri}); } db.setTransactionSuccessful(); } finally { db.endTransaction(); }
    }

    private void normalizePositions(long playlistId) {
        List<MusicTrack> items = tracks(playlistId);
        SQLiteDatabase db = getWritableDatabase();
        for (int i = 0; i < items.size(); i++) { ContentValues v = new ContentValues(); v.put("position", i); db.update("playlist_tracks", v, "playlist_id=? AND uri=?", new String[]{String.valueOf(playlistId), items.get(i).uri}); }
    }
}
