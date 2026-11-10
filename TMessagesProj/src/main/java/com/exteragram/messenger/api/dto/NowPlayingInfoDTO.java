package com.exteragram.messenger.api.dto;

import com.exteragram.messenger.api.model.NowPlayingServiceType;

import java.util.Objects;

/** Scrobbling account of the current user (service + username). */
public final class NowPlayingInfoDTO {

    private final NowPlayingServiceType serviceType;
    private String username;

    public NowPlayingInfoDTO(NowPlayingServiceType serviceType, String username) {
        this.serviceType = serviceType;
        this.username = username;
    }

    public NowPlayingServiceType getServiceType() {
        return serviceType;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NowPlayingInfoDTO)) return false;
        NowPlayingInfoDTO that = (NowPlayingInfoDTO) o;
        return serviceType == that.serviceType && Objects.equals(username, that.username);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceType, username);
    }

    @Override
    public String toString() {
        return "NowPlayingInfoDTO(serviceType=" + serviceType + ", username=" + username + ")";
    }
}
