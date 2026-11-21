package io.github.rkaushikdev.carrierworks.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * How the service reads and writes JSON, in one place for the application and the tests.
 *
 * <p>Field names are snake case, timestamps are ISO 8601 in UTC, a missing value is {@code null},
 * and every state, mode and kind goes over the wire as the name the bench stores it under, which
 * is what each of those types prints as.
 *
 * <p>A request field with a default takes it when the field is left out. Sending such a field as
 * {@code null} is refused rather than read as "the default", because null is not one of its values.
 */
public final class ApiJson {

    private ApiJson() {}

    /** Apply the bench's JSON settings to a mapper being built. */
    public static void configure(Jackson2ObjectMapperBuilder builder) {
        builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .featuresToEnable(
                        SerializationFeature.WRITE_ENUMS_USING_TO_STRING,
                        DeserializationFeature.READ_ENUMS_USING_TO_STRING)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** A mapper configured the way the service's own is. */
    public static ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        configure(builder);
        return builder.build();
    }
}
