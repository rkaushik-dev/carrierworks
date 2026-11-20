package io.github.rkaushikdev.carrierworks;

/**
 * One credential, without the secret in it.
 *
 * <p>The secret is never kept. A key is issued once, its digest is stored, and a lost key is
 * replaced rather than recovered.
 *
 * @param id the key's id
 * @param label what the key is for
 * @param role what it may do
 * @param team who it belongs to, or empty
 * @param active false once revoked
 */
public record ApiKey(String id, String label, Role role, String team, boolean active) {

    public ApiKey {
        if (label.isBlank()) {
            throw new ValidationError("a key says what it is for", "label");
        }
    }

    /** A live key belonging to no team. */
    public ApiKey(String id, String label, Role role) {
        this(id, label, role, "", true);
    }

    /** How much this key can do, least first. */
    public int rank() {
        return role.rank();
    }

    /** Whether this key carries at least the role asked for. */
    public boolean allows(Role needed) {
        return active && rank() >= needed.rank();
    }

    /** {@code nightly runner [operator]}, with a mark when it has been revoked. */
    public String describe() {
        String state = active ? "" : " (revoked)";
        return label + " [" + role.value() + "]" + state;
    }
}
