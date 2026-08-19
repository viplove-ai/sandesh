package in.sandesh.retention;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RetainedMessageRepository extends JpaRepository<RetainedMessage, UUID> {

    /**
     * What a re-issued handset gets back for a channel — bounded by the caller, who bounds it by
     * the person's own assignment dates. Somebody posted to a site in March and moved on in June
     * gets March to June, and not a day outside it.
     */
    @Query("""
            SELECT r FROM RetainedMessage r
             WHERE r.convId = :convId
               AND r.sentAt >= :from AND r.sentAt < :to
             ORDER BY r.sentAt, r.msgId
            """)
    List<RetainedMessage> window(@Param("convId") String convId, @Param("from") Instant from,
                                 @Param("to") Instant to);

    /**
     * The window enforced as a job. A retention period nothing deletes against is "permanent"
     * wearing a policy's clothes.
     */
    @Modifying
    @Query("DELETE FROM RetainedMessage r WHERE r.retainUntil < :now")
    int deleteExpired(@Param("now") Instant now);

    long countByConvId(String convId);
}
