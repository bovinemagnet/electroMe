package io.github.bovinemagnet.electrome.market.cdr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CdrCacheTest {

    @Test
    void readsBackWhatItWrote(@TempDir Path temp) {
        var cache = new CdrCache(temp);
        cache.write("OR1@VEC", "{\"a\":1}");
        assertThat(cache.read("OR1@VEC")).contains("{\"a\":1}");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void missingKeyReadsEmpty(@TempDir Path temp) {
        assertThat(new CdrCache(temp).read("nothing")).isEmpty();
    }

    @Test
    void sanitisesKeysContainingPathCharacters(@TempDir Path temp) throws IOException {
        // Plan ids carry an @ and could in principle carry a separator. A key must never be
        // able to write outside the cache directory.
        var cache = new CdrCache(temp);
        cache.write("../escape/OR1@VEC", "{}");
        assertThat(cache.read("../escape/OR1@VEC")).isPresent();
        try (var entries = Files.walk(temp)) {
            assertThat(entries.filter(Files::isRegularFile).count()).isEqualTo(1);
        }
    }

    @Test
    void freshnessTracksLastUpdated(@TempDir Path temp) {
        var cache = new CdrCache(temp);
        cache.write("OR1@VEC", "{}");
        cache.recordLastUpdated("OR1@VEC", "2026-08-01T00:00:00Z");

        assertThat(cache.isFresh("OR1@VEC", "2026-08-01T00:00:00Z")).isTrue();
        assertThat(cache.isFresh("OR1@VEC", "2026-08-15T00:00:00Z")).isFalse();
        assertThat(cache.isFresh("never-seen", "2026-08-01T00:00:00Z")).isFalse();
        assertThat(cache.isFresh("OR1@VEC", null)).isFalse();
    }

    @Test
    void clearRemovesEverything(@TempDir Path temp) {
        var cache = new CdrCache(temp);
        cache.write("a", "{}");
        cache.write("b", "{}");
        cache.clear();
        assertThat(cache.read("a")).isEmpty();
        assertThat(cache.read("b")).isEmpty();
        assertThat(cache.size()).isZero();
    }
}
