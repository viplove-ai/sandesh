package in.sandesh;

import in.sandesh.media.ContentTypeSniffer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case this exists for: a site engineer is sent a ".pdf" that is an HTML file. The declared
 * content type is the sender's claim, and nothing else in the pipeline questions it.
 */
class ContentTypeSnifferTest {

    private static byte[] head(int... bytes) {
        byte[] out = new byte[ContentTypeSniffer.PROBE_BYTES];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = (byte) bytes[i];
        }
        return out;
    }

    @Test
    void acceptsAFileThatIsWhatItSaysItIs() {
        assertThat(ContentTypeSniffer.matches("application/pdf", head('%', 'P', 'D', 'F', '-')))
                .isTrue();
        assertThat(ContentTypeSniffer.matches("image/jpeg", head(0xFF, 0xD8, 0xFF, 0xE0))).isTrue();
        assertThat(ContentTypeSniffer.matches("image/png",
                head(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))).isTrue();
    }

    @Test
    void refusesHtmlWearingAPdfName() {
        byte[] html = head('<', '!', 'D', 'O', 'C', 'T', 'Y', 'P', 'E');
        assertThat(ContentTypeSniffer.matches("application/pdf", html)).isFalse();
    }

    @Test
    void refusesAJpegDeclaredAsAPng() {
        assertThat(ContentTypeSniffer.matches("image/png", head(0xFF, 0xD8, 0xFF))).isFalse();
    }

    @Test
    void readsWebpAtItsOffsetRatherThanTheStart() {
        // RIFF....WEBP — the four bytes at offset 8 are what separate it from a WAV, which
        // shares the RIFF container and would otherwise pass.
        byte[] webp = head('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P');
        byte[] wav = head('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E');

        assertThat(ContentTypeSniffer.matches("image/webp", webp)).isTrue();
        assertThat(ContentTypeSniffer.matches("image/webp", wav)).isFalse();
    }

    @Test
    void recognisesOfficeFilesAsTheZipContainersTheyAre() {
        byte[] zip = head('P', 'K', 0x03, 0x04);
        assertThat(ContentTypeSniffer.matches(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", zip))
                .isTrue();
        assertThat(ContentTypeSniffer.matches(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                head('n', 'o', 't', 'z', 'i', 'p'))).isFalse();
    }

    @Test
    void aTruncatedOrEmptyHeadIsARefusalAndNotAPass() {
        assertThat(ContentTypeSniffer.matches("application/pdf", new byte[]{'%', 'P'})).isFalse();
        assertThat(ContentTypeSniffer.matches("application/pdf", null)).isFalse();
    }

    @Test
    void aTypeWeHoldNoSignatureForIsNotRefused() {
        // This refuses a contradiction; it does not demand proof. An allow-list decides what may
        // be sent — this only decides whether the bytes agree with the label.
        assertThat(ContentTypeSniffer.matches("text/plain", head('h', 'e', 'l', 'l', 'o'))).isTrue();
    }
}
