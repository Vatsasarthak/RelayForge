package com.vatsasarthak.notifyr.entity;

import org.junit.jupiter.api.Test;

import static com.vatsasarthak.notifyr.entity.NotificationStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationStatusTest {

    @Test
    void deliveryPathIsAllowed() {
        assertThat(QUEUED.canTransitionTo(PROCESSING)).isTrue();
        assertThat(PROCESSING.canTransitionTo(SENT)).isTrue();
        assertThat(PROCESSING.canTransitionTo(SIMULATED)).isTrue();
        assertThat(PROCESSING.canTransitionTo(QUEUED)).isTrue();    // retry scheduled
        assertThat(PROCESSING.canTransitionTo(FAILED)).isTrue();
        assertThat(FAILED.canTransitionTo(QUEUED)).isTrue();        // manual retry / replay
    }

    @Test
    void deliveredNotificationsAreFinal() {
        for (NotificationStatus next : values()) {
            assertThat(SENT.canTransitionTo(next)).isFalse();
            assertThat(SIMULATED.canTransitionTo(next)).isFalse();
        }
    }

    @Test
    void stepsCannotBeSkipped() {
        assertThat(QUEUED.canTransitionTo(SENT)).isFalse();
        assertThat(QUEUED.canTransitionTo(FAILED)).isFalse();
        assertThat(FAILED.canTransitionTo(SENT)).isFalse();
    }

    @Test
    void terminalStatuses() {
        assertThat(SENT.isTerminal()).isTrue();
        assertThat(SIMULATED.isTerminal()).isTrue();
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(QUEUED.isTerminal()).isFalse();
        assertThat(PROCESSING.isTerminal()).isFalse();
    }
}
