package com.exteragram.messenger.api.db;

import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.api.dto.NowPlayingInfoDTO;
import com.exteragram.messenger.api.dto.ProfileDTO;

import java.util.List;

import kotlin.Unit;
import kotlin.coroutines.Continuation;

/** exteraGram 12.10.6 profiles DAO (Room in exteraGram, plain SQLite in StaticGram; results are returned directly). */
public interface ProfileDao {

    Object deleteProfiles(List<Long> ids, Continuation<? super Integer> continuation);

    Object getAll(Continuation<? super List<ProfileDTO>> continuation);

    Object getById(long id, Continuation<? super ProfileDTO> continuation);

    Object insertAll(List<ProfileDTO> profiles, Continuation<? super Unit> continuation);

    Object updateBadge(long id, BadgeDTO badge, Continuation<? super Integer> continuation);

    Object updateNowPlaying(long id, NowPlayingInfoDTO nowPlaying, Continuation<? super Integer> continuation);
}
