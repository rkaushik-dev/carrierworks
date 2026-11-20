package io.github.rkaushikdev.carrierworks;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Staffed hours, holidays and maintenance, for one bench.
 *
 * <p>Two different questions live here and they are not the same one. Staffed hours say whether a
 * person is in the lab to reseat a cable, which decides whether a job that needs hands may be
 * queued at all. Maintenance windows say the bench or one rack is out of use, which stops even the
 * jobs that need nothing from anyone.
 *
 * <p>Times are UTC everywhere, and the calendar carries a fixed offset for the wall clock the lab
 * keeps. The offset does not follow daylight saving on purpose. The overnight regression slot is
 * defined against the building's air handling schedule, which is set once a year and does not shift
 * in March, and having the bench move an hour away from the cooling was worth avoiding.
 *
 * @param hours the staffed window on each weekday that has one
 * @param holidays local dates nobody is in
 * @param windows maintenance booked, in the order it was booked
 * @param utcOffsetMinutes the lab's wall clock, as minutes from UTC
 */
public record BenchCalendar(
        Map<DayOfWeek, ServiceHours> hours,
        Set<LocalDate> holidays,
        List<MaintenanceWindow> windows,
        int utcOffsetMinutes) {

    /** The lab's wall clock unless a site says otherwise: five hours behind UTC. */
    public static final int DEFAULT_UTC_OFFSET_MINUTES = -300;

    private static final int DEFAULT_HORIZON_DAYS = 14;

    public BenchCalendar {
        hours = hours.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(hours));
        holidays = Set.copyOf(holidays);
        windows = List.copyOf(windows);
    }

    /** A bench with no hours, no holidays and no maintenance: it is never staffed. */
    public BenchCalendar() {
        this(Map.of(), Set.of(), List.of(), DEFAULT_UTC_OFFSET_MINUTES);
    }

    /** The usual shape: monday to friday, eight to six. */
    public static BenchCalendar weekdays() {
        return weekdays("08:00-18:00");
    }

    /** Monday to friday, one window a day. */
    public static BenchCalendar weekdays(String text) {
        return weekdays(text, Set.of(), DEFAULT_UTC_OFFSET_MINUTES);
    }

    /** Monday to friday, one window a day, with holidays and the lab's offset. */
    public static BenchCalendar weekdays(String text, Set<LocalDate> holidays, int utcOffsetMinutes) {
        ServiceHours day = ServiceHours.parse(text);
        Map<DayOfWeek, ServiceHours> hours = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek weekday : List.of(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            hours.put(weekday, day);
        }
        return new BenchCalendar(hours, holidays, List.of(), utcOffsetMinutes);
    }

    /** A moment on the lab's wall clock. */
    public OffsetDateTime local(Instant when) {
        return when.atOffset(ZoneOffset.ofTotalSeconds(utcOffsetMinutes * 60));
    }

    /** Whether somebody is in the lab at this moment. */
    public boolean isStaffed(Instant when) {
        OffsetDateTime local = local(when);
        if (holidays.contains(local.toLocalDate())) {
            return false;
        }
        ServiceHours day = hours.get(local.getDayOfWeek());
        return day != null && day.covers(local.getHour() * 60 + local.getMinute());
    }

    /** The bench wide maintenance in force at this moment, if any. */
    public Optional<MaintenanceWindow> maintenanceAt(Instant when) {
        return maintenanceAt(when, Optional.empty());
    }

    /** The maintenance covering this rack at this moment, if any. */
    public Optional<MaintenanceWindow> maintenanceAt(Instant when, String rack) {
        return maintenanceAt(when, Optional.of(rack));
    }

    private Optional<MaintenanceWindow> maintenanceAt(Instant when, Optional<String> rack) {
        return windows.stream()
                .filter(window -> window.covers(when) && window.appliesTo(rack))
                .findFirst();
    }

    /** Why work cannot happen anywhere now, or nothing when it can. */
    public Optional<String> blocked(Instant when) {
        return blocked(maintenanceAt(when), when);
    }

    /** Why work cannot happen on this rack now, or nothing when it can. */
    public Optional<String> blocked(Instant when, String rack) {
        return blocked(maintenanceAt(when, rack), when);
    }

    private Optional<String> blocked(Optional<MaintenanceWindow> window, Instant when) {
        if (window.isPresent()) {
            return Optional.of("maintenance: " + window.get().reason());
        }
        if (!isStaffed(when)) {
            return Optional.of("outside staffed hours");
        }
        return Optional.empty();
    }

    /** {@link #nextStaffed(Instant, int)} looking two weeks ahead. */
    public Optional<Instant> nextStaffed(Instant when) {
        return nextStaffed(when, DEFAULT_HORIZON_DAYS);
    }

    /**
     * The next minute somebody is in the lab, searched a minute at a time.
     *
     * <p>A minute is coarse enough to be cheap and fine enough that nobody notices, and the horizon
     * stops a bench with no hours at all from looping forever.
     */
    public Optional<Instant> nextStaffed(Instant when, int horizonDays) {
        Instant candidate = when.truncatedTo(ChronoUnit.MINUTES);
        Instant limit = when.plus(Duration.ofDays(horizonDays));
        while (!candidate.isAfter(limit)) {
            if (isStaffed(candidate) && maintenanceAt(candidate).isEmpty()) {
                return Optional.of(candidate);
            }
            candidate = candidate.plus(Duration.ofMinutes(1));
        }
        return Optional.empty();
    }

    /** The calendar with one more window booked, refusing one that overlaps its own scope. */
    public BenchCalendar withWindow(MaintenanceWindow window) {
        for (MaintenanceWindow existing : windows) {
            if (existing.scope().equals(window.scope()) && existing.overlaps(window)) {
                throw new ValidationError(
                        "that window overlaps one already booked", "window", Details.of("scope", window.scope()));
            }
        }
        List<MaintenanceWindow> booked = new ArrayList<>(windows);
        booked.add(window);
        return new BenchCalendar(hours, holidays, booked, utcOffsetMinutes);
    }
}
