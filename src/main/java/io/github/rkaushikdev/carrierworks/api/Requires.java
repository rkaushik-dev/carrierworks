package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.Role;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The role a route needs.
 *
 * <p>Roles are attached to routes rather than checked inside them, so what a route needs is
 * visible on it and a route that forgot to ask stands out.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Requires {

    /** The least role that may call the route. */
    Role value();
}
