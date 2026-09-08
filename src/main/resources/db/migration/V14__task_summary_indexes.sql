-- Indexes for the task badge, GET /api/v1/companies/{companyId}/tasks/summary.
--
-- WHY THIS IS NOT "just another query". The badge is polled every 30 seconds by every
-- open browser tab of every corporate user - it is, by a wide margin, the most-executed
-- statement this service will ever run, and it runs whether or not anybody is looking at
-- the inbox. The counts themselves are tiny; what makes or breaks it is whether Oracle can
-- answer from indexes or has to touch TRX_TASK_CANDIDATE and TRX_TASK_ACTION rows.
--
-- The query (TrxTaskMapper.findTaskSummary, the same join as findInbox) drives from the
-- candidate: TRX_TASK_CANDIDATE by USER_ID, then TRX_TASK by primary key, then
-- TRX_TASK_STAGE by UK_TRX_TASK_STAGE_SEQ, then one NOT EXISTS probe per surviving row
-- against TRX_TASK_ACTION. Both of those last two steps are already served; the two ends
-- are not:
--
--   * V2's IX_TRX_TASK_CAND_USER is (USER_ID) alone. It finds the user's candidate rows but
--     every one of them then needs a table visit for TRX_TASK_ID, STAGE_SEQ and CORP_USR_ID
--     - all three of which the join and the NOT EXISTS need. Widening it to carry them makes
--     the whole candidate side index-only. The existing narrow index is left in place: it is
--     the leading column of the new one, so it is redundant, but dropping an index a live
--     inbox depends on is not something a migration should do on its own.
--
--   * V1's IX_TRX_TASK_ACTION_TASK is (TRX_TASK_ID) alone, so the NOT EXISTS ("has this
--     user already acted on this stage?") reads EVERY action row of the task and filters
--     STAGE_SEQ and ACTOR_USER_ID afterwards. A task with three approval levels and a
--     release already carries half a dozen; the probe should be a single index lookup.
--
-- Both indexes serve the inbox (findInbox) and the notification recipient queries too -
-- they are the same access paths, just aggregated. Non-unique: a candidate can appear on
-- several stages of one task and an actor writes one row per stage.

CREATE INDEX IX_TRX_TASK_CAND_USER_TASK
    ON TRX_TASK_CANDIDATE (USER_ID, TRX_TASK_ID, STAGE_SEQ, CORP_USR_ID);

CREATE INDEX IX_TRX_TASK_ACTION_STAGE
    ON TRX_TASK_ACTION (TRX_TASK_ID, STAGE_SEQ, ACTOR_USER_ID);
