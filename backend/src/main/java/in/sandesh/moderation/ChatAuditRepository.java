package in.sandesh.moderation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChatAuditRepository extends JpaRepository<ChatAudit, UUID> {

    Page<ChatAudit> findByOrgIdOrderByAtDesc(UUID orgId, Pageable pageable);
}
