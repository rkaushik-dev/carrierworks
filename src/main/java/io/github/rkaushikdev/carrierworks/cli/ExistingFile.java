package io.github.rkaushikdev.carrierworks.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * A path that has to name a file that is there, checked as the command line is read.
 *
 * <p>A missing file or a directory is a usage error rather than a bench error: the command never
 * starts, and the message names the path as it was typed.
 */
final class ExistingFile implements ITypeConverter<Path> {

    @Override
    public Path convert(String value) {
        Path path = Path.of(value);
        if (!Files.exists(path)) {
            throw new TypeConversionException("File '" + value + "' does not exist.");
        }
        if (Files.isDirectory(path)) {
            throw new TypeConversionException("File '" + value + "' is a directory.");
        }
        return path;
    }
}
