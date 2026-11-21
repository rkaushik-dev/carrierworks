package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.services.BenchOverview;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole bench at one moment: the page on the wall in the lab.
 *
 * @param at the moment it describes
 * @param site which site it is
 * @param boards how many boards are in each state, by state
 * @param available how many boards could be taken right now
 * @param totalBoards how many boards the bench has
 * @param leasesHeld how many leases are live
 * @param leasesExpiring how many of those run out soon
 * @param queueWaiting how many requests are waiting for a board
 * @param queueLongestWaitSeconds how long the longest of those has waited, in whole seconds
 * @param jobsQueued how many programming jobs are waiting to run
 * @param jobsRunning how many are running
 * @param thermal how many boards are in each thermal state, by state
 * @param calibrationOverdue the boards out of calibration, in tag order
 * @param healthy whether nothing on the bench needs anybody's attention
 * @param summary the whole of it as one sentence
 */
public record OverviewOut(
        Instant at,
        String site,
        Map<String, Integer> boards,
        int available,
        int totalBoards,
        int leasesHeld,
        int leasesExpiring,
        int queueWaiting,
        long queueLongestWaitSeconds,
        int jobsQueued,
        int jobsRunning,
        Map<String, Integer> thermal,
        List<String> calibrationOverdue,
        boolean healthy,
        String summary) {

    public OverviewOut {
        boards = Collections.unmodifiableMap(new LinkedHashMap<>(boards));
        thermal = Collections.unmodifiableMap(new LinkedHashMap<>(thermal));
        calibrationOverdue = List.copyOf(calibrationOverdue);
    }

    /** The overview, with its sentence. */
    public static OverviewOut of(BenchOverview state) {
        return new OverviewOut(
                state.at(),
                state.site(),
                state.boards(),
                state.available(),
                state.totalBoards(),
                state.leasesHeld(),
                state.leasesExpiring(),
                state.queueWaiting(),
                state.queueLongestWaitSeconds(),
                state.jobsQueued(),
                state.jobsRunning(),
                state.thermal(),
                state.calibrationOverdue(),
                state.healthy(),
                state.describe());
    }
}
