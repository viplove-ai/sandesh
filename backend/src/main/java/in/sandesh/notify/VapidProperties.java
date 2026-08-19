package in.sandesh.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The application server's identity to the push services.
 *
 * <p>Blank keys switch push off rather than failing at startup. The pilot has to be able to run
 * before somebody has generated a key pair, and a service that will not boot without one is a
 * service nobody can try.</p>
 *
 * @param subject a mailto: or https: URL the push service can use to contact whoever operates
 *                this — required by the VAPID spec and by Apple's endpoint in particular
 */
@ConfigurationProperties(prefix = "app.push")
public record VapidProperties(String publicKey, String privateKey, String subject,
                              boolean enabled) {

    public boolean configured() {
        return enabled && publicKey != null && !publicKey.isBlank()
                && privateKey != null && !privateKey.isBlank();
    }
}
