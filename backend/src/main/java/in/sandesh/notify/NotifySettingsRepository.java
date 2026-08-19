package in.sandesh.notify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotifySettingsRepository extends JpaRepository<NotifySettings, UUID> {
}
