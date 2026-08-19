package in.sandesh.system;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The shared secret Nirman presents when it posts a card.
 *
 * <p>Not a JWT: this is service-to-service and there is no user to represent. Blank disables the
 * endpoint entirely rather than accepting an empty token, which is the failure mode that matters
 * — a misconfigured deployment should refuse the call, not accept everybody's.</p>
 */
@ConfigurationProperties(prefix = "app.system")
public record SystemTokenProperties(String token) {

    public boolean configured() {
        return token != null && token.length() >= 32;
    }
}
