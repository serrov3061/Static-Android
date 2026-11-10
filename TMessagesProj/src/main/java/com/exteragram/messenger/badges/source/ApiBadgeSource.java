package com.exteragram.messenger.badges.source;

import com.exteragram.messenger.api.ApiController;
import com.exteragram.messenger.api.db.ProfileDao;
import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.api.dto.ProfileDTO;
import com.exteragram.messenger.api.model.ProfileStatus;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import kotlin.Unit;
import kotlin.coroutines.Continuation;

/**
 * exteraGram 12.10.6 badge source: in-memory cache over the profiles table.
 * Field names (cache, profileDao) and method signatures are kept 1:1 because Mandre modules
 * (glow_suite, local_user_arrow) read the cache and hook these methods by reflection.
 * StaticGram: the DAO is plain SQLite and completes synchronously, so the "suspend" methods
 * return their result immediately (never COROUTINE_SUSPENDED) and ignore the continuation.
 */
public final class ApiBadgeSource {

    private final ConcurrentHashMap<Long, BadgeInfo> cache = new ConcurrentHashMap<>();
    /** StaticGram avatar frames by profile id (kept apart from BadgeInfo, whose shape modules rely on). */
    private final ConcurrentHashMap<Long, String> frames = new ConcurrentHashMap<>();
    /** Avatar frame settings by profile id (only for ids with a frame and non-empty settings). */
    private final ConcurrentHashMap<Long, Map<String, String>> frameParams = new ConcurrentHashMap<>();
    /** StaticGram profile banners by profile id: {documentId, kind}. */
    private final ConcurrentHashMap<Long, String[]> banners = new ConcurrentHashMap<>();
    /**
     * exteraGram 13.1.1 supporter badge numbers by profile id (exteraGram keeps it in BadgeInfo.number;
     * kept apart here so the 12.10.6 BadgeInfo shape that modules use by reflection stays unchanged).
     */
    private final ConcurrentHashMap<Long, Integer> numbers = new ConcurrentHashMap<>();
    private final ProfileDao profileDao;

    public ApiBadgeSource(ProfileDao profileDao) {
        this.profileDao = profileDao;
    }

    public BadgeDTO getBadge(long id, boolean isUser) {
        BadgeInfo badgeInfo = cache.get(id);
        // a row without a badge (frame-only user) is not a badge holder
        BadgeDTO badge = badgeInfo != null ? badgeInfo.getBadge() : null;
        return badge != null && badge.getDocumentId() != 0 ? badge : null;
    }

    public final boolean isDeveloper(long id) {
        BadgeInfo badgeInfo = cache.get(id);
        return (badgeInfo != null ? badgeInfo.getStatus() : null) == ProfileStatus.DEVELOPER;
    }

    /** exteraGram 13.1.1: the cached status of a profile, or null when there is no row. */
    public final ProfileStatus getStatus(long id) {
        BadgeInfo badgeInfo = cache.get(id);
        return badgeInfo != null ? badgeInfo.getStatus() : null;
    }

    /** exteraGram 13.1.1: the cached badge number of a profile, or null. */
    public final Integer getNumber(long id) {
        return numbers.get(id);
    }

    public final boolean canChangeBadge(long id) {
        BadgeInfo badgeInfo = cache.get(id);
        return (badgeInfo != null && badgeInfo.getCanChangeBadge()) || isDeveloper(id);
    }

    public final String getFrame(long id) {
        return frames.get(id);
    }

    /** Frame settings (never null; empty = defaults). */
    public final Map<String, String> getFrameParams(long id) {
        Map<String, String> params = frameParams.get(id);
        return params != null ? params : Collections.emptyMap();
    }

    /** The profile banner {documentId, kind} of a profile, or null. */
    public final String[] getBanner(long id) {
        return banners.get(id);
    }

    /** Applies the own banner right after the bot confirmed it (null clears it). */
    public final void updateLocalBanner(long id, String bannerId, String kind) {
        putBanner(id, bannerId, kind);
        if (profileDao instanceof com.exteragram.messenger.api.db.ProfileDao_Impl) {
            ((com.exteragram.messenger.api.db.ProfileDao_Impl) profileDao).updateBanner(id, bannerId, kind);
        }
    }

    private void putBanner(long id, String bannerId, String kind) {
        if (bannerId == null || bannerId.isEmpty() || kind == null) {
            banners.remove(id);
        } else {
            banners.put(id, new String[]{bannerId, kind});
        }
    }

    /** Applies the own frame right after the bot confirmed it (the next sync brings the same value). */
    public final void updateLocalFrame(long id, String frame, Map<String, String> params) {
        boolean none = frame == null || frame.isEmpty();
        putFrame(id, none ? null : frame, params);
        if (profileDao instanceof com.exteragram.messenger.api.db.ProfileDao_Impl) {
            ((com.exteragram.messenger.api.db.ProfileDao_Impl) profileDao).updateFrame(id, none ? null : frame, none ? null : params);
        }
    }

    private void putFrame(long id, String frame, Map<String, String> params) {
        if (frame == null || frame.isEmpty()) {
            frames.remove(id);
            frameParams.remove(id);
            return;
        }
        frames.put(id, frame);
        if (params == null || params.isEmpty()) {
            frameParams.remove(id);
        } else {
            frameParams.put(id, Collections.unmodifiableMap(new TreeMap<>(params)));
        }
    }

    public final Object updateLocalBadge(long id, BadgeDTO badge, Continuation<? super Unit> continuation) {
        cache.computeIfPresent(id, (key, info) -> BadgeInfo.copy$default(info, badge, null, false, 6, null));
        profileDao.updateBadge(id, badge, ApiController.CONTINUATION);
        return Unit.INSTANCE;
    }

    /** Loads the profiles table into the cache (exteraGram only adds; we also drop rows that are gone). */
    public final Object loadToCache(Continuation<? super Unit> continuation) {
        Object all = profileDao.getAll(ApiController.CONTINUATION);
        if (all instanceof List) {
            Set<Long> seen = new HashSet<>();
            for (Object o : (List<?>) all) {
                if (!(o instanceof ProfileDTO)) {
                    continue;
                }
                ProfileDTO profileDTO = (ProfileDTO) o;
                ProfileStatus status = profileDTO.getStatus() != null ? profileDTO.getStatus() : ProfileStatus.DEFAULT;
                cache.put(profileDTO.getId(), new BadgeInfo(profileDTO.getBadge(), status, Boolean.TRUE.equals(profileDTO.getCanChangeBadge())));
                putFrame(profileDTO.getId(), profileDTO.getFrame(), profileDTO.getFrameParams());
                putBanner(profileDTO.getId(), profileDTO.getBannerId(), profileDTO.getBannerType());
                Integer number = profileDTO.getNumber();
                if (number != null && number > 0) {
                    numbers.put(profileDTO.getId(), number);
                } else {
                    numbers.remove(profileDTO.getId());
                }
                seen.add(profileDTO.getId());
            }
            cache.keySet().retainAll(seen);
            frames.keySet().retainAll(seen);
            frameParams.keySet().retainAll(seen);
            banners.keySet().retainAll(seen);
            numbers.keySet().retainAll(seen);
        }
        return Unit.INSTANCE;
    }
}
