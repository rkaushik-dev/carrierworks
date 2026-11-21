package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Hangs the gate and the page reader on every route. */
@Component
class ApiWeb implements WebMvcConfigurer {

    private final Gate gate;

    ApiWeb(BenchDatabase database, BenchSettings settings, Clock clock) {
        this.gate = new Gate(database, settings, clock);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gate);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(gate);
        resolvers.add(new PageResolver());
    }
}
