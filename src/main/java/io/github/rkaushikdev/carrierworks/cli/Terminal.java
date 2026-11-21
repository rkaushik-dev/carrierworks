package io.github.rkaushikdev.carrierworks.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * Everything the command line talks to: the environment it reads its settings from, the input it
 * reads a capture from when told to, and the two streams it answers on.
 *
 * <p>Commands never reach for the process's own streams or environment. A terminal is handed to
 * {@link Main#run}, so a test types at the command line exactly as a person would, with an
 * environment of its own and the output caught in writers.
 *
 * @param environment the variables the settings are read from
 * @param in what a command reads when it is told to read standard input
 * @param out where answers go
 * @param err where errors go
 */
public record Terminal(Map<String, String> environment, BufferedReader in, PrintWriter out, PrintWriter err) {

    public Terminal {
        environment = Map.copyOf(environment);
    }

    /** The process's own environment and streams. */
    public static Terminal system() {
        return new Terminal(
                System.getenv(),
                new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset())),
                new PrintWriter(System.out, true),
                new PrintWriter(System.err, true));
    }
}
