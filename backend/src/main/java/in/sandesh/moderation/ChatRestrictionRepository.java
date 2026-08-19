package in.sandesh.moderation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatRestrictionRepository extends JpaRepository<ChatRestriction, UUID> {

    List<ChatRestriction> findByOrgId(UUID orgId);
}
