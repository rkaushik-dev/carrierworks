package io.github.rkaushikdev.carrierworks;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * One site's settings, already validated.
 *
 * <p>Settings come from the environment because the bench runs as a service and the service is
 * configured the way every other service in the building is. They are read once into an immutable
 * object rather than looked up where they are used, so a change halfway through a run cannot make
 * the first half of a job disagree with the second.
 *
 * <p>Every value is checked on the way in and the check has teeth. A lease ceiling of zero would
 * silently make every lease expire the moment it was taken, and the place to find that out is at
 * startup rather than at two in the morning.
 */
public record BenchSettings(
        String databaseUrl,
        Path artifactRoot,
        Path journalRoot,
        Path wearLedger,
        String site,
        int defaultLeaseMinutes,
        int maxLeaseMinutes,
        int leaseGraceSeconds,
        int programAttempts,
        int programBackoffSeconds,
        boolean verifyAfterProgram,
        int thermalPollSeconds,
        int thermalStaleSeconds,
        int adapterClockCeilingHz,
        String staffedHours,
        int utcOffsetMinutes,
        boolean requireTimingClosure,
        String apiKeyHeader) {

    /** Every bench variable in the environment starts with this. */
    public static final String ENV_PREFIX = "FPGA_";

    /** The database a site gets when it names none: a file beside wherever the bench runs. */
    public static final String DEFAULT_DATABASE_URL = "jdbc:h2:file:./bench;AUTO_SERVER=TRUE";

    private static final Set<String> TRUE = Set.of("1", "true", "yes", "on");
    private static final Set<String> FALSE = Set.of("0", "false", "no", "off");

    public BenchSettings {
        site = Ids.slug(site);
        if (defaultLeaseMinutes <= 0) {
            throw new ConfigurationError("a lease lasts some minutes", Details.of("setting", "default_lease_minutes"));
        }
        if (maxLeaseMinutes < defaultLeaseMinutes) {
            throw new ConfigurationError(
                    "the lease ceiling is below the default",
                    Details.of(
                            "setting",
                            "max_lease_minutes",
                            "default",
                            defaultLeaseMinutes,
                            "ceiling",
                            maxLeaseMinutes));
        }
        if (leaseGraceSeconds < 0) {
            throw new ConfigurationError("grace cannot be negative", Details.of("setting", "lease_grace_seconds"));
        }
        if (programAttempts < 1 || programAttempts > 10) {
            throw new ConfigurationError(
                    "program attempts are out of range", Details.of("setting", "program_attempts"));
        }
        if (programBackoffSeconds < 0) {
            throw new ConfigurationError("backoff cannot be negative", Details.of("setting", "program_backoff"));
        }
        if (thermalPollSeconds <= 0) {
            throw new ConfigurationError(
                    "the thermal poll has a period", Details.of("setting", "thermal_poll_seconds"));
        }
        if (thermalStaleSeconds <= thermalPollSeconds) {
            throw new ConfigurationError(
                    "a sample would be stale before the next poll",
                    Details.of(
                            "setting",
                            "thermal_stale_seconds",
                            "poll",
                            thermalPollSeconds,
                            "stale",
                            thermalStaleSeconds));
        }
        if (adapterClockCeilingHz < 100_000 || adapterClockCeilingHz > 60_000_000) {
            throw new ConfigurationError("adapter ceiling is implausible", Details.of("setting", "adapter_clock"));
        }
        if (utcOffsetMinutes < -720 || utcOffsetMinutes > 840) {
            throw new ConfigurationError("that offset is not on the planet", Details.of("setting", "utc_offset"));
        }
        if (apiKeyHeader.isBlank()) {
            throw new ConfigurationError("the key header needs a name", Details.of("setting", "api_key_header"));
        }
    }

    /** A working bench with nothing changed. */
    public static BenchSettings defaults() {
        return builder().build();
    }

    /** A builder starting from the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder starting from these settings. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Read the settings out of the process environment, prefix and all. */
    public static BenchSettings fromEnvironment() {
        return fromEnv(System.getenv());
    }

    /**
     * Read the settings out of an environment, prefix and all.
     *
     * <p>Only variables starting {@value #ENV_PREFIX} are looked at, the rest of the name is the
     * setting, and an empty value counts as unset.
     */
    public static BenchSettings fromEnv(Map<String, String> environ) {
        Map<String, String> values = new LinkedHashMap<>();
        environ.forEach((key, value) -> {
            if (key.startsWith(ENV_PREFIX)) {
                values.put(key.substring(ENV_PREFIX.length()).toLowerCase(Locale.ROOT), value);
            }
        });
        Builder builder = builder();
        for (Map.Entry<String, Setting> setting : SETTINGS.entrySet()) {
            String raw = values.get(setting.getKey());
            if (raw != null && !raw.isBlank()) {
                setting.getValue().read(builder, setting.getKey(), raw);
            }
        }
        return builder.build();
    }

    /**
     * A copy with a few settings changed, by the names the environment uses, checked the same way.
     *
     * <p>Values are strings in the environment's form or values of the setting's own type.
     */
    public BenchSettings override(Map<String, ?> changes) {
        Set<String> unknown = new TreeSet<>(changes.keySet());
        unknown.removeAll(SETTINGS.keySet());
        if (!unknown.isEmpty()) {
            throw new ConfigurationError("no such setting", Details.of("setting", String.join(", ", unknown)));
        }
        Builder builder = toBuilder();
        changes.forEach((name, value) -> SETTINGS.get(name).apply(builder, name, value));
        return builder.build();
    }

    /** The longest a lease can run before the sweep takes it back, grace included. */
    public int leaseCeilingSeconds() {
        return maxLeaseMinutes * 60 + leaseGraceSeconds;
    }

    /** One setting: how to read it from text and how to set it on a builder. */
    private record Setting(Kind kind, BiConsumer<Builder, Object> set) {

        void read(Builder builder, String name, String raw) {
            set.accept(builder, kind.parse(name, raw));
        }

        void apply(Builder builder, String name, Object value) {
            Object converted = value instanceof String text ? kind.parse(name, text) : value;
            if (!kind.accepts(converted)) {
                throw new ConfigurationError(
                        "setting is the wrong kind of value", Details.of("setting", name, "given", value));
            }
            set.accept(builder, converted);
        }
    }

    private enum Kind {
        TEXT,
        PATH,
        WHOLE,
        FLAG;

        Object parse(String name, String raw) {
            String cleaned = raw.strip();
            return switch (this) {
                case TEXT -> cleaned;
                case PATH -> Path.of(cleaned);
                case WHOLE -> whole(name, raw, cleaned);
                case FLAG -> flag(name, raw, cleaned.toLowerCase(Locale.ROOT));
            };
        }

        boolean accepts(Object value) {
            return switch (this) {
                case TEXT -> value instanceof String;
                case PATH -> value instanceof Path;
                case WHOLE -> value instanceof Integer;
                case FLAG -> value instanceof Boolean;
            };
        }

        private static Integer whole(String name, String raw, String cleaned) {
            try {
                return Integer.valueOf(cleaned);
            } catch (NumberFormatException failure) {
                throw new ConfigurationError(
                        "setting is not a whole number", Details.of("setting", name, "given", raw));
            }
        }

        private static Boolean flag(String name, String raw, String cleaned) {
            if (TRUE.contains(cleaned)) {
                return true;
            }
            if (FALSE.contains(cleaned)) {
                return false;
            }
            throw new ConfigurationError("setting is not a yes or a no", Details.of("setting", name, "given", raw));
        }
    }

    private static final Map<String, Setting> SETTINGS = settings();

    private static Map<String, Setting> settings() {
        Map<String, Setting> settings = new LinkedHashMap<>();
        settings.put("database_url", new Setting(Kind.TEXT, (b, v) -> b.databaseUrl((String) v)));
        settings.put("artifact_root", new Setting(Kind.PATH, (b, v) -> b.artifactRoot((Path) v)));
        settings.put("journal_root", new Setting(Kind.PATH, (b, v) -> b.journalRoot((Path) v)));
        settings.put("wear_ledger", new Setting(Kind.PATH, (b, v) -> b.wearLedger((Path) v)));
        settings.put("site", new Setting(Kind.TEXT, (b, v) -> b.site((String) v)));
        settings.put("default_lease_minutes", new Setting(Kind.WHOLE, (b, v) -> b.defaultLeaseMinutes((Integer) v)));
        settings.put("max_lease_minutes", new Setting(Kind.WHOLE, (b, v) -> b.maxLeaseMinutes((Integer) v)));
        settings.put("lease_grace_seconds", new Setting(Kind.WHOLE, (b, v) -> b.leaseGraceSeconds((Integer) v)));
        settings.put("program_attempts", new Setting(Kind.WHOLE, (b, v) -> b.programAttempts((Integer) v)));
        settings.put(
                "program_backoff_seconds", new Setting(Kind.WHOLE, (b, v) -> b.programBackoffSeconds((Integer) v)));
        settings.put("verify_after_program", new Setting(Kind.FLAG, (b, v) -> b.verifyAfterProgram((Boolean) v)));
        settings.put("thermal_poll_seconds", new Setting(Kind.WHOLE, (b, v) -> b.thermalPollSeconds((Integer) v)));
        settings.put("thermal_stale_seconds", new Setting(Kind.WHOLE, (b, v) -> b.thermalStaleSeconds((Integer) v)));
        settings.put(
                "adapter_clock_ceiling_hz", new Setting(Kind.WHOLE, (b, v) -> b.adapterClockCeilingHz((Integer) v)));
        settings.put("staffed_hours", new Setting(Kind.TEXT, (b, v) -> b.staffedHours((String) v)));
        settings.put("utc_offset_minutes", new Setting(Kind.WHOLE, (b, v) -> b.utcOffsetMinutes((Integer) v)));
        settings.put("require_timing_closure", new Setting(Kind.FLAG, (b, v) -> b.requireTimingClosure((Boolean) v)));
        settings.put("api_key_header", new Setting(Kind.TEXT, (b, v) -> b.apiKeyHeader((String) v)));
        return settings;
    }

    /** Settings assembled one at a time and checked once, on {@link #build()}. */
    public static final class Builder {

        private String databaseUrl = DEFAULT_DATABASE_URL;
        private Path artifactRoot = Path.of("var/artifacts");
        private Path journalRoot = Path.of("var/journal");
        private Path wearLedger = Path.of("var/wear.json");
        private String site = "lab-a";
        private int defaultLeaseMinutes = 60;
        private int maxLeaseMinutes = 480;
        private int leaseGraceSeconds = 120;
        private int programAttempts = 3;
        private int programBackoffSeconds = 15;
        private boolean verifyAfterProgram = true;
        private int thermalPollSeconds = 20;
        private int thermalStaleSeconds = 180;
        private int adapterClockCeilingHz = 30_000_000;
        private String staffedHours = "08:00-18:00";
        private int utcOffsetMinutes = -300;
        private boolean requireTimingClosure = true;
        private String apiKeyHeader = "x-bench-key";

        private Builder() {}

        private Builder(BenchSettings from) {
            databaseUrl = from.databaseUrl;
            artifactRoot = from.artifactRoot;
            journalRoot = from.journalRoot;
            wearLedger = from.wearLedger;
            site = from.site;
            defaultLeaseMinutes = from.defaultLeaseMinutes;
            maxLeaseMinutes = from.maxLeaseMinutes;
            leaseGraceSeconds = from.leaseGraceSeconds;
            programAttempts = from.programAttempts;
            programBackoffSeconds = from.programBackoffSeconds;
            verifyAfterProgram = from.verifyAfterProgram;
            thermalPollSeconds = from.thermalPollSeconds;
            thermalStaleSeconds = from.thermalStaleSeconds;
            adapterClockCeilingHz = from.adapterClockCeilingHz;
            staffedHours = from.staffedHours;
            utcOffsetMinutes = from.utcOffsetMinutes;
            requireTimingClosure = from.requireTimingClosure;
            apiKeyHeader = from.apiKeyHeader;
        }

        public Builder databaseUrl(String value) {
            databaseUrl = value;
            return this;
        }

        public Builder artifactRoot(Path value) {
            artifactRoot = value;
            return this;
        }

        public Builder journalRoot(Path value) {
            journalRoot = value;
            return this;
        }

        public Builder wearLedger(Path value) {
            wearLedger = value;
            return this;
        }

        public Builder site(String value) {
            site = value;
            return this;
        }

        public Builder defaultLeaseMinutes(int value) {
            defaultLeaseMinutes = value;
            return this;
        }

        public Builder maxLeaseMinutes(int value) {
            maxLeaseMinutes = value;
            return this;
        }

        public Builder leaseGraceSeconds(int value) {
            leaseGraceSeconds = value;
            return this;
        }

        public Builder programAttempts(int value) {
            programAttempts = value;
            return this;
        }

        public Builder programBackoffSeconds(int value) {
            programBackoffSeconds = value;
            return this;
        }

        public Builder verifyAfterProgram(boolean value) {
            verifyAfterProgram = value;
            return this;
        }

        public Builder thermalPollSeconds(int value) {
            thermalPollSeconds = value;
            return this;
        }

        public Builder thermalStaleSeconds(int value) {
            thermalStaleSeconds = value;
            return this;
        }

        public Builder adapterClockCeilingHz(int value) {
            adapterClockCeilingHz = value;
            return this;
        }

        public Builder staffedHours(String value) {
            staffedHours = value;
            return this;
        }

        public Builder utcOffsetMinutes(int value) {
            utcOffsetMinutes = value;
            return this;
        }

        public Builder requireTimingClosure(boolean value) {
            requireTimingClosure = value;
            return this;
        }

        public Builder apiKeyHeader(String value) {
            apiKeyHeader = value;
            return this;
        }

        /** The settings, checked. */
        public BenchSettings build() {
            return new BenchSettings(
                    databaseUrl,
                    artifactRoot,
                    journalRoot,
                    wearLedger,
                    site,
                    defaultLeaseMinutes,
                    maxLeaseMinutes,
                    leaseGraceSeconds,
                    programAttempts,
                    programBackoffSeconds,
                    verifyAfterProgram,
                    thermalPollSeconds,
                    thermalStaleSeconds,
                    adapterClockCeilingHz,
                    staffedHours,
                    utcOffsetMinutes,
                    requireTimingClosure,
                    apiKeyHeader);
        }
    }
}
