package io.github.rkaushikdev.carrierworks;

/**
 * Where the bench draws its lines for one board, in millidegrees celsius.
 *
 * <p>The part worth reading twice is the hysteresis. A board sitting exactly on the warning
 * threshold will cross it hundreds of times a minute, and every crossing would be an alert, a log
 * line and possibly a preempted lease. So the state only climbs at the threshold and only falls
 * once the die is a set margin below it.
 *
 * @param warnMc where a person is told
 * @param criticalMc where the supervisor starts taking boards back
 * @param shutdownMc where the board is switched off
 * @param hysteresisMc how far below a threshold the die has to cool before the state falls
 */
public record ThermalLimits(int warnMc, int criticalMc, int shutdownMc, int hysteresisMc) {

    /** The margin a falling reading needs, unless a site says otherwise. */
    public static final int DEFAULT_HYSTERESIS_MC = 3000;

    public ThermalLimits {
        if (!(warnMc < criticalMc && criticalMc < shutdownMc)) {
            throw new ValidationError(
                    "thermal thresholds have to climb",
                    "thresholds",
                    Details.of("warn", warnMc, "critical", criticalMc, "shutdown", shutdownMc));
        }
        if (hysteresisMc <= 0 || hysteresisMc > 10_000) {
            throw new ValidationError("hysteresis is out of range", "hysteresis_mc");
        }
    }

    /** Thresholds with the usual three degree hysteresis. */
    public ThermalLimits(int warnMc, int criticalMc, int shutdownMc) {
        this(warnMc, criticalMc, shutdownMc, DEFAULT_HYSTERESIS_MC);
    }

    /**
     * Limits derived from the part's own maximum junction temperature.
     *
     * <p>Shutdown sits on the datasheet maximum because past it the part is out of specification
     * whatever the bench thinks. The two below it are margin the lab chose: ten degrees to warn,
     * four to escalate.
     */
    public static ThermalLimits fromJunctionRange(JunctionRange junction) {
        int top = junction.high() * 1000;
        return new ThermalLimits(top - 14_000, top - 4_000, top);
    }

    /** The same limits warning somewhere else. */
    public ThermalLimits withWarnMc(int warn) {
        return new ThermalLimits(warn, criticalMc, shutdownMc, hysteresisMc);
    }

    /** The state a cold board would go straight to at this temperature. */
    public ThermalState stateFor(int millidegrees) {
        if (millidegrees >= shutdownMc) {
            return ThermalState.SHUTDOWN;
        }
        if (millidegrees >= criticalMc) {
            return ThermalState.CRITICAL;
        }
        if (millidegrees >= warnMc) {
            return ThermalState.WARN;
        }
        return ThermalState.NORMAL;
    }

    /** The threshold a board crossed to enter this state. */
    public int thresholdFor(ThermalState state) {
        return switch (state) {
            case SHUTDOWN -> shutdownMc;
            case CRITICAL -> criticalMc;
            case WARN -> warnMc;
            case NORMAL, UNKNOWN ->
                throw new ValidationError("no threshold for that state", "state", Details.of("given", state.value()));
        };
    }

    /**
     * Where the board goes next, with the hysteresis applied on the way down.
     *
     * <p>Climbing is immediate because a hot part does not get the benefit of the doubt. Falling
     * needs the die to be a whole hysteresis band below the threshold it crossed, so an alert does
     * not chatter.
     */
    public ThermalState nextState(ThermalState current, int millidegrees) {
        int from = current.level();
        ThermalState raw = stateFor(millidegrees);
        if (raw.level() >= from) {
            return raw;
        }
        ThermalState settled = current;
        while (settled != ThermalState.NORMAL) {
            int threshold = thresholdFor(settled);
            if (millidegrees > threshold - hysteresisMc) {
                break;
            }
            settled = ThermalState.ESCALATION.get(settled.level() - 1);
        }
        return settled.level() >= raw.level() ? settled : raw;
    }
}
