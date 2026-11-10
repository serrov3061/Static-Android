package com.exteragram.messenger.api.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.api.dto.NowPlayingInfoDTO;
import com.exteragram.messenger.api.dto.ProfileDTO;
import com.exteragram.messenger.api.model.ProfileStatus;
import com.exteragram.messenger.api.model.ProfileType;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import kotlin.Unit;
import kotlin.coroutines.Continuation;

/**
 * Plain-SQLite implementation of exteraGram's Room DAO over the same "extera-database" file and
 * ProfileDTO table schema (id, type, status, badge JSON, nowPlaying JSON, deleted, canChangeBadge,
 * plus StaticGram's nullable frame and frameParams JSON),
 * so modules that write the table directly keep working. Every method completes synchronously.
 */
public final class ProfileDao_Impl implements ProfileDao {

    private static final Gson gson = new Gson();
    private final ExteraDatabase database;

    public ProfileDao_Impl(ExteraDatabase database) {
        this.database = database;
    }

    private SQLiteDatabase db() {
        return database.getWritableDatabase();
    }

    @Override
    public Object insertAll(List<ProfileDTO> list, Continuation<? super Unit> continuation) {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            for (ProfileDTO p : list) {
                ContentValues values = new ContentValues();
                values.put("id", p.getId());
                values.put("type", (p.getType() != null ? p.getType() : ProfileType.USER).name());
                values.put("status", (p.getStatus() != null ? p.getStatus() : ProfileStatus.DEFAULT).name());
                values.put("badge", p.getBadge() != null ? gson.toJson(p.getBadge()) : null);
                values.put("nowPlaying", p.getNowPlaying() != null ? gson.toJson(p.getNowPlaying()) : null);
                if (p.getDeleted() != null) {
                    values.put("deleted", p.getDeleted() ? 1 : 0);
                } else {
                    values.putNull("deleted");
                }
                if (p.getCanChangeBadge() != null) {
                    values.put("canChangeBadge", p.getCanChangeBadge() ? 1 : 0);
                } else {
                    values.putNull("canChangeBadge");
                }
                values.put("frame", p.getFrame());
                Map<String, String> params = p.getFrameParams();
                values.put("frameParams", params.isEmpty() ? null : gson.toJson(params));
                values.put("bannerId", p.getBannerId());
                values.put("bannerType", p.getBannerType());
                values.put("number", p.getNumber());
                db.insertWithOnConflict("ProfileDTO", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return Unit.INSTANCE;
    }

    @Override
    public Object getAll(Continuation<? super List<ProfileDTO>> continuation) {
        ArrayList<ProfileDTO> result = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT * FROM ProfileDTO", null)) {
            while (c.moveToNext()) {
                ProfileDTO p = read(c);
                if (p != null) {
                    result.add(p);
                }
            }
        }
        return result;
    }

    @Override
    public Object getById(long id, Continuation<? super ProfileDTO> continuation) {
        try (Cursor c = db().rawQuery("SELECT * FROM ProfileDTO WHERE id = ?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    @Override
    public Object deleteProfiles(List<Long> list, Continuation<? super Integer> continuation) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        StringBuilder sb = new StringBuilder("DELETE FROM ProfileDTO WHERE id IN (");
        String[] args = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            sb.append(i == 0 ? "?" : ",?");
            args[i] = String.valueOf(list.get(i));
        }
        sb.append(")");
        SQLiteDatabase db = db();
        db.execSQL(sb.toString(), args);
        try (Cursor c = db.rawQuery("SELECT changes()", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Removes every row (used when a full profile list replaces the cache). */
    public int deleteAll() {
        return db().delete("ProfileDTO", null, null);
    }

    @Override
    public Object updateBadge(long id, BadgeDTO badge, Continuation<? super Integer> continuation) {
        ContentValues values = new ContentValues();
        values.put("badge", badge != null ? gson.toJson(badge) : null);
        return db().update("ProfileDTO", values, "id = ?", new String[]{String.valueOf(id)});
    }

    /** StaticGram: avatar frame and its settings of one profile (null clears them). */
    public int updateFrame(long id, String frame, Map<String, String> params) {
        ContentValues values = new ContentValues();
        values.put("frame", frame);
        values.put("frameParams", frame == null || params == null || params.isEmpty() ? null : gson.toJson(params));
        return db().update("ProfileDTO", values, "id = ?", new String[]{String.valueOf(id)});
    }

    /** StaticGram: the profile banner of one profile (null clears it). */
    public int updateBanner(long id, String bannerId, String bannerType) {
        ContentValues values = new ContentValues();
        values.put("bannerId", bannerId);
        values.put("bannerType", bannerId == null ? null : bannerType);
        return db().update("ProfileDTO", values, "id = ?", new String[]{String.valueOf(id)});
    }

    @Override
    public Object updateNowPlaying(long id, NowPlayingInfoDTO nowPlaying, Continuation<? super Integer> continuation) {
        ContentValues values = new ContentValues();
        values.put("nowPlaying", nowPlaying != null ? gson.toJson(nowPlaying) : null);
        return db().update("ProfileDTO", values, "id = ?", new String[]{String.valueOf(id)});
    }

    private static ProfileDTO read(Cursor c) {
        try {
            long id = c.getLong(c.getColumnIndexOrThrow("id"));
            ProfileType type = parseEnum(ProfileType.class, c.getString(c.getColumnIndexOrThrow("type")), ProfileType.USER);
            ProfileStatus status = parseEnum(ProfileStatus.class, c.getString(c.getColumnIndexOrThrow("status")), ProfileStatus.DEFAULT);
            int badgeIdx = c.getColumnIndexOrThrow("badge");
            BadgeDTO badge = c.isNull(badgeIdx) ? null : gson.fromJson(c.getString(badgeIdx), BadgeDTO.class);
            NowPlayingInfoDTO nowPlaying = null;
            int npIdx = c.getColumnIndex("nowPlaying");
            if (npIdx >= 0 && !c.isNull(npIdx)) {
                try {
                    nowPlaying = gson.fromJson(c.getString(npIdx), NowPlayingInfoDTO.class);
                } catch (Exception ignore) {
                }
            }
            int delIdx = c.getColumnIndex("deleted");
            Boolean deleted = delIdx < 0 || c.isNull(delIdx) ? null : c.getInt(delIdx) != 0;
            int chIdx = c.getColumnIndex("canChangeBadge");
            Boolean canChange = chIdx < 0 || c.isNull(chIdx) ? null : c.getInt(chIdx) != 0;
            int frameIdx = c.getColumnIndex("frame");
            String frame = frameIdx < 0 || c.isNull(frameIdx) ? null : c.getString(frameIdx);
            Map<String, String> frameParams = null;
            int fpIdx = c.getColumnIndex("frameParams");
            if (fpIdx >= 0 && !c.isNull(fpIdx)) {
                try {
                    frameParams = gson.fromJson(c.getString(fpIdx), new TypeToken<TreeMap<String, String>>() {}.getType());
                } catch (Exception ignore) {
                }
            }
            int biIdx = c.getColumnIndex("bannerId");
            int btIdx = c.getColumnIndex("bannerType");
            String bannerId = biIdx < 0 || c.isNull(biIdx) ? null : c.getString(biIdx);
            String bannerType = btIdx < 0 || c.isNull(btIdx) ? null : c.getString(btIdx);
            int numIdx = c.getColumnIndex("number");
            Integer number = numIdx < 0 || c.isNull(numIdx) ? null : c.getInt(numIdx);
            return new ProfileDTO(id, type, status, badge, nowPlaying, deleted, canChange, frame, frameParams).withBanner(bannerId, bannerType).withNumber(number);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> cls, String value, T fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(cls, value);
        } catch (Exception e) {
            return fallback;
        }
    }
}
