package com.vatsasarthak.notifyr.entity;

import java.util.Set;

// Allowed transitions (also enforced by the conditional UPDATEs in NotificationRepository):
//
//   QUEUED ──claim──> PROCESSING ──> SENT | SIMULATED          (delivered)
//                         │     └──> QUEUED                    (transient failure, retry scheduled)
//                         │     └──> FAILED                    (permanent failure or retries exhausted)
//                         └──lease expired──> PROCESSING | QUEUED   (worker crashed; recovered)
//   FAILED ──manual retry──> QUEUED
public enum NotificationStatus {
    QUEUED,
    PROCESSING,
    SENT,
    // handled by a simulated channel (push, or email/SMS in mock-provider mode); nothing was delivered
    SIMULATED,
    FAILED;

    public boolean canTransitionTo(NotificationStatus next) {
        return switch (this) {
            case QUEUED -> next == PROCESSING;
            case PROCESSING -> Set.of(PROCESSING, SENT, SIMULATED, QUEUED, FAILED).contains(next);
            case FAILED -> next == QUEUED;
            case SENT, SIMULATED -> false;
        };
    }

    public boolean isTerminal() {
        return this == SENT || this == SIMULATED || this == FAILED;
    }
}
