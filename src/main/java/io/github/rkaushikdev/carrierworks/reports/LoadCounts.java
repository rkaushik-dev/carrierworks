package io.github.rkaushikdev.carrierworks.reports;

/**
 * How the loads of one image went on the bench.
 *
 * @param passed how many loads passed
 * @param failed how many failed after reaching the board
 */
public record LoadCounts(int passed, int failed) {}
