package io.github.rkaushikdev.carrierworks.jtag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What several scans of one chain add up to.
 *
 * <p>Consistently right, consistently wrong and sometimes right are three different findings with
 * three different remedies, and only the last one needs a person with a magnifier.
 *
 * @param expected the chain that was expected, as its log line
 * @param attempts every scan, in the order they were made
 */
public record ChainDiagnosis(String expected, List<ChainAttempt> attempts) {

    /** The shape of what the scans saw. */
    public enum Verdict {
        /** No scans were made. */
        NOT_TESTED("not tested"),
        /** Every scan read back the chain on file. */
        HEALTHY("healthy"),
        /** No scan read back the chain on file. */
        FAILED("failed"),
        /** Some scans did and some did not, which is the one worth a person's time. */
        INTERMITTENT("intermittent");

        private final String value;

        Verdict(String value) {
            this.value = value;
        }

        /** The name the verdict is printed under. */
        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public ChainDiagnosis {
        attempts = List.copyOf(attempts);
    }

    /** How many scans read back the chain on file. */
    public int good() {
        return (int) attempts.stream().filter(ChainAttempt::ok).count();
    }

    /** How many did not. */
    public int bad() {
        return attempts.size() - good();
    }

    /** Whether every scan that came back read back exactly the same bits. */
    public boolean stable() {
        return attempts.stream()
                        .flatMap(attempt -> attempt.capture().stream())
                        .map(BitVector::toString)
                        .distinct()
                        .count()
                <= 1;
    }

    /** What the scans add up to. */
    public Verdict verdict() {
        if (attempts.isEmpty()) {
            return Verdict.NOT_TESTED;
        }
        if (bad() == 0) {
            return Verdict.HEALTHY;
        }
        if (good() == 0) {
            return Verdict.FAILED;
        }
        return Verdict.INTERMITTENT;
    }

    /** An intermittent chain is worth another go; a dead one is not. */
    public boolean worthARetry() {
        return verdict() == Verdict.INTERMITTENT;
    }

    /** The complaint from the first scan that had one. */
    public Optional<String> firstError() {
        return attempts.stream()
                .map(ChainAttempt::error)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * The complaint that came up most often, which is usually the real one. A tie goes to the one
     * that came up first.
     */
    public Optional<String> commonError() {
        Map<String, Long> counts = attempts.stream()
                .map(ChainAttempt::error)
                .flatMap(Optional::stream)
                .filter(error -> !error.isEmpty())
                .collect(Collectors.groupingBy(error -> error, LinkedHashMap::new, Collectors.counting()));
        String common = null;
        long most = 0;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (entry.getValue() > most) {
                common = entry.getKey();
                most = entry.getValue();
            }
        }
        return Optional.ofNullable(common);
    }

    /**
     * One line for a log: the verdict and how many scans agreed, then for anything but a healthy
     * chain the most common complaint and whether the scans disagreed with each other.
     */
    public String describe() {
        Verdict verdict = verdict();
        String line = verdict + ": " + good() + " of " + attempts.size() + " scans agreed with " + expected;
        if (verdict == Verdict.HEALTHY) {
            return line;
        }
        Optional<String> error = commonError();
        if (error.isPresent()) {
            line = line + ", " + error.get();
        }
        if (!stable()) {
            line = line + ", and the scans did not even agree with each other";
        }
        return line;
    }
}
