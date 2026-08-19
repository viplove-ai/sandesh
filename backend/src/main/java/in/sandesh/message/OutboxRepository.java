package in.sandesh.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    /**
     * What a reconnecting device missed, in the order it must replay them.
     *
     * <p>Ordered by {@code sentAt} then {@code msgId} — the same total order the client sorts by,
     * so {@code Last-Event-ID} resumes at exactly the right place rather than approximately.</p>
     */
    @Query("""
            SELECT o FROM OutboxEntry o
             WHERE o.recipientId = :recipientId
               AND (:afterSentAt IS NULL OR o.sentAt > :afterSentAt
                    OR (o.sentAt = :afterSentAt AND o.msgId > :afterMsgId))
             ORDER BY o.sentAt, o.msgId
            """)
    List<OutboxEntry> pendingFor(@Param("recipientId") UUID recipientId,
                                 @Param("afterSentAt") Instant afterSentAt,
                                 @Param("afterMsgId") UUID afterMsgId);

    @Modifying
    @Query("DELETE FROM OutboxEntry o WHERE o.recipientId = :recipientId AND o.msgId = :msgId")
    int ack(@Param("recipientId") UUID recipientId, @Param("msgId") UUID msgId);

    @Modifying
    @Query("DELETE FROM OutboxEntry o WHERE o.createdAt < :cutoff")
    int sweepOlderThan(@Param("cutoff") Instant cutoff);

    long countByRecipientId(UUID recipientId);
}
