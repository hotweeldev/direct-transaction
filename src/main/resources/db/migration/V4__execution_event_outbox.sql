-- Transactional outbox for event-driven execution (app.execution.mode=kafka).
--
-- In kafka mode a release that completes the workflow (or a single-user submit) no
-- longer runs execution in the request: the SAME workflow transaction sets the task
-- QUEUED and writes one row here, so "the task is queued" and "an event will be sent"
-- commit or roll back together - the event is never lost and never refers to a task
-- that was rolled back. A scheduled relay (ExecutionOutboxRelay) then publishes NEW
-- rows to Kafka and marks them SENT; the consumer claims the task QUEUED -> EXECUTING
-- under the usual optimistic VERSION guard, so a redelivered event no-ops exactly like
-- a concurrent double-execute does today.
--
-- STATUS: NEW -> SENT, or NEW -> FAILED after app.execution.outbox-max-attempts sends
-- all failed (alert-worthy: the task sits QUEUED until someone re-queues the row or
-- executes manually - the payload is kept in full for that).
--
-- V4 also widens TRX_TASK's STATUS vocabulary (V1 documented it, V3 widened it): the
-- full set is now
--   PENDING_APPROVAL / PENDING_RELEASE / REJECTED / QUEUED / READY_TO_EXECUTE /
--   EXECUTING / EXECUTED / FAILED / UNKNOWN
-- QUEUED is the kafka-mode sibling of READY_TO_EXECUTE: the workflow is complete and
-- execution has been handed to the event pipeline instead of the releasing request.
-- The EXECUTING claim accepts both, so a QUEUED task can also be executed directly
-- (e.g. a manual replay) without a status shuffle. No TRX_TASK DDL is needed - the
-- column is free text.
--
-- Same NVARCHAR2 convention as the other tables this service owns; IDs are minted in
-- Java (32-hex, dashless). No foreign key to TRX_TASK on purpose - same lesson as
-- direct-bankmodule's UMAS_PROVISIONING_TASK: the outbox row must never be the reason
-- a workflow transaction rolls back.

CREATE TABLE TRX_EVENT_OUTBOX (
    ID           NVARCHAR2(40)  NOT NULL,
    -- Only 'EXECUTION_REQUESTED' today; a column so the next event type is a row, not a table.
    EVENT_TYPE   NVARCHAR2(40)  NOT NULL,
    -- The TRX_TASK id the event is about - also the Kafka message key, so every event
    -- of one task lands on one partition, in order.
    AGGREGATE_ID NVARCHAR2(40)  NOT NULL,
    -- The event as it will be sent, verbatim: {"taskId":...,"refNo":...,"corpId":...}.
    PAYLOAD      CLOB           NOT NULL,
    STATUS       NVARCHAR2(20)  DEFAULT 'NEW' NOT NULL,
    ATTEMPTS     NUMBER(5)      DEFAULT 0 NOT NULL,
    LAST_ERROR   NVARCHAR2(1000),
    CREATED_DT   DATE           NOT NULL,
    SENT_DT      DATE,
    CONSTRAINT PK_TRX_EVENT_OUTBOX PRIMARY KEY (ID),
    CONSTRAINT CK_TRX_EVENT_OUTBOX_STATUS CHECK (STATUS IN ('NEW', 'SENT', 'FAILED'))
);

-- The relay's only query: oldest NEW first.
CREATE INDEX IX_TRX_EVENT_OUTBOX_STATUS ON TRX_EVENT_OUTBOX (STATUS, CREATED_DT);
