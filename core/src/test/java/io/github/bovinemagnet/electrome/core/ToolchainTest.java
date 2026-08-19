package io.github.bovinemagnet.electrome.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolchainTest {

    @Test
    void runsOnJavaTwentyOneOrLater() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
