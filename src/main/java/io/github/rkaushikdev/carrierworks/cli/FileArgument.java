package io.github.rkaushikdev.carrierworks.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Paths to files, checked as the command line is read.
 *
 * <p>A path that cannot be what the command needs is a usage error rather than a bench error: the
 * command never starts, and the message names the path as it was typed.
 */
final class FileArgument {

    private FileArgument() {}

    /** A file that has to be there already: not missing, and not a directory. */
    static final class Existing implements ITypeConverter<Path> {

        @Override
        public Path convert(String value) {
            Path path = Path.of(value);
            if (!Files.exists(path)) {
                throw new TypeConversionException("File '" + value + "' does not exist.");
            }
            return notADirectory(value, path);
        }
    }

    /** A file that may or may not be there yet, as long as it is not a directory. */
    static final class Target implements ITypeConverter<Path> {

        @Override
        public Path convert(String value) {
            return notADirectory(value, Path.of(value));
        }
    }

    private static Path notADirectory(String value, Path path) {
        if (Files.isDirectory(path)) {
            throw new TypeConversionException("File '" + value + "' is a directory.");
        }
        return path;
    }
}
