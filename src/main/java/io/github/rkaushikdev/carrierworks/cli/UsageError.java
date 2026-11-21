package io.github.rkaushikdev.carrierworks.cli;

/**
 * A command line that cannot be run as typed, found by the command rather than by the parser.
 *
 * <p>It is reported the way a mistyped option is, with the command's usage line, and exit status
 * {@value Main#USAGE}.
 */
final class UsageError extends RuntimeException {

    UsageError(String message) {
        super(message);
    }
}
