package com.exteragram.messenger.badges.source;

import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.api.model.ProfileStatus;

import java.util.Objects;

/**
 * exteraGram 12.10.6 cached badge entry. Package-private like the original Kotlin data class;
 * modules construct it through getDeclaredConstructor(BadgeDTO, ProfileStatus, boolean).
 */
final class BadgeInfo {

    private final BadgeDTO badge;
    private final ProfileStatus status;
    private final boolean canChangeBadge;

    public BadgeInfo(BadgeDTO badge, ProfileStatus status, boolean canChangeBadge) {
        this.badge = badge;
        this.status = status;
        this.canChangeBadge = canChangeBadge;
    }

    public static BadgeInfo copy$default(BadgeInfo badgeInfo, BadgeDTO badge, ProfileStatus status, boolean canChangeBadge, int mask, Object unused) {
        if ((mask & 1) != 0) {
            badge = badgeInfo.badge;
        }
        if ((mask & 2) != 0) {
            status = badgeInfo.status;
        }
        if ((mask & 4) != 0) {
            canChangeBadge = badgeInfo.canChangeBadge;
        }
        return badgeInfo.copy(badge, status, canChangeBadge);
    }

    public final BadgeInfo copy(BadgeDTO badge, ProfileStatus status, boolean canChangeBadge) {
        return new BadgeInfo(badge, status, canChangeBadge);
    }

    public final BadgeDTO getBadge() {
        return badge;
    }

    public final ProfileStatus getStatus() {
        return status;
    }

    public final boolean getCanChangeBadge() {
        return canChangeBadge;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BadgeInfo)) {
            return false;
        }
        BadgeInfo o = (BadgeInfo) other;
        return Objects.equals(badge, o.badge) && status == o.status && canChangeBadge == o.canChangeBadge;
    }

    @Override
    public int hashCode() {
        return ((((badge == null ? 0 : badge.hashCode()) * 31) + (status == null ? 0 : status.hashCode())) * 31) + Boolean.hashCode(canChangeBadge);
    }

    @Override
    public String toString() {
        return "BadgeInfo(badge=" + badge + ", status=" + status + ", canChangeBadge=" + canChangeBadge + ")";
    }
}
