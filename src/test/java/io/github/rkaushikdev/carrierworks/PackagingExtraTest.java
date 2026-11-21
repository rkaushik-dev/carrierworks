package io.github.rkaushikdev.carrierworks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.cli.Main;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine.Command;

/**
 * What the build promises about the program it makes: what ships beside the classes, which version
 * it carries, and that the command line runs as a program of its own.
 *
 * <p>The program is started as a separate process on the class path the tests run on, which is the
 * only way to see what {@code main} really does with its exit status and its two output streams.
 */
class PackagingExtraTest {

    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    private static final Pattern PUBLIC_TYPE = Pattern.compile(
            "^public (?:(?:final|abstract|sealed|non-sealed) )*(?:class|interface|enum|record|@interface) (\\w+)");

    private static Finished help;

    /**
     * What a finished program left behind.
     *
     * @param code its exit status
     * @param out what it wrote to standard output
     * @param err what it wrote to standard error
     */
    record Finished(int code, String out, String err) {}

    /** Run the command line as its own program, from a directory, and wait for it. */
    private static Finished program(Path directory, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(
                List.of(JAVA.toString(), "-cp", System.getProperty("java.class.path"), Main.class.getName()));
        command.addAll(List.of(args));
        Path out = Files.createTempFile(directory, "out", ".txt");
        Path err = Files.createTempFile(directory, "err", ".txt");
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .start();
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("the program did not finish: " + command);
        }
        return new Finished(
                process.exitValue(),
                Files.readString(out, StandardCharsets.UTF_8),
                Files.readString(err, StandardCharsets.UTF_8));
    }

    /** {@code fpga --help}, run once and kept, since three tests read it. */
    private static synchronized Finished help(Path directory) throws IOException, InterruptedException {
        if (help == null) {
            help = program(directory, "--help");
        }
        return help;
    }

    /** The public top-level types in one source file that carry no class Javadoc. */
    static List<String> undocumented(String source) {
        List<String> lines = source.lines().toList();
        List<String> missing = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            Matcher declaration = PUBLIC_TYPE.matcher(lines.get(index));
            if (declaration.lookingAt() && !documented(lines, index)) {
                missing.add(declaration.group(1));
            }
        }
        return missing;
    }

    /** Whether the lines above a declaration, past its annotations, end a Javadoc comment. */
    private static boolean documented(List<String> lines, int declaration) {
        for (int index = declaration - 1; index >= 0; index--) {
            String line = lines.get(index);
            if (line.stripTrailing().endsWith("*/")) {
                return true;
            }
            boolean annotation = line.startsWith("@")
                    || line.startsWith(" ")
                    || line.startsWith("\t")
                    || line.startsWith(")")
                    || line.startsWith("}");
            if (!annotation) {
                return false;
            }
        }
        return false;
    }

    @Test
    void everyPublicTypeSaysWhatItIsFor() {
        assertThat(undocumented("package x;\n\npublic final class Bare {}\n")).containsExactly("Bare");
        assertThat(undocumented("package x;\n\n/** Said. */\n@Deprecated\n@SuppressWarnings({\n    \"x\"\n})\n"
                        + "public record Said(int n) {}\n"))
                .isEmpty();
        int types = 0;
        for (Path source : Checkout.javaFiles(Checkout.MAIN)) {
            String text = Checkout.read(source);
            types += (int) text.lines()
                    .filter(line -> PUBLIC_TYPE.matcher(line).lookingAt())
                    .count();
            assertThat(undocumented(text)).as(source.getFileName().toString()).isEmpty();
        }
        assertThat(types).as("public types read").isGreaterThan(200);
    }

    @Test
    void theMigrationsAndTheRuntimeSettingsShipWithTheClasses() throws IOException {
        Path resources = Checkout.ROOT.resolve("src/main/resources");
        List<String> migrations;
        try (Stream<Path> files = Files.list(resources.resolve("db/migration"))) {
            migrations = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
        assertThat(migrations).hasSizeGreaterThanOrEqualTo(6).allMatch(name -> name.matches("V\\d+__\\w+\\.sql"));
        List<String> shipped = new ArrayList<>(
                migrations.stream().map(name -> "db/migration/" + name).toList());
        shipped.addAll(List.of("logback.xml", "application.properties"));
        ClassLoader loader = Main.class.getClassLoader();
        for (String name : shipped) {
            assertThat(loader.getResource(name)).as(name).isNotNull();
            assertThat(loader.getResource(name).toString()).as(name).doesNotContain("test-classes");
        }
    }

    @Test
    void theBuildAndTheCodeCarryTheSameVersion() {
        assertThat(Checkout.pom().version()).isEqualTo(Carrierworks.VERSION);
    }

    @Test
    void theBenchCarriesAVersion() {
        assertThat(Carrierworks.VERSION).matches("\\d+\\.\\d+\\.\\d+");
    }

    @Test
    void theCommandLineRunsAsAProgram(@TempDir Path tmp) throws Exception {
        Finished finished = help(tmp);
        assertThat(finished.code()).isZero();
        assertThat(finished.out()).contains("boards");
    }

    @Test
    void theJarAndTheProgramAreTheSameCommand(@TempDir Path tmp) throws Exception {
        Checkout.Pom pom = Checkout.pom();
        assertThat(pom.property("main.class")).contains(Main.class.getName());
        assertThat(pom.plugin("spring-boot-maven-plugin").element().getTextContent())
                .contains("${main.class}");
        Finished finished = program(tmp, "version");
        assertThat(finished.code()).isZero();
        assertThat(finished.out()).contains(Carrierworks.VERSION);
    }

    @Test
    void everyCommandGroupIsReachableFromTheProgram(@TempDir Path tmp) throws Exception {
        Finished finished = help(tmp);
        for (String group : List.of("boards", "chain", "flash", "image", "job", "lease", "power", "queue")) {
            assertThat(finished.out()).as(group).contains(group);
        }
    }

    @Test
    void theGroupsRegisteredInProcessMatchTheOnesAdvertised(@TempDir Path tmp) throws Exception {
        Class<?>[] registered = Main.class.getAnnotation(Command.class).subcommands();
        assertThat(registered).hasSizeGreaterThan(10);
        Finished finished = help(tmp);
        for (Class<?> command : registered) {
            String name = command.getAnnotation(Command.class).name();
            assertThat(finished.out()).as(name).containsPattern("(?m)^\\s+" + Pattern.quote(name) + "\\b");
        }
    }

    @Test
    void aCommandThatNeedsADatabaseFailsCleanlyNotWithAStackTrace(@TempDir Path tmp) throws Exception {
        Finished finished = program(tmp, "--database", "jdbc:h2:file:./nothing", "boards", "list");
        assertThat(finished.code()).isEqualTo(1);
        assertThat(finished.err()).doesNotContain("Exception in thread").doesNotContainPattern("(?m)^\\s+at ");
        assertThat(finished.err()).contains("no schema yet");
    }
}
