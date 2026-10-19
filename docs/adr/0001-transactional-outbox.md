# ADR 0001: Publish messages through a transactional outbox

**Status:** accepted

## Context

Every state change in RelayForge is followed by a RabbitMQ message: a new notification must be queued, a campaign
send triggers a dispatch, a failed delivery schedules a retry or a dead letter. Originally the code saved to
PostgreSQL and then called `RabbitTemplate.convertAndSend`. That is a dual write with three failure modes we
observed or could reproduce:

- the process dies between commit and publish → the notification stays `QUEUED` forever;
- the broker is down → the API had to either fail the request or mark the notification `FAILED`;
- the campaign fan-out published inside its database transaction → workers sometimes received an id whose row
  was not committed yet and skipped it.

## Decision

State changes and the messages they cause are written in **one PostgreSQL transaction**: the message goes into
the `outbox_events` table (`OutboxService.enqueue`, `Propagation.MANDATORY` so it cannot run outside the caller's
transaction). `OutboxRelay` polls pending rows every 100 ms with
`SELECT … FOR UPDATE SKIP LOCKED`, publishes the batch with **publisher confirms**, and marks rows `SENT` only after
the broker confirmed them. Failed batches stay `PENDING` with exponential backoff (1 s … 60 s). Published rows are
deleted after 24 h.

## Consequences

- The API keeps accepting requests during a broker outage; messages wait in PostgreSQL and are delivered when
  the broker returns (covered by `ReliabilityIntegrationTest`).
- Publication is **at-least-once**: a crash after the broker confirm but before the commit republishes the batch.
  Consumers must be idempotent (see ADR 0003).
- Up to one poll interval (default 100 ms) of extra latency between commit and publish.
- PostgreSQL carries extra write load (one outbox row per message). `SKIP LOCKED` lets several app instances
  relay concurrently; ordering across instances is not guaranteed (RelayForge does not need it).
- Outbox backlog is visible as metrics (`outbox_events_pending`, `outbox_oldest_pending_age_seconds`) and as the
  `outbox` health component, which turns DOWN when the oldest pending event is older than 5 minutes.
