package in.sandesh.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Whether work channels are kept, and for how long.
 *
 * <p>Off by default, and that default is a decision rather than caution. Retention needs a
 * stated purpose, a stated window and language in the employment contract before the first
 * retained message exists — none of which is engineering's to supply. Shipping it enabled would
 * make the legal position a consequence of a deploy.</p>
 *
 * @param windowDays how long a Tier 2 message is kept. The default is three years; the real
 *                   number follows the contract's arbitration window, which is why it is
 *                   configuration and not a constant
 */
@ConfigurationProperties(prefix = "app.retention")
public record RetentionProperties(boolean enabled, int windowDays) {

    public RetentionProperties {
        if (windowDays <= 0) {
            windowDays = 365 * 3;
        }
    }

    public Duration window() {
        return Duration.ofDays(windowDays);
    }
}
