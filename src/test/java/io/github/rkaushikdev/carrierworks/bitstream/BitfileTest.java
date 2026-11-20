package io.github.rkaushikdev.carrierworks.bitstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BitfileTest {

    private static final byte[] BODY = body();

    private static byte[] body() {
        byte[] body = new byte[256 * 4];
        for (int index = 0; index < body.length; index++) {
            body[index] = (byte) index;
        }
        return body;
    }

    /** The fields a sample file is built from, each one changeable. */
    private static final class Sample {

        private String design = "radar_frontend;UserID=0XFFFFFFFF;Version=2024.1";
        private String part = "7a100tfgg484";
        private String date = "2025/04/21";
        private String time = "14:02:19";
        private byte[] data = BODY;

        Sample design(String value) {
            design = value;
            return this;
        }

        Sample part(String value) {
            part = value;
            return this;
        }

        Sample date(String value) {
            date = value;
            return this;
        }

        Sample time(String value) {
            time = value;
            return this;
        }

        Sample data(byte[] value) {
            data = value;
            return this;
        }

        byte[] build() {
            return BitFile.build(design, part, date, time, data);
        }
    }

    private static Sample sample() {
        return new Sample();
    }

    private static int indexOf(byte[] haystack, String needle, int from) {
        byte[] wanted = needle.getBytes(StandardCharsets.US_ASCII);
        for (int index = from; index + wanted.length <= haystack.length; index++) {
            if (Arrays.equals(haystack, index, index + wanted.length, wanted, 0, wanted.length)) {
                return index;
            }
        }
        throw new AssertionError(needle + " is not in the file");
    }

    @Test
    void aBuiltFileParsesBack() {
        BitFile parsed = BitFile.parse(sample().build());
        assertThat(parsed.designName()).isEqualTo("radar_frontend");
        assertThat(parsed.part()).isEqualTo("7a100tfgg484");
        assertThat(parsed.date()).isEqualTo("2025/04/21");
        assertThat(parsed.time()).isEqualTo("14:02:19");
        assertThat(parsed.data()).isEqualTo(BODY);
        assertThat(parsed.length()).isEqualTo(BODY.length);
    }

    @Test
    void theAttributesAfterTheNameAreRead() {
        BitFile parsed = BitFile.parse(sample().build());
        assertThat(parsed.toolVersion()).contains("2024.1");
        assertThat(parsed.userId()).contains(0xFFFF_FFFFL);
        assertThat(parsed.attributes()).containsEntry("userid", "0XFFFFFFFF");
    }

    @Test
    void aDesignWithoutAttributesStillWorks() {
        BitFile parsed = BitFile.parse(sample().design("beamformer").build());
        assertThat(parsed.designName()).isEqualTo("beamformer");
        assertThat(parsed.attributes()).isEmpty();
        assertThat(parsed.toolVersion()).isEmpty();
        assertThat(parsed.userId()).isEmpty();
    }

    @Test
    void aUserIdThatIsNotHexIsReportedAsMissing() {
        BitFile parsed = BitFile.parse(sample().design("top;UserID=none").build());
        assertThat(parsed.userId()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "7a100tfgg484, xc7a100t, fgg484",
        "7a35tcpg236, xc7a35t, cpg236",
        "7k325tffg900, xc7k325t, ffg900",
        "7s25csga324, xc7s25, csga324",
        "7z020clg484, xc7z020, clg484",
        "xc7a200tfbg676, xc7a200t, fbg676",
        "ku040ffva1156, xcku040, ffva1156"
    })
    void theDeviceIsPulledOutOfThePartField(String part, String device, String packageCode) {
        BitFile parsed = BitFile.parse(sample().part(part).build());
        assertThat(parsed.device()).isEqualTo(device);
        assertThat(parsed.packageCode()).isEqualTo(packageCode);
    }

    @Test
    void aPartFieldThatMakesNoSenseIsRefused() {
        BitFile parsed = BitFile.parse(sample().part("whatever").build());
        assertThatThrownBy(parsed::device).isInstanceOf(ValidationError.class);
    }

    @Test
    void aFileThatIsNotABitFileIsRefused() {
        assertThatThrownBy(() -> BitFile.parse(new byte[64])).isInstanceOf(ValidationError.class);
    }

    @Test
    void aFileThatIsTooShortIsRefused() {
        assertThatThrownBy(() -> BitFile.parse(new byte[] {0x00, 0x09})).isInstanceOf(ValidationError.class);
    }

    @Test
    void aTruncatedHeaderIsRefused() {
        byte[] whole = sample().build();
        assertThatThrownBy(() -> BitFile.parse(Arrays.copyOf(whole, 20))).isInstanceOf(ValidationError.class);
    }

    @Test
    void aTruncatedBodyIsRefused() {
        byte[] whole = sample().build();
        ValidationError caught = catchThrowableOfType(
                ValidationError.class, () -> BitFile.parse(Arrays.copyOf(whole, whole.length - 10)));
        assertThat(caught.details()).containsEntry("present", BODY.length - 10);
    }

    @Test
    void fieldsOutOfOrderAreRefused() {
        byte[] whole = sample().build();
        // the b field tag sits right after the a field
        whole[indexOf(whole, "b", 13)] = 'c';
        assertThatThrownBy(() -> BitFile.parse(whole)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aStringFieldWithoutItsTerminatorIsRefused() {
        byte[] whole = sample().design("top").build();
        whole[indexOf(whole, "top", 0) + 3] = '!';
        assertThatThrownBy(() -> BitFile.parse(whole)).isInstanceOf(ValidationError.class);
    }

    @Test
    void anEmptyImageIsStillAValidFile() {
        BitFile parsed = BitFile.parse(sample().data(new byte[0]).build());
        assertThat(parsed.length()).isZero();
        assertThat(parsed.data()).isEmpty();
    }

    @Test
    void aFieldLongerThanTheLengthWordIsRefused() {
        assertThatThrownBy(
                        () -> BitFile.build("x".repeat(70000), "7a100tfgg484", "2025/04/21", "14:02:19", new byte[0]))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void theHeaderIsTheLengthEveryoneExpects() {
        byte[] whole = sample().design("a")
                .part("7a100tfgg484")
                .date("d")
                .time("t")
                .data(new byte[0])
                .build();
        // thirteen bytes of preamble, four tagged strings, then the data field
        assertThat(whole).hasSize(13 + (1 + 2 + 2) + (1 + 2 + 13) + (1 + 2 + 2) * 2 + 5);
    }
}
