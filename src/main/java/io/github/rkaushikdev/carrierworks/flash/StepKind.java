package io.github.rkaushikdev.carrierworks.flash;

/** What one step of a flash plan does to the part. */
public enum StepKind {
    /** Clear a sector or subsector back to ones. */
    ERASE("erase"),
    /** Write one page, or the part of one an image covers. */
    PROGRAM("program"),
    /** Read a chunk back and compare it. */
    VERIFY("verify");

    private final String value;

    StepKind(String value) {
        this.value = value;
    }

    /** The name the step is printed and listed under. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
