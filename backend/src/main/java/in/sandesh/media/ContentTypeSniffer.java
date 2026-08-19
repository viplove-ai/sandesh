package in.sandesh.media;

import java.util.List;
import java.util.Optional;

/**
 * What a file actually is, as opposed to what the sender said it was.
 *
 * <p>A site engineer will eventually be sent a {@code .pdf} that is an HTML file, and the
 * declared content type is the sender's claim rather than a fact. This checks the first few
 * bytes against the type being asserted and refuses the mismatch — cheap, and it closes the
 * gap between "we only allow PDFs" and "we only allow things called PDFs".</p>
 *
 * <p>Not a virus scanner and not pretending to be one. It answers one question: do the magic
 * bytes agree with the label.</p>
 */
public final class ContentTypeSniffer {

    /** The prefix each accepted type must begin with. */
    private record Signature(String contentType, byte[] magic, int offset) {
    }

    private static final List<Signature> SIGNATURES = List.of(
            new Signature("image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 0),
            new Signature("image/png",
                    new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0),
            // RIFF....WEBP — the four bytes at offset 8 are what distinguish it from a WAV.
            new Signature("image/webp", new byte[]{'W', 'E', 'B', 'P'}, 8),
            new Signature("application/pdf", new byte[]{'%', 'P', 'D', 'F', '-'}, 0),
            // Every modern Office file is a zip. So is a jar, and so is a renamed archive — this
            // says "a zip container", which is as far as magic bytes can honestly go.
            new Signature("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    new byte[]{'P', 'K', 0x03, 0x04}, 0),
            new Signature("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    new byte[]{'P', 'K', 0x03, 0x04}, 0),
            new Signature("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    new byte[]{'P', 'K', 0x03, 0x04}, 0));

    /** How many bytes a caller needs to read off the front for {@link #matches} to be useful. */
    public static final int PROBE_BYTES = 16;

    private ContentTypeSniffer() {
    }

    /**
     * @return true when the head agrees with the declared type, or when the type is one we hold
     *         no signature for — this refuses a contradiction, it does not require proof
     */
    public static boolean matches(String declaredContentType, byte[] head) {
        Optional<Signature> signature = SIGNATURES.stream()
                .filter(s -> s.contentType().equals(declaredContentType))
                .findFirst();
        if (signature.isEmpty()) {
            return true;
        }
        Signature expected = signature.get();
        if (head == null || head.length < expected.offset() + expected.magic().length) {
            return false;
        }
        for (int i = 0; i < expected.magic().length; i++) {
            if (head[expected.offset() + i] != expected.magic()[i]) {
                return false;
            }
        }
        return true;
    }

    public static boolean isImage(String contentType) {
        return contentType != null && contentType.startsWith("image/");
    }
}
