package io.github.rkaushikdev.carrierworks.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DigestsTest {

    @Test
    void theDigestOfNothingIsTheWellKnownOne() {
        assertThat(Digests.sha256(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void textIsDigestedAsItsUtf8Bytes() {
        assertThat(Digests.sha256("café"))
                .isEqualTo(Digests.sha256("café".getBytes(StandardCharsets.UTF_8)))
                .hasSize(64)
                .isLowerCase();
    }
}
