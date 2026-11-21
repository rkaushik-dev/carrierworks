package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.BenchError;
import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.Conflict;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.DeviceMismatch;
import io.github.rkaushikdev.carrierworks.Forbidden;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.TransientBenchError;
import io.github.rkaushikdev.carrierworks.Unauthorized;
import io.github.rkaushikdev.carrierworks.ValidationError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The one shape every error comes back in, and the status it comes back with.
 *
 * <p>Every failure the bench raises on purpose is a {@link BenchError} with a stable code, so what
 * is here is a mapping from those to a status and nothing else. A route never builds an error
 * response; it raises the same exception it would raise in the command line tool and this turns it
 * into {@code {"error": {code, message, retryable, details}}}.
 *
 * <p>The mapping is short and worth stating. Not found is a 404, a conflict is a 409 because
 * somebody else got there first, a validation failure is a 422 because the request was understood
 * and refused, a configuration error is a 500 because the bench is wrong rather than the caller,
 * and anything transient is a 503 with the retryable flag set and a {@code Retry-After} saying when
 * to come back. A request the framework itself cannot read is a validation failure in the same
 * shape.
 */
@RestControllerAdvice
public class Errors {

    // How long to tell a caller to wait, by what went wrong out in the lab.
    private static final Map<String, Integer> RETRY_SECONDS = Map.of("power", 30, "chain", 5, "transient", 10);
    private static final int DEFAULT_RETRY = 10;

    /**
     * How many seconds to tell a caller to wait, or nothing to not say.
     *
     * <p>Only transient errors get a number. Saying it for a conflict would be a lie, because
     * nothing about waiting makes a board somebody else is holding free, and a client that retries
     * on a 409 turns one mistake into a loop.
     */
    public static OptionalInt retryAfterFor(BenchError error) {
        if (!error.retryable()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(RETRY_SECONDS.getOrDefault(error.code(), DEFAULT_RETRY));
    }

    /** Which status an error comes back as. */
    public static int statusFor(BenchError error) {
        return switch (error) {
            case Unauthorized ignored -> 401;
            case Forbidden ignored -> 403;
            case NotFound ignored -> 404;
            case Conflict ignored -> 409;
            case DeviceMismatch ignored -> 422;
            case ValidationError ignored -> 422;
            case TransientBenchError ignored -> 503;
            case ConfigurationError ignored -> 500;
            default -> 400;
        };
    }

    /** The response for any bench error. */
    public static ResponseEntity<Map<String, Object>> respond(BenchError error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(statusFor(error));
        retryAfterFor(error).ifPresent(wait -> response.header(HttpHeaders.RETRY_AFTER, String.valueOf(wait)));
        return response.body(Map.of("error", error.asMap()));
    }

    @ExceptionHandler(BenchError.class)
    ResponseEntity<Map<String, Object>> benchError(BenchError error) {
        return respond(error);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> noSuchRoute(HttpServletRequest request) {
        return respond(new NotFound("route", request.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> wrongMethod(HttpRequestMethodNotSupportedException failure) {
        BenchError error = new BenchError(
                "that route does not answer " + failure.getMethod(), Details.of("method", failure.getMethod()));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("error", error.asMap()));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        HttpMediaTypeNotSupportedException.class,
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<Map<String, Object>> unreadable(Exception failure) {
        return respond(new ValidationError("the request does not hold up", Details.of("problems", problems(failure))));
    }

    private static List<Map<String, String>> problems(Exception failure) {
        List<Map<String, String>> problems = new ArrayList<>();
        switch (failure) {
            case MethodArgumentNotValidException invalid ->
                invalid.getBindingResult()
                        .getFieldErrors()
                        .forEach(field -> problems.add(problem(field.getField(), field.getDefaultMessage())));
            case HandlerMethodValidationException invalid ->
                invalid.getParameterValidationResults().forEach(result -> result.getResolvableErrors()
                        .forEach(error -> problems.add(problem(
                                Optional.ofNullable(result.getMethodParameter().getParameterName())
                                        .orElse("parameter"),
                                error.getDefaultMessage()))));
            case MissingServletRequestParameterException missing ->
                problems.add(problem(missing.getParameterName(), "a value is required"));
            case MethodArgumentTypeMismatchException mismatch ->
                problems.add(problem(mismatch.getName(), "that is not a usable value"));
            default -> problems.add(problem("body", firstLine(failure.getMessage())));
        }
        return problems;
    }

    private static Map<String, String> problem(String where, String why) {
        Map<String, String> problem = new LinkedHashMap<>();
        problem.put("where", where);
        problem.put("why", why == null ? "" : why);
        return problem;
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("");
    }
}
