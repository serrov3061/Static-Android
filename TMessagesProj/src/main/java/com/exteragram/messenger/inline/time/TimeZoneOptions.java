package com.exteragram.messenger.inline.time;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import kotlin.jvm.internal.DefaultConstructorMarker;

public final class TimeZoneOptions {
    private final ZoneId local;
    private final ZonedDateTime now;

    public TimeZoneOptions(ZoneId zoneId, ZonedDateTime zonedDateTime) {
        this.local = zoneId;
        this.now = zonedDateTime;
    }

    public TimeZoneOptions(ZoneId zoneId, ZonedDateTime zonedDateTime, int i) {
        this(zoneId, (i & 2) != 0 ? null : zonedDateTime);
    }

    public final ZoneId getLocal() {
        return this.local;
    }

    public final ZonedDateTime getNow() {
        return this.now;
    }
}
