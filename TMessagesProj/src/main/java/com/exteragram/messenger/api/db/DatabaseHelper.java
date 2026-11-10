package com.exteragram.messenger.api.db;

import com.exteragram.messenger.api.dto.BoostySubscriberDTO;
import com.exteragram.messenger.api.dto.ProfileDTO;

import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import kotlin.coroutines.Continuation;

/**
 * exteraGram API database helper. Profiles (badges) are stored in the plain-SQLite "extera-database";
 * Boosty is an exteraGram-only service, so its list stays empty.
 * insertProfiles/deleteProfiles keep exteraGram's (List, Continuation) signatures for modules.
 */
public final class DatabaseHelper {

    public static final DatabaseHelper INSTANCE = new DatabaseHelper();

    private DatabaseHelper() {
    }

    public static void getBoostySubscribers(Consumer<List<BoostySubscriberDTO>> callback) {
        callback.accept(new ArrayList<>());
    }

    private static ProfileDao_Impl dao() {
        return (ProfileDao_Impl) ExteraDatabase.INSTANCE.getInstance().profileDao();
    }

    public final Object insertProfiles(List<ProfileDTO> profiles, Continuation<? super Boolean> continuation) {
        try {
            dao().insertAll(profiles, com.exteragram.messenger.api.ApiController.CONTINUATION);
            return Boolean.TRUE;
        } catch (Exception e) {
            FileLog.e(e);
            return Boolean.FALSE;
        }
    }

    public final Object deleteProfiles(List<Long> ids, Continuation<? super Boolean> continuation) {
        try {
            dao().deleteProfiles(ids, com.exteragram.messenger.api.ApiController.CONTINUATION);
            return Boolean.TRUE;
        } catch (Exception e) {
            FileLog.e(e);
            return Boolean.FALSE;
        }
    }

    /** Full list sync: drops the ids that are no longer listed and upserts the rest. */
    public final boolean replaceProfiles(List<ProfileDTO> profiles) {
        try {
            ProfileDao_Impl dao = dao();
            ArrayList<Long> stale = new ArrayList<>();
            java.util.HashSet<Long> keep = new java.util.HashSet<>();
            for (ProfileDTO p : profiles) {
                keep.add(p.getId());
            }
            Object all = dao.getAll(null);
            if (all instanceof List) {
                for (Object o : (List<?>) all) {
                    if (o instanceof ProfileDTO && !keep.contains(((ProfileDTO) o).getId())) {
                        stale.add(((ProfileDTO) o).getId());
                    }
                }
            }
            if (!stale.isEmpty()) {
                deleteProfiles(stale, null);
            }
            return Boolean.TRUE.equals(insertProfiles(profiles, null));
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }
}
