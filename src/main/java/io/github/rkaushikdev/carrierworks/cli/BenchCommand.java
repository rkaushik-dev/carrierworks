package io.github.rkaushikdev.carrierworks.cli;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One command at the end of the command tree: the part that does the work and prints the answer.
 *
 * <p>Commands do not catch bench errors. {@link Main} turns one into a printed line and exit status
 * {@value Main#FAILED}, and lets anything that is not a bench error go up, because that is a
 * defect and hiding it helps nobody.
 */
abstract class BenchCommand {

    /** The command did what it was asked. */
    static final int OK = Main.SUCCESS;

    /** The command ran, and what it found needs a person. */
    static final int NEEDS_ATTENTION = 2;

    /**
     * Do the work and print the answer.
     *
     * @return the exit status, {@link #OK} unless the command says otherwise
     * @throws UsageError when the command line asks for something that cannot be done as typed
     */
    abstract int run(Context context);

    /**
     * Named values in the order given, for a JSON payload or a listing row. A value may be absent
     * ({@code null} or an empty {@code Optional}), and prints as {@code null}.
     *
     * @param namesAndValues a name, then its value, then the next name, and so on
     */
    static Map<String, Object> fields(Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("every name needs a value");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            fields.put((String) namesAndValues[index], namesAndValues[index + 1]);
        }
        return Collections.unmodifiableMap(fields);
    }
}
