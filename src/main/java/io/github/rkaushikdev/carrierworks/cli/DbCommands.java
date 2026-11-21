package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.persistence.SchemaUpgrade;
import picocli.CommandLine.Command;

/**
 * {@code fpga db}: the database's schema.
 *
 * <p>A fresh site starts with an empty database, and every other command refuses one until its
 * schema is built. {@code fpga db upgrade} builds it, and after the bench itself has been upgraded
 * it brings the schema up to the newest version. Running it again changes nothing, so a deploy
 * script can run it every time.
 */
@Command(name = "db", description = "The database's schema.", subcommands = DbCommands.Upgrade.class)
final class DbCommands {

    private DbCommands() {}

    /** Build the schema, or bring it up to the newest version. */
    @Command(name = "upgrade", description = "Build the schema, or bring it up to the newest version.")
    static final class Upgrade extends BenchCommand {

        @Override
        int run(Context context) {
            SchemaUpgrade upgrade = context.database().upgrade();
            context.say(
                    upgrade.describe(),
                    fields("from", upgrade.from(), "to", upgrade.to(), "applied", upgrade.applied()));
            return OK;
        }
    }
}
