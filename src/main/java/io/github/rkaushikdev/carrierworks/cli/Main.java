package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.BenchError;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Carrierworks;
import java.io.PrintWriter;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;

/**
 * The {@code fpga} command line, which is how the lab actually uses the bench.
 *
 * <p>The web service exists for the regression runners and the dashboard. People use this, from a
 * terminal beside the rack, usually with one hand on a board. So every command prints something
 * readable by default and something parseable when asked, and no command asks a question it could
 * have answered itself.
 *
 * <p>Errors are printed, not thrown. A stack trace in front of somebody holding a ribbon cable is
 * noise; what they need is the one line the bench already wrote into the error, and a non zero
 * exit status so a script notices. Anything that is not a bench error still goes up, because that
 * is a defect.
 *
 * <p>Commands are listed by name in the help, and each command's options in the order it declares
 * them.
 *
 * <p>Exit statuses: {@value #SUCCESS} for success, {@value #FAILED} for a bench error, and {@value
 * #USAGE} for a command line that cannot be run as typed. A few commands also exit {@value #USAGE}
 * when what they found needs a person, which they say in their own help.
 */
@Command(
        name = "fpga",
        description = "The bench: boards, leases, images and jobs.",
        subcommands = {
            AuditCommand.class,
            BoardCommands.class,
            ImageCommands.class,
            LeaseCommands.class,
            Main.Version.class
        })
public final class Main {

    /** The exit status of a command that did what it was asked. */
    public static final int SUCCESS = 0;

    /** The exit status of a command the bench refused. */
    public static final int FAILED = 1;

    /** The exit status of a command line that cannot be run as typed. */
    public static final int USAGE = 2;

    @Option(names = "--json", description = "print machine readable output")
    private boolean asJson;

    @Option(names = "--database", paramLabel = "TEXT", description = "override the database url")
    private Optional<String> databaseUrl = Optional.empty();

    private Main() {}

    /** Run the command line against the process's own terminal, and exit with its status. */
    public static void main(String... args) {
        System.exit(run(Terminal.system(), args));
    }

    /**
     * Run one command line against a terminal.
     *
     * @return the exit status
     * @throws RuntimeException anything a command threw that is not a bench error, as it was thrown
     */
    public static int run(Terminal terminal, String... args) {
        Main main = new Main();
        CommandLine line = commandLine(main);
        try {
            return main.dispatch(line.parseArgs(args), terminal);
        } catch (ParameterException mistyped) {
            return usage(mistyped.getCommandLine(), mistyped.getMessage(), terminal);
        } finally {
            terminal.out().flush();
            terminal.err().flush();
        }
    }

    /** The command tree, set up to read a command line the way the bench documents it. */
    private static CommandLine commandLine(Main main) {
        CommandLine line = new CommandLine(main);
        line.setExpandAtFiles(false);
        line.setOverwrittenOptionsAllowed(true);
        prepare(line);
        return line;
    }

    /**
     * Every command gets {@code --help}, a usage line that does not list every option, and its
     * options listed in the order they are declared.
     */
    private static void prepare(CommandLine line) {
        CommandSpec spec = line.getCommandSpec();
        spec.usageMessage()
                .abbreviateSynopsis(true)
                .sortOptions(false)
                .descriptionHeading("%n")
                .parameterListHeading("%nArguments:%n")
                .optionListHeading("%nOptions:%n")
                .commandListHeading("%nCommands:%n");
        spec.addOption(OptionSpec.builder("--help")
                .usageHelp(true)
                .type(boolean.class)
                .description("Show this message and exit.")
                .build());
        line.getSubcommands().values().forEach(Main::prepare);
    }

    private int dispatch(ParseResult parsed, Terminal terminal) {
        ParseResult leaf = parsed;
        while (true) {
            if (leaf.isUsageHelpRequested()) {
                leaf.commandSpec().commandLine().usage(terminal.out());
                return SUCCESS;
            }
            if (!leaf.hasSubcommand()) {
                break;
            }
            leaf = leaf.subcommand();
        }
        CommandLine command = leaf.commandSpec().commandLine();
        if (!(command.getCommand() instanceof BenchCommand bench)) {
            command.usage(terminal.out());
            return SUCCESS;
        }
        try (Context context = new Context(settings(terminal), asJson, terminal)) {
            return bench.run(context);
        } catch (BenchError refused) {
            terminal.out().flush();
            terminal.err().print("Error: " + refused.getMessage() + "\n");
            return FAILED;
        } catch (UsageError mistyped) {
            return usage(command, mistyped.getMessage(), terminal);
        }
    }

    /** The settings from the terminal's environment, with the database the command line named. */
    private BenchSettings settings(Terminal terminal) {
        BenchSettings settings = BenchSettings.fromEnv(terminal.environment());
        Optional<String> url = databaseUrl.filter(given -> !given.isEmpty());
        return url.isPresent() ? settings.override(Map.of("database_url", url.get())) : settings;
    }

    /** The usage line, where to get help, and what was wrong, on the error stream. */
    private static int usage(CommandLine command, String message, Terminal terminal) {
        terminal.out().flush();
        Help help = command.getHelp();
        PrintWriter err = terminal.err();
        err.print(help.synopsisHeading() + help.synopsis(0));
        err.print("Try '" + command.getCommandSpec().qualifiedName() + " --help' for help.\n");
        err.print("\nError: " + message + "\n");
        return USAGE;
    }

    /** What this is and which site it thinks it belongs to. */
    @Command(name = "version", description = "What this is and which site it thinks it belongs to.")
    static final class Version extends BenchCommand {

        @Override
        int run(Context context) {
            String site = context.settings().site();
            context.say(
                    "fpga bench " + Carrierworks.VERSION + " at " + site,
                    fields("version", Carrierworks.VERSION, "site", site));
            return OK;
        }
    }
}
