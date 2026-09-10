package com.kutumlabs.chatapp.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class MutableClock extends Clock {
    private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException("UTC only");
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
