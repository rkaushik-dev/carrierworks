package io.github.rkaushikdev.carrierworks.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.bitstream.Bitstream;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RegistryTest {

    private static final String DIGEST = "ab".repeat(32);
    private static final long ARTIX = 0x03631093L;

    @TempDir
    Path tmp;

    /** An Artix image of the radar front end, read from a file named for its design. */
    private static final class Sample {

        private Images.Image image =
                Images.image().part(Images.ARTIX_PART).idcode(ARTIX).frames(8);
        private String design = "radar_frontend";

        Sample design(String value) {
            design = value;
            image = image.design(value);
            return this;
        }

        Sample start(boolean value) {
            image = image.start(value);
            return this;
        }

        Sample frames(int value) {
            image = image.frames(value);
            return this;
        }

        Bitstream read() {
            return image.bitstream(design + ".bit");
        }
    }

    private static Sample sample() {
        return new Sample();
    }

    private static Bitstream image() {
        return sample().read();
    }

    private ArtifactStore store() {
        return new ArtifactStore(tmp.resolve("artifacts"));
    }

    @Test
    void storingAnImageGivesARecord() {
        ArtifactRecord record = store().put(image());
        assertThat(record.design()).isEqualTo("radar_frontend");
        assertThat(record.device()).isEqualTo("xc7a100t");
        assertThat(record.kind()).isEqualTo(ArtifactKind.FULL);
        assertThat(record.channel()).isEqualTo("dev");
        assertThat(record.toolVersion()).isEqualTo("2024.1");
        assertThat(record.source()).isEqualTo("radar_frontend.bit");
        assertThat(record.storedAt()).isNotEmpty();
    }

    @Test
    void theBytesThatComeBackAreTheConfigurationData() {
        ArtifactStore keeper = store();
        Bitstream live = image();
        ArtifactRecord record = keeper.put(live);
        assertThat(keeper.read(record.digest())).isEqualTo(live.header().data());
    }

    @Test
    void storingTheSameImageTwiceIsOneEntry() {
        ArtifactStore keeper = store();
        ArtifactRecord first = keeper.put(image());
        ArtifactRecord second = keeper.put(image());
        assertThat(first.digest()).isEqualTo(second.digest());
        assertThat(keeper.records()).hasSize(1);
    }

    @Test
    void twoDifferentImagesAreTwoEntries() {
        ArtifactStore keeper = store();
        keeper.put(image());
        keeper.put(sample().frames(9).read());
        assertThat(keeper.records()).hasSize(2);
    }

    @Test
    void aPartialImageIsRecordedAsOne() {
        ArtifactRecord record = store().put(sample().start(false).read());
        assertThat(record.kind()).isEqualTo(ArtifactKind.PARTIAL);
        assertThat(record.isPartial()).isTrue();
    }

    @Test
    void aMissingArtifactIsANotFound() {
        ArtifactStore keeper = store();
        assertThatThrownBy(() -> keeper.read(DIGEST)).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> keeper.recordFor(DIGEST)).isInstanceOf(NotFound.class);
        assertThat(keeper.has(DIGEST)).isFalse();
    }

    @Test
    void recordsComeBackByDesign() {
        ArtifactStore keeper = store();
        keeper.put(sample().design("radar_frontend").read());
        keeper.put(sample().design("beamformer").frames(9).read());
        assertThat(keeper.forDesign("Radar Frontend"))
                .extracting(ArtifactRecord::design)
                .containsExactly("radar_frontend");
        assertThat(keeper.forDesign("nothing_here")).isEmpty();
    }

    @Test
    void aRecordCanBeMovedToAnotherChannel() {
        ArtifactStore keeper = store();
        ArtifactRecord record = keeper.put(image());
        keeper.update(record.withChannel("qual"));
        assertThat(keeper.recordFor(record.digest()).channel()).isEqualTo("qual");
    }

    @Test
    void updatingSomethingThatIsNotStoredIsANotFound() {
        ArtifactRecord record = ArtifactRecord.of(image());
        assertThatThrownBy(() -> store().update(record)).isInstanceOf(NotFound.class);
    }

    @Test
    void tagsAreSortedAndDeduplicated() {
        ArtifactRecord record = ArtifactRecord.of(image()).tagged("Nightly", "nightly", "keep");
        assertThat(record.tags()).containsExactly("keep", "nightly");
    }

    @Test
    void aRecordRoundTripsThroughJson() {
        ArtifactRecord record = ArtifactRecord.of(image()).tagged("keep");
        assertThat(ArtifactRecord.fromJson(record.toJson())).isEqualTo(record);
    }

    @Test
    void aRecordDescribesItself() {
        ArtifactRecord record = store().put(image());
        assertThat(record.describe()).startsWith("radar_frontend ");
        assertThat(record.describe()).contains("[dev]");
        assertThat(record.shortDigest()).isEqualTo(record.digest().substring(0, 12));
    }

    @ParameterizedTest
    @CsvSource({"digest, nope", "kind, sideways", "length, 0"})
    void aNonsenseRecordIsRefused(String field, String value) {
        assertThatThrownBy(() -> {
                    String digest = field.equals("digest") ? value : DIGEST;
                    String kind = field.equals("kind") ? value : "full";
                    long length = field.equals("length") ? Long.parseLong(value) : 100;
                    new ArtifactRecord(digest, "radar_frontend", "xc7a100t", length, ArtifactKind.parse(kind));
                })
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void removingAnArtifactTakesTheImageWithIt() {
        ArtifactStore keeper = store();
        ArtifactRecord record = keeper.put(image());
        keeper.remove(record.digest());
        assertThat(keeper.has(record.digest())).isFalse();
        assertThat(keeper.records()).isEmpty();
        assertThatThrownBy(() -> keeper.remove(record.digest())).isInstanceOf(NotFound.class);
    }

    @Test
    void theStoreSpreadsDigestsAcrossFolders() {
        ArtifactStore keeper = store();
        ArtifactRecord record = keeper.put(image());
        Path path = keeper.pathFor(record.digest());
        assertThat(path.getParent().getFileName()).hasToString(record.digest().substring(2));
        assertThat(path.getParent().getParent().getFileName())
                .hasToString(record.digest().substring(0, 2));
    }

    @Test
    void aDigestThatIsTooShortToBeOneIsRefused() {
        assertThatThrownBy(() -> store().has("ab")).isInstanceOf(ValidationError.class);
    }
}
