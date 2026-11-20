package io.github.rkaushikdev.carrierworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BenchCalendarTest {

    private static Instant utc(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC);
    }

    private static Instant utc(int year, int month, int day, int hour) {
        return utc(year, month, day, hour, 0);
    }

    @Test
    void serviceHoursParse() {
        ServiceHours hours = ServiceHours.parse("08:00-18:30");
        assertThat(hours.opens()).isEqualTo(480);
        assertThat(hours.closes()).isEqualTo(1110);
        assertThat(hours.covers(480)).isTrue();
        assertThat(hours.covers(1110)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "08:00", "18:00-08:00", "8-18", "08:70-18:00", "aa:bb-cc:dd"})
    void badServiceHoursAreRefused(String text) {
        assertThatThrownBy(() -> ServiceHours.parse(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void theBenchIsStaffedDuringTheLocalWorkingDay() {
        BenchCalendar calendar = BenchCalendar.weekdays();
        // 2025-03-05 was a wednesday. 14:00 utc is 09:00 local.
        assertThat(calendar.isStaffed(utc(2025, 3, 5, 14, 0))).isTrue();
        assertThat(calendar.isStaffed(utc(2025, 3, 5, 4, 0))).isFalse();
    }

    @Test
    void theOffsetIsAppliedRatherThanIgnored() {
        BenchCalendar calendar = BenchCalendar.weekdays();
        // 02:00 utc on thursday is 21:00 wednesday local, which is after hours.
        assertThat(calendar.isStaffed(utc(2025, 3, 6, 2, 0))).isFalse();
        assertThat(calendar.isStaffed(utc(2025, 3, 6, 13, 30))).isTrue();
    }

    @Test
    void weekendsAreNotStaffed() {
        BenchCalendar calendar = BenchCalendar.weekdays();
        assertThat(calendar.isStaffed(utc(2025, 3, 8, 15, 0))).isFalse();
        assertThat(calendar.isStaffed(utc(2025, 3, 9, 15, 0))).isFalse();
    }

    @Test
    void holidaysCloseTheLab() {
        BenchCalendar calendar = BenchCalendar.weekdays(
                "08:00-18:00", Set.of(LocalDate.of(2025, 5, 26)), BenchCalendar.DEFAULT_UTC_OFFSET_MINUTES);
        assertThat(calendar.isStaffed(utc(2025, 5, 26, 15, 0))).isFalse();
        assertThat(calendar.isStaffed(utc(2025, 5, 27, 15, 0))).isTrue();
    }

    /**
     * The original refused a timestamp with no zone at run time. Here the calendar takes an {@link
     * Instant}, which is always a moment in UTC, so a zoneless time cannot be handed in at all; the
     * test holds the signature to that.
     */
    @Test
    void aNaiveTimestampIsRefused() throws NoSuchMethodException {
        assertThat(BenchCalendar.class.getMethod("isStaffed", Instant.class)).isNotNull();
        assertThat(Arrays.stream(BenchCalendar.class.getMethods())
                        .filter(method -> method.getName().equals("isStaffed")))
                .allMatch(method -> Arrays.equals(method.getParameterTypes(), new Class<?>[] {Instant.class}));
    }

    @Test
    void aMaintenanceWindowCoversItsOwnStretch() {
        MaintenanceWindow window =
                new MaintenanceWindow(utc(2025, 3, 5, 6, 0), utc(2025, 3, 5, 10, 0), "chilled water loop down");
        assertThat(window.covers(utc(2025, 3, 5, 6, 0))).isTrue();
        assertThat(window.covers(utc(2025, 3, 5, 9, 59))).isTrue();
        assertThat(window.covers(utc(2025, 3, 5, 10, 0))).isFalse();
        assertThat(window.minutes()).isEqualTo(240);
    }

    @Test
    void aWindowNeedsAPositiveLengthAndATimezone() {
        assertThatThrownBy(() -> new MaintenanceWindow(utc(2025, 3, 5, 10), utc(2025, 3, 5, 6), "x"))
                .isInstanceOf(ValidationError.class);
        // A window's ends are instants, which carry their zone; a zoneless one cannot be built.
        assertThat(MaintenanceWindow.class.getRecordComponents())
                .filteredOn(component -> component.getName().equals("start")
                        || component.getName().equals("end"))
                .allMatch(component -> component.getType() == Instant.class);
    }

    @Test
    void aBenchWindowCoversEveryRackAndARackWindowCoversOne() {
        MaintenanceWindow bench = new MaintenanceWindow(utc(2025, 3, 5, 6), utc(2025, 3, 5, 10), "x");
        MaintenanceWindow rack = new MaintenanceWindow(utc(2025, 3, 5, 6), utc(2025, 3, 5, 10), "pdu swap", "rack-2");
        assertThat(bench.appliesTo(Optional.empty())).isTrue();
        assertThat(bench.appliesTo(Optional.of("rack-4"))).isTrue();
        assertThat(rack.appliesTo(Optional.of("rack-2"))).isTrue();
        assertThat(rack.appliesTo(Optional.of("rack-4"))).isFalse();
        assertThat(rack.appliesTo(Optional.empty())).isFalse();
    }

    @Test
    void windowsKnowWhenTheyOverlap() {
        MaintenanceWindow first = new MaintenanceWindow(utc(2025, 3, 5, 6), utc(2025, 3, 5, 10), "a");
        MaintenanceWindow touching = new MaintenanceWindow(utc(2025, 3, 5, 10), utc(2025, 3, 5, 12), "b");
        MaintenanceWindow overlapping = new MaintenanceWindow(utc(2025, 3, 5, 9), utc(2025, 3, 5, 12), "c");
        assertThat(first.overlaps(touching)).isFalse();
        assertThat(first.overlaps(overlapping)).isTrue();
        assertThat(overlapping.overlaps(first)).isTrue();
    }

    @Test
    void maintenanceBlocksWorkEvenInStaffedHours() {
        MaintenanceWindow window =
                new MaintenanceWindow(utc(2025, 3, 5, 14), utc(2025, 3, 5, 16), "chilled water loop down");
        BenchCalendar calendar = BenchCalendar.weekdays().withWindow(window);
        assertThat(calendar.blocked(utc(2025, 3, 5, 15))).contains("maintenance: chilled water loop down");
        assertThat(calendar.blocked(utc(2025, 3, 5, 17))).isEmpty();
    }

    @Test
    void aRackWindowLeavesTheOtherRacksAlone() {
        MaintenanceWindow window =
                new MaintenanceWindow(utc(2025, 3, 5, 14), utc(2025, 3, 5, 16), "pdu swap", "rack-2");
        BenchCalendar calendar = BenchCalendar.weekdays().withWindow(window);
        assertThat(calendar.blocked(utc(2025, 3, 5, 15), "rack-2")).isPresent();
        assertThat(calendar.blocked(utc(2025, 3, 5, 15), "rack-4")).isEmpty();
    }

    @Test
    void outOfHoursIsReportedAsSuch() {
        assertThat(BenchCalendar.weekdays().blocked(utc(2025, 3, 5, 4))).contains("outside staffed hours");
    }

    @Test
    void twoOverlappingWindowsOnOneScopeAreRefused() {
        BenchCalendar calendar = BenchCalendar.weekdays()
                .withWindow(new MaintenanceWindow(utc(2025, 3, 5, 14), utc(2025, 3, 5, 16), "a"));
        assertThatThrownBy(
                        () -> calendar.withWindow(new MaintenanceWindow(utc(2025, 3, 5, 15), utc(2025, 3, 5, 17), "b")))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void windowsOnDifferentScopesMayOverlap() {
        BenchCalendar calendar = BenchCalendar.weekdays()
                .withWindow(new MaintenanceWindow(utc(2025, 3, 5, 14), utc(2025, 3, 5, 16), "a", "rack-2"));
        BenchCalendar extended =
                calendar.withWindow(new MaintenanceWindow(utc(2025, 3, 5, 15), utc(2025, 3, 5, 17), "b", "rack-4"));
        assertThat(extended.windows()).hasSize(2);
    }

    @Test
    void theNextStaffedMinuteIsTheStartOfTheNextWorkingDay() {
        BenchCalendar calendar = BenchCalendar.weekdays();
        assertThat(calendar.nextStaffed(utc(2025, 3, 8, 15, 0))).contains(utc(2025, 3, 10, 13, 0));
    }

    @Test
    void askingWhileStaffedGivesNow() {
        BenchCalendar calendar = BenchCalendar.weekdays();
        Instant now = utc(2025, 3, 5, 14, 30);
        assertThat(calendar.nextStaffed(now)).contains(now);
    }

    @Test
    void maintenancePushesTheNextStaffedMinuteOut() {
        MaintenanceWindow window = new MaintenanceWindow(utc(2025, 3, 5, 13), utc(2025, 3, 5, 15), "hvac");
        BenchCalendar calendar = BenchCalendar.weekdays().withWindow(window);
        assertThat(calendar.nextStaffed(utc(2025, 3, 5, 14))).contains(utc(2025, 3, 5, 15));
    }

    @Test
    void aBenchWithNoHoursNeverOpens() {
        BenchCalendar calendar = new BenchCalendar();
        assertThat(calendar.nextStaffed(utc(2025, 3, 5, 14), 1)).isEmpty();
    }

    @Test
    void theHorizonIsRespected() {
        BenchCalendar calendar = BenchCalendar.weekdays();
        Instant fridayEvening = utc(2025, 3, 7, 23, 30);
        assertThat(calendar.nextStaffed(fridayEvening, 1)).isEmpty();
        assertThat(calendar.nextStaffed(fridayEvening, 3)).contains(utc(2025, 3, 10, 13, 0));
    }

    @Test
    void theSearchStartsFromAWholeMinute() {
        BenchCalendar calendar = BenchCalendar.weekdays();
        Instant ragged = utc(2025, 3, 5, 14, 30).plus(Duration.ofSeconds(45));
        assertThat(calendar.nextStaffed(ragged)).contains(utc(2025, 3, 5, 14, 30));
    }
}
