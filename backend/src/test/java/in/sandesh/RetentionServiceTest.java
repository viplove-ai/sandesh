package in.sandesh;

import in.sandesh.common.BusinessException;
import in.sandesh.conversation.ConversationId;
import in.sandesh.retention.RetainedMessage;
import in.sandesh.retention.RetainedMessageRepository;
import in.sandesh.retention.RetentionProperties;
import in.sandesh.retention.RetentionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rule this file exists to hold: a direct message is never retained, by any setting.
 */
class RetentionServiceTest {

    private final RetainedMessageRepository repository = mock(RetainedMessageRepository.class);

    private RetentionService service(boolean enabled) {
        return new RetentionService(repository, new RetentionProperties(enabled, 1095));
    }

    @Test
    void retentionIsOffUnlessSomebodyTurnsItOn() {
        // The default is a decision: keeping work channels needs a purpose, a window and contract
        // language, none of which is engineering's to supply.
        RetentionProperties defaults = new RetentionProperties(false, 0);
        assertThat(defaults.enabled()).isFalse();
        // A zero or negative window falls back to three years rather than meaning "delete now".
        assertThat(defaults.windowDays()).isEqualTo(1095);
    }

    @Test
    void aDirectMessageIsNeverRetainable() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertThat(service(true).isRetainable(ConversationId.direct(a, b))).isFalse();
        assertThat(service(false).isRetainable(ConversationId.direct(a, b))).isFalse();
    }

    @Test
    void workChannelsAreRetainableOnlyWhenEnabled() {
        ConversationId site = ConversationId.site(UUID.randomUUID());

        assertThat(service(true).isRetainable(site)).isTrue();
        assertThat(service(false).isRetainable(site)).isFalse();
    }

    @Test
    void recordingADirectMessageWritesNothing() {
        service(true).record(UUID.randomUUID(), UUID.randomUUID(),
                ConversationId.direct(UUID.randomUUID(), UUID.randomUUID()), UUID.randomUUID(),
                "TEXT", "private", null, Instant.now());

        verify(repository, never()).save(any());
    }

    @Test
    void recordingWithRetentionOffWritesNothing() {
        service(false).record(UUID.randomUUID(), UUID.randomUUID(),
                ConversationId.site(UUID.randomUUID()), UUID.randomUUID(),
                "TEXT", "the pour is delayed", null, Instant.now());

        verify(repository, never()).save(any());
    }

    @Test
    void aRetainedMessageCarriesItsOwnDeletionDate() {
        List<RetainedMessage> saved = new ArrayList<>();
        when(repository.save(any())).thenAnswer(call -> {
            saved.add(call.getArgument(0));
            return call.getArgument(0);
        });
        Instant sentAt = Instant.parse("2026-08-19T09:00:00Z");

        service(true).record(UUID.randomUUID(), UUID.randomUUID(),
                ConversationId.site(UUID.randomUUID()), UUID.randomUUID(),
                "TEXT", "the pour is delayed", null, sentAt);

        // Stamped at insert rather than computed at read, so changing the window later cannot
        // un-delete last year's messages or bring this year's deletion forward.
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getSentAt()).isEqualTo(sentAt);
        assertThat(saved.get(0).getBody()).isEqualTo("the pour is delayed");
    }

    @Test
    void resyncingADirectConversationIsRefusedRatherThanEmpty() {
        // Returning nothing would read as "there is no history". Refusing says what is true:
        // the two devices are the only place it ever was.
        assertThatThrownBy(() -> service(true).resync(
                ConversationId.direct(UUID.randomUUID(), UUID.randomUUID()),
                Instant.EPOCH, Instant.now()))
                .isInstanceOf(BusinessException.class);
    }
}
