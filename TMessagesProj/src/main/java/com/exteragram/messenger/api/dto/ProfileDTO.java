package com.exteragram.messenger.api.dto;

import com.exteragram.messenger.api.model.ProfileStatus;
import com.exteragram.messenger.api.model.ProfileType;
import com.google.gson.annotations.SerializedName;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** exteraGram 12.10.6 (+ 13.1.1 "number") profile row of GET profiles / profiles/updates (same JSON field names). */
public final class ProfileDTO {

    @SerializedName("id")
    private final long id;
    @SerializedName("type")
    private final ProfileType type;
    @SerializedName("status")
    private final ProfileStatus status;
    @SerializedName("badge")
    private final BadgeDTO badge;
    @SerializedName("nowPlaying")
    private final NowPlayingInfoDTO nowPlaying;
    @SerializedName("deleted")
    private final Boolean deleted;
    @SerializedName("canChangeBadge")
    private final Boolean canChangeBadge;
    /** StaticGram extension: avatar frame style ({@code AvatarFrames}), absent when none. */
    @SerializedName("frame")
    private final String frame;
    /**
     * StaticGram extension: avatar frame settings ({@code FrameParams}), a string map, absent when
     * there are none. Kept as a raw JSON element so an unexpected value never fails the list parse.
     */
    @SerializedName("frame_params")
    private final JsonElement frameParams;
    /** StaticGram extension: profile banner document id (decimal string) and kind ("photo"/"video"), absent when none. */
    @SerializedName("banner_id")
    private String bannerId;
    @SerializedName("banner_type")
    private String bannerType;
    /**
     * exteraGram 13.1.1: the supporter badge number (shown as "#N" next to the name and engraved on the
     * arrow), absent/null when the server has not assigned one. Only meaningful for SUPPORTER/DEVELOPER
     * rows with a value &gt; 0 (see BadgesController.getNumber).
     */
    @SerializedName("number")
    private Integer number;

    public ProfileDTO(long id, ProfileType type, ProfileStatus status, BadgeDTO badge, NowPlayingInfoDTO nowPlaying, Boolean deleted, Boolean canChangeBadge) {
        this(id, type, status, badge, nowPlaying, deleted, canChangeBadge, null);
    }

    public ProfileDTO(long id, ProfileType type, ProfileStatus status, BadgeDTO badge, NowPlayingInfoDTO nowPlaying, Boolean deleted, Boolean canChangeBadge, String frame) {
        this(id, type, status, badge, nowPlaying, deleted, canChangeBadge, frame, (JsonElement) null);
    }

    public ProfileDTO(long id, ProfileType type, ProfileStatus status, BadgeDTO badge, NowPlayingInfoDTO nowPlaying, Boolean deleted, Boolean canChangeBadge, String frame, Map<String, String> frameParams) {
        this(id, type, status, badge, nowPlaying, deleted, canChangeBadge, frame, toJson(frameParams));
    }

    private ProfileDTO(long id, ProfileType type, ProfileStatus status, BadgeDTO badge, NowPlayingInfoDTO nowPlaying, Boolean deleted, Boolean canChangeBadge, String frame, JsonElement frameParams) {
        this.frame = frame;
        this.frameParams = frameParams;
        this.id = id;
        this.type = type;
        this.status = status;
        this.badge = badge;
        this.nowPlaying = nowPlaying;
        this.deleted = deleted;
        this.canChangeBadge = canChangeBadge;
    }

    public final long getId() {
        return id;
    }

    /** May be null when the server sends an unknown value; treated as {@link ProfileType#USER}. */
    public final ProfileType getType() {
        return type;
    }

    /** May be null when the server sends an unknown value; treated as {@link ProfileStatus#DEFAULT}. */
    public final ProfileStatus getStatus() {
        return status;
    }

    public final BadgeDTO getBadge() {
        return badge;
    }

    public final NowPlayingInfoDTO getNowPlaying() {
        return nowPlaying;
    }

    public final Boolean getDeleted() {
        return deleted;
    }

    public final Boolean getCanChangeBadge() {
        return canChangeBadge;
    }

    public final String getFrame() {
        return frame;
    }

    public final String getBannerId() {
        return bannerId;
    }

    public final String getBannerType() {
        return bannerType;
    }

    /** exteraGram 13.1.1 ProfileDTO.getNumber(): the badge number or null. */
    public final Integer getNumber() {
        return number;
    }

    /** Sets the badge number (DAO read and copies); null clears it. */
    public final ProfileDTO withNumber(Integer number) {
        this.number = number;
        return this;
    }

    /** Sets the banner (DAO read and copies); null clears it. */
    public final ProfileDTO withBanner(String id, String type) {
        this.bannerId = id;
        this.bannerType = id == null ? null : type;
        return this;
    }

    /** Frame settings as strings (primitive values only; anything else is dropped). Never null. */
    public final Map<String, String> getFrameParams() {
        if (frameParams == null || !frameParams.isJsonObject()) {
            return Collections.emptyMap();
        }
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : frameParams.getAsJsonObject().entrySet()) {
            JsonElement v = e.getValue();
            if (e.getKey() != null && v != null && v.isJsonPrimitive()) {
                out.put(e.getKey(), v.getAsString());
            }
        }
        return out;
    }

    private static JsonElement toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        JsonObject o = new JsonObject();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                o.addProperty(e.getKey(), e.getValue());
            }
        }
        return o;
    }

    public final ProfileDTO copy(long id, ProfileType type, ProfileStatus status, BadgeDTO badge, NowPlayingInfoDTO nowPlaying, Boolean deleted, Boolean canChangeBadge) {
        return new ProfileDTO(id, type, status, badge, nowPlaying, deleted, canChangeBadge, frame, frameParams).withBanner(bannerId, bannerType).withNumber(number);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProfileDTO)) {
            return false;
        }
        ProfileDTO o = (ProfileDTO) other;
        return id == o.id && type == o.type && status == o.status && Objects.equals(badge, o.badge)
                && Objects.equals(nowPlaying, o.nowPlaying) && Objects.equals(deleted, o.deleted)
                && Objects.equals(canChangeBadge, o.canChangeBadge) && Objects.equals(frame, o.frame)
                && Objects.equals(frameParams, o.frameParams) && Objects.equals(bannerId, o.bannerId)
                && Objects.equals(bannerType, o.bannerType) && Objects.equals(number, o.number);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, status, badge, nowPlaying, deleted, canChangeBadge, frame, frameParams, bannerId, bannerType, number);
    }

    @Override
    public String toString() {
        return "ProfileDTO(id=" + id + ", type=" + type + ", status=" + status + ", badge=" + badge
                + ", nowPlaying=" + nowPlaying + ", deleted=" + deleted + ", canChangeBadge=" + canChangeBadge + ", frame=" + frame + ", frameParams=" + frameParams + ", number=" + number + ")";
    }
}
