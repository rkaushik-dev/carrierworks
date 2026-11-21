package io.github.rkaushikdev.carrierworks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock a test sets and moves by hand, so a route runs at a moment of the test's choosing. */
public final class TestClock extends Clock {

    private volatile Instant now;

    public TestClock(Instant now) {
        this.now = now;
    }

    /** Stop the clock at a moment. */
    public void set(Instant moment) {
        now = moment;
    }

    /** Move the clock on. */
    public void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
