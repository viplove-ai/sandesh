package in.sandesh.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface MessageIdempotencyRepository extends JpaRepository<MessageIdempotency, UUID> {

    @Modifying
    @Query("DELETE FROM MessageIdempotency m WHERE m.createdAt < :cutoff")
    int sweepOlderThan(@Param("cutoff") Instant cutoff);
}
