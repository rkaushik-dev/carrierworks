package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code fpga serve}: the service comes up on the bench's database, answers, and stops when told. */
class CliServeTest {

    private static final Pattern LISTENING = Pattern.compile("serving (\\S+) on http://127\\.0\\.0\\.1:(\\d+)");

    @TempDir
    Path tmp;

    @Test
    void theServiceAnswersUntilItIsStopped() throws Exception {
        String url = Shell.url(tmp);
        try (BenchDatabase migrated = Shell.migrated(url)) {
            assertThat(migrated.settings().databaseUrl()).isEqualTo(url);
        }
        StringWriter printed = new StringWriter();
        PrintWriter writer = new PrintWriter(printed, true);
        Terminal terminal =
                new Terminal(Map.of("FPGA_SITE", "lab-s"), new BufferedReader(new StringReader("")), writer, writer);
        AtomicInteger status = new AtomicInteger(-1);
        Thread serving = new Thread(() -> status.set(Main.run(terminal, "--database", url, "serve", "--port", "0")));
        serving.start();

        Matcher listening = waitFor(printed);
        assertThat(listening.group(1)).isEqualTo("lab-s");
        HttpResponse<String> health = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listening.group(2) + "/health"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"ok\"").contains("\"site\":\"lab-s\"");

        serving.interrupt();
        serving.join(Duration.ofSeconds(30));
        assertThat(serving.isAlive()).isFalse();
        assertThat(status.get()).isZero();
    }

    /** The line saying where the service listens, once it has been printed. */
    private static Matcher waitFor(StringWriter printed) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(1));
        while (Instant.now().isBefore(deadline)) {
            Matcher listening = LISTENING.matcher(printed.toString());
            if (listening.find()) {
                return listening;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the service never said where it listens: " + printed);
    }
}
