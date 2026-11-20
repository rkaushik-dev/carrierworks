package io.github.rkaushikdev.carrierworks.reports;

/**
 * Whether an image's build closed timing, and by how much, as its build report says.
 *
 * @param closed whether setup and hold both closed
 * @param worstSlackPs the worse of the setup and hold slack, in picoseconds; negative when it missed
 */
public record TimingClosure(boolean closed, long worstSlackPs) {}
