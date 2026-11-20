package io.github.rkaushikdev.carrierworks;

/**
 * Bench control plane for the prototyping lab.
 *
 * <p>The version lives here rather than only in the build metadata, because the api reports it,
 * the bundles record it, and both of those need it at runtime from a checkout that was never
 * packaged. A test holds it to the version the build declares.
 */
public final class Carrierworks {

    /** The version of the bench software. */
    public static final String VERSION = "0.1.0";

    private Carrierworks() {}
}
