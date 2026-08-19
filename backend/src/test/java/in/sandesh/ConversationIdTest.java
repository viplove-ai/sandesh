package in.sandesh;

import in.sandesh.common.BusinessException;
import in.sandesh.conversation.ConversationId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationIdTest {

    @Test
    void directConversationIsTheSameWhicheverPartyNamesIt() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

        assertThat(ConversationId.direct(a, b)).isEqualTo(ConversationId.direct(b, a));
        assertThat(ConversationId.direct(b, a).toString()).isEqualTo("dm:" + a + ":" + b);
    }

    @Test
    void roundTripsThroughItsStringForm() {
        UUID site = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertThat(ConversationId.parse(ConversationId.site(site).toString()))
                .isEqualTo(ConversationId.site(site));
        assertThat(ConversationId.parse(ConversationId.project(project).toString()))
                .isEqualTo(ConversationId.project(project));
        assertThat(ConversationId.parse(ConversationId.direct(a, b).toString()))
                .isEqualTo(ConversationId.direct(a, b));
    }

    @Test
    void otherPartyIsWhicheverOneIsNotYou() {
        UUID me = UUID.randomUUID();
        UUID them = UUID.randomUUID();
        assertThat(ConversationId.direct(me, them).otherParty(me)).isEqualTo(them);
        assertThat(ConversationId.direct(me, them).otherParty(them)).isEqualTo(me);
    }

    @Test
    void rubbishIsRefusedAsABadRequestRatherThanCrashing() {
        assertThatThrownBy(() -> ConversationId.parse("site:not-a-uuid"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ConversationId.parse("wat:" + UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ConversationId.parse(""))
                .isInstanceOf(BusinessException.class);
    }
}
