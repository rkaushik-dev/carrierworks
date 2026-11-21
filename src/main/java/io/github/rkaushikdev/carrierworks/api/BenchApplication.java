package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Carrierworks;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * The bench over HTTP: boards, leases, images, jobs, keys, the audit trail, build reports,
 * thermal readings, the waiting list and the roll up.
 *
 * <p>There is one factory, {@link #run}, rather than a context that builds its own database: the
 * command line opens the database before anything else happens, and tests start several
 * applications against several databases. The database, the settings and the clock are handed in
 * and become beans; they are the only shared things. Units of work are per request, and nothing
 * caches a domain object between requests.
 *
 * <p>Spring Boot's own data source, JPA and Flyway set up are switched off: with H2 on the class
 * path and no data source of its own, Boot would otherwise build an embedded database and migrate
 * that.
 */
@SpringBootApplication(
        exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
        })
public class BenchApplication {

    /** The service's name, as the OpenAPI document gives it. */
    public static final String TITLE = "fpga bench";

    /** One line on what it is. */
    public static final String SUMMARY = "The board farm the prototyping lab runs on";

    /** Start the service on the real clock, over a database somebody else opened and will close. */
    public static ConfigurableApplicationContext run(BenchDatabase database, BenchSettings settings, String... args) {
        return run(database, settings, Clock.systemUTC(), List.of(), args);
    }

    /**
     * Start the service on a given clock, with more configuration beside the application's own:
     * the routes a test adds, for instance.
     */
    public static ConfigurableApplicationContext run(
            BenchDatabase database, BenchSettings settings, Clock clock, List<Class<?>> extra, String... args) {
        List<Class<?>> sources = new ArrayList<>();
        sources.add(BenchApplication.class);
        sources.addAll(extra);
        return new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
                .initializers(context -> {
                    context.getBeanFactory().registerSingleton("benchSettings", settings);
                    context.getBeanFactory().registerSingleton("benchDatabase", database);
                    context.getBeanFactory().registerSingleton("benchClock", clock);
                })
                .build()
                .run(args);
    }

    @Bean
    Work work(BenchDatabase database) {
        return new Work(database);
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer benchJson() {
        return ApiJson::configure;
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info().title(TITLE).summary(SUMMARY).version(Carrierworks.VERSION));
    }
}
