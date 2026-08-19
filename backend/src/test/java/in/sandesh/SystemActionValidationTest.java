package in.sandesh;

import in.sandesh.common.BusinessException;
import in.sandesh.conversation.ConversationId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The system channel, and the shape of what may be put in it.
 *
 * <p>A card's buttons are paths on Nirman's API that a signed-in phone will execute with the
 * user's own token. The validation that keeps them paths — rather than URLs pointing at another
 * host — is the difference between a convenience and a token-exfiltration route.</p>
 */
class SystemActionValidationTest {

    @Test
    void theSystemChannelIsOnePerPersonAndRoundTrips() {
        UUID userId = UUID.randomUUID();
        ConversationId channel = ConversationId.system(userId);

        assertThat(channel.toString()).isEqualTo("sys:" + userId);
        assertThat(ConversationId.parse("sys:" + userId)).isEqualTo(channel);
        assertThat(channel.kind()).isEqualTo(ConversationId.Kind.SYSTEM);
    }

    @Test
    void aSystemChannelIsNotADirectConversation() {
        // They are both "one person's" channel and it would be easy to conflate them. A direct
        // conversation has two parties; this one has an audience of one and no sender.
        UUID userId = UUID.randomUUID();
        assertThat(ConversationId.system(userId))
                .isNotEqualTo(ConversationId.direct(userId, userId));
    }

    @Test
    void aMalformedSystemIdIsRefused() {
        assertThatThrownBy(() -> ConversationId.parse("sys:not-a-uuid"))
                .isInstanceOf(BusinessException.class);
    }
}
