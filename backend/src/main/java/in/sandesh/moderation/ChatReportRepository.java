package in.sandesh.moderation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChatReportRepository extends JpaRepository<ChatReport, UUID> {
}
