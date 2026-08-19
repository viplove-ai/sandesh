package in.sandesh;

import in.sandesh.media.StorageProperties;
import in.sandesh.notify.VapidProperties;
import in.sandesh.security.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sandesh — the messenger that sits beside Nirman.
 *
 * <p>It authenticates nobody: Nirman issues the tokens and this service verifies them with the
 * same secret. It stores no chat history: an undelivered message waits in {@code outbox} and is
 * deleted the moment the recipient's device says it has it.</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({JwtProperties.class, StorageProperties.class,
        VapidProperties.class})
public class SandeshApplication {
    public static void main(String[] args) {
        SpringApplication.run(SandeshApplication.class, args);
    }
}
