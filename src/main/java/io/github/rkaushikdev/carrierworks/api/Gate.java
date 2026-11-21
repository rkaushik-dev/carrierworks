package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.Access;
import io.github.rkaushikdev.carrierworks.ApiKey;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Security;
import io.github.rkaushikdev.carrierworks.util.Times;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Optional;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The door every route that needs a role goes through, and the key it hands to the route.
 *
 * <p>A bench with no keys issued is open. That sounds alarming written down and it is the only
 * behaviour that works: the service starts empty, somebody has to be able to issue the first key,
 * and every lab that runs this on a laptop for an afternoon would otherwise have to invent a
 * credential first. The moment a single key exists, everything asks for one.
 *
 * <p>It runs as an interceptor, before a single argument is read, so a caller who may not be here
 * is told so before anybody reads what they sent: a 401 or a 403 beats a 422. The key is checked
 * in a unit of work of its own, so its last use is recorded even when the route then fails.
 */
public final class Gate implements HandlerInterceptor, HandlerMethodArgumentResolver {

    /** The key a bench with no keys issued runs as. */
    public static final ApiKey OPEN_BENCH = new ApiKey("open", "open bench", Role.ADMIN);

    private static final String KEY = Gate.class.getName() + ".key";

    private final BenchDatabase database;
    private final BenchSettings settings;
    private final Clock clock;

    public Gate(BenchDatabase database, BenchSettings settings, Clock clock) {
        this.database = database;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            Requires needs = method.getMethodAnnotation(Requires.class);
            if (needs != null) {
                ApiKey key = Access.requireRole(currentKey(request), needs.value());
                request.setAttribute(KEY, key);
            }
        }
        return true;
    }

    /** Whoever is calling, or the open bench key when no keys have been issued. */
    ApiKey currentKey(HttpServletRequest request) {
        Optional<String> presented = Optional.ofNullable(request.getHeader(settings.apiKeyHeader()));
        return database.apply(unit ->
                unit.apiKeys().all().isEmpty() ? OPEN_BENCH : Security.authenticate(unit, presented, Times.now(clock)));
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == ApiKey.class;
    }

    @Override
    public ApiKey resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer container,
            NativeWebRequest request,
            WebDataBinderFactory binders) {
        Object key = request.getAttribute(KEY, NativeWebRequest.SCOPE_REQUEST);
        if (key instanceof ApiKey known) {
            return known;
        }
        throw new IllegalStateException(
                parameter.getExecutable().getName() + " asks for a key and has no @Requires to check one");
    }
}
