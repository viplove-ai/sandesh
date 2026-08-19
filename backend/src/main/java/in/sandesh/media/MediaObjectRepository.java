package in.sandesh.media;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MediaObjectRepository extends JpaRepository<MediaObject, UUID> {
}
