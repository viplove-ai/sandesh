package in.sandesh;

import in.sandesh.conversation.ConversationId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationIdOrgTest {

    @Test
    void theAnnouncementsChannelRoundTrips() {
        UUID orgId = UUID.randomUUID();
        ConversationId announcements = ConversationId.org(orgId);

        assertThat(announcements.toString()).isEqualTo("org:" + orgId);
        assertThat(ConversationId.parse("org:" + orgId)).isEqualTo(announcements);
        assertThat(announcements.kind()).isEqualTo(ConversationId.Kind.ORG);
    }
}
