package io.github.rkaushikdev.carrierworks;

/**
 * The staffed window on one weekday, in minutes past local midnight.
 *
 * @param opens when somebody arrives, inclusive
 * @param closes when the last person leaves, exclusive
 */
public record ServiceHours(int opens, int closes) {

    static final int MINUTES_IN_DAY = 24 * 60;

    public ServiceHours {
        if (!(0 <= opens && opens < closes && closes <= MINUTES_IN_DAY)) {
            throw new ValidationError(
                    "service hours have to open before they close",
                    "hours",
                    Details.of("opens", opens, "closes", closes));
        }
    }

    /** From the form the rota is written in, such as {@code 08:00-18:30}. */
    public static ServiceHours parse(String text) {
        String cleaned = text.strip().replace(" ", "");
        int dash = cleaned.indexOf('-');
        if (dash < 0) {
            throw new ValidationError("service hours look like 08:00-18:00", "hours");
        }
        return new ServiceHours(minutes(cleaned.substring(0, dash)), minutes(cleaned.substring(dash + 1)));
    }

    /** Whether somebody is in at this minute of the local day. */
    public boolean covers(int minuteOfDay) {
        return opens <= minuteOfDay && minuteOfDay < closes;
    }

    private static int minutes(String text) {
        int colon = text.indexOf(':');
        String hours = colon < 0 ? "" : text.substring(0, colon);
        String minutes = colon < 0 ? "" : text.substring(colon + 1);
        if (colon < 0 || !digits(hours) || !digits(minutes)) {
            throw new ValidationError("a time looks like 08:00", "time", Details.of("given", text));
        }
        long total = Long.parseLong(hours) * 60 + Long.parseLong(minutes);
        if (total > MINUTES_IN_DAY || Long.parseLong(minutes) >= 60) {
            throw new ValidationError("a time is inside one day", "time", Details.of("given", text));
        }
        return (int) total;
    }

    private static boolean digits(String text) {
        return !text.isEmpty() && text.length() <= 15 && text.chars().allMatch(ch -> ch >= '0' && ch <= '9');
    }
}
