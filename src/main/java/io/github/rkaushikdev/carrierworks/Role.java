package io.github.rkaushikdev.carrierworks;

/**
 * What a key is allowed to do, ordered by consequence rather than by seniority.
 *
 * <p>A reader can look at anything, because the bench holds nothing secret. An operator can take
 * boards and program them, which is what the regression runners and everybody in the lab needs. An
 * admin can change what the bench believes: adding boards, promoting an image to golden, and
 * issuing keys. Each role covers everything below it.
 */
public enum Role {
    READER("reader"),
    OPERATOR("operator"),
    ADMIN("admin");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    /** The name the role is stored and asked for under. */
    public String value() {
        return value;
    }

    /** How much the role can do, least first. */
    public int rank() {
        return ordinal();
    }

    /** The role with this name, however it was written. */
    public static Role parse(String text) {
        String wanted = Ids.slug(text);
        for (Role role : values()) {
            if (role.value.equals(wanted)) {
                return role;
            }
        }
        throw new ValidationError("no such role", "role", Details.of("given", text));
    }

    @Override
    public String toString() {
        return value;
    }
}
