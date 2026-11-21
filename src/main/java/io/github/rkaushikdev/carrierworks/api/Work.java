package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One unit of work per request: committed if the handler returns, rolled back if it throws.
 *
 * <p>The commit happens before the handler's answer is written, which is why this is a call around
 * the handler's body and not an interceptor: a response body is written before an interceptor's
 * {@code postHandle} runs, so a commit there that failed would follow a 200.
 */
public final class Work {

    private final BenchDatabase database;

    public Work(BenchDatabase database) {
        this.database = database;
    }

    /** Run the handler's body in a fresh unit of work and answer what it answers. */
    public <T> T apply(Function<? super UnitOfWork, ? extends T> handler) {
        return database.apply(handler);
    }

    /** Run the handler's body in a fresh unit of work, for a route with nothing to hand back. */
    public void run(Consumer<? super UnitOfWork> handler) {
        database.run(handler);
    }
}
