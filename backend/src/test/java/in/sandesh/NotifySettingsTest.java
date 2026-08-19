package in.sandesh;

import in.sandesh.notify.NotifySettings;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotifySettingsTest {

    private NotifySettings settings() {
        return new NotifySettings(UUID.randomUUID());
    }

    @Test
    void withNoQuietHoursNothingIsEverQuiet() {
        assertThat(settings().isQuietAt(LocalTime.of(3, 0))).isFalse();
    }

    @Test
    void aQuietWindowThatWrapsMidnightIsTwoRangesAndNotOne() {
        // 22:00 to 07:00 is the shape almost everybody picks. Comparing it as a single interval
        // silently means "never", which is the bug this test exists to catch.
        NotifySettings s = settings();
        s.update(true, LocalTime.of(22, 0), LocalTime.of(7, 0), Set.of());

        assertThat(s.isQuietAt(LocalTime.of(23, 30))).isTrue();
        assertThat(s.isQuietAt(LocalTime.of(2, 0))).isTrue();
        assertThat(s.isQuietAt(LocalTime.of(6, 59))).isTrue();
        assertThat(s.isQuietAt(LocalTime.of(7, 0))).isFalse();
        assertThat(s.isQuietAt(LocalTime.of(13, 0))).isFalse();
    }

    @Test
    void aWindowInsideOneDayBehavesTheObviousWay() {
        NotifySettings s = settings();
        s.update(true, LocalTime.of(13, 0), LocalTime.of(14, 0), Set.of());

        assertThat(s.isQuietAt(LocalTime.of(13, 30))).isTrue();
        assertThat(s.isQuietAt(LocalTime.of(12, 59))).isFalse();
        assertThat(s.isQuietAt(LocalTime.of(14, 0))).isFalse();
    }

    @Test
    void aMutedConversationIsRememberedAcrossAnUpdate() {
        NotifySettings s = settings();
        s.update(false, null, null, Set.of("site:abc", "dm:x:y"));

        assertThat(s.isMuted("site:abc")).isTrue();
        assertThat(s.isMuted("site:other")).isFalse();
        assertThat(s.isPreviewsEnabled()).isFalse();
        assertThat(s.getMutedConvIds()).containsExactlyInAnyOrder("site:abc", "dm:x:y");
    }
}
