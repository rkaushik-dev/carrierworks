package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.ThermalState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the whole bench stands at one moment.
 *
 * @param at the moment it was read
 * @param site the site's name
 * @param boards how many boards are in each state, by state name in name order
 * @param leasesHeld how many leases are live
 * @param leasesExpiring how many of those run out soon
 * @param queueWaiting how many requests are waiting for a board
 * @param queueLongestWaitSeconds how long the longest of them has waited, in whole seconds
 * @param jobsQueued how many programming jobs are queued
 * @param jobsRunning how many are running
 * @param thermal how many boards are in each thermal state, by state name in name order
 * @param calibrationOverdue the boards out of calibration, in tag order
 */
public record BenchOverview(
        Instant at,
        String site,
        Map<String, Integer> boards,
        int leasesHeld,
        int leasesExpiring,
        int queueWaiting,
        long queueLongestWaitSeconds,
        int jobsQueued,
        int jobsRunning,
        Map<String, Integer> thermal,
        List<String> calibrationOverdue) {

    public BenchOverview {
        boards = Collections.unmodifiableMap(new LinkedHashMap<>(boards));
        thermal = Collections.unmodifiableMap(new LinkedHashMap<>(thermal));
        calibrationOverdue = List.copyOf(calibrationOverdue);
    }

    /** How many boards could be taken right now. */
    public int available() {
        return boards.getOrDefault(BoardStatus.AVAILABLE.value(), 0);
    }

    /** How many boards the bench has, in any state. */
    public int totalBoards() {
        return boards.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Nothing on fire: no board in any thermal state but normal, nothing faulted, and nothing out of
     * calibration. A board that has stopped reporting counts against this, because a silent board
     * is not known to be fine.
     */
    public boolean healthy() {
        int alarms = thermal.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(ThermalState.NORMAL.value()))
                .mapToInt(Map.Entry::getValue)
                .sum();
        return alarms == 0 && boards.getOrDefault(BoardStatus.FAULTED.value(), 0) == 0 && calibrationOverdue.isEmpty();
    }

    /** {@code lab-a: 2 of 3 free, 1 held, 0 waiting, 0 jobs queued, 1 out of calibration}. */
    public String describe() {
        String line = site + ": " + available() + " of " + totalBoards() + " free, " + leasesHeld + " held, "
                + queueWaiting + " waiting, " + jobsQueued + " jobs queued";
        if (!calibrationOverdue.isEmpty()) {
            line = line + ", " + calibrationOverdue.size() + " out of calibration";
        }
        return line;
    }
}
