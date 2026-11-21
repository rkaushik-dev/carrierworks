package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.api.BenchApplication;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code fpga serve}: the HTTP service, for the regression runners and the dashboard.
 *
 * <p>It runs over the same database and settings as every other command, says where it is
 * listening once it is, and runs until it is stopped: an interrupt, or the process being told to
 * end, which closes the service and lets the command return.
 */
@Command(name = "serve", description = "Run the HTTP service until it is stopped.")
final class ServeCommand extends BenchCommand {

    @Option(names = "--host", paramLabel = "TEXT", description = "the address to listen on")
    private String host = "127.0.0.1";

    @Option(names = "--port", paramLabel = "INTEGER", description = "the port to listen on, 0 for any free one")
    private int port = 8000;

    @Override
    int run(Context context) {
        CountDownLatch closed = new CountDownLatch(1);
        try (ConfigurableApplicationContext service = BenchApplication.run(
                context.database(),
                context.settings(),
                Clock.systemUTC(),
                List.of(),
                "--server.address=" + host,
                "--server.port=" + port)) {
            service.addApplicationListener((ApplicationListener<ContextClosedEvent>) event -> closed.countDown());
            int listening = ((ServletWebServerApplicationContext) service)
                    .getWebServer()
                    .getPort();
            context.echo("serving " + context.settings().site() + " on http://" + host + ":" + listening);
            context.flush();
            closed.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return OK;
    }
}
