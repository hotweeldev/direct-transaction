-- P6 (multi-currency Bank Lain, 2-leg via simsem): the two-leg execution state on
-- TRX_TASK.
--
-- Kliring/RTGS are IDR-only (no currency field), so a cross-currency Transfer ke Bank
-- Lain cannot go straight out. It executes in two legs through a simsem (holding)
-- account:
--   LEG 1  DepTransferCross  customer valas account -> simsem IDR account
--   LEG 2  Kliring / RTGS    simsem IDR account -> destination bank
-- with an auto-refund out of the simsem account when leg 2 is refused. These columns
-- record which account was used, how far the two-leg flow got, and leg 1's journal, so a
-- task can be finalized or reconciled long after the request thread ended.
--
-- TRX_TASK is THIS service's own table (V1), so widening it is regular schema work - the
-- no-Flyway rule covers DATA and legacy tables, not our own DDL (same reasoning as
-- V5/V6/V7). The simsem accounts themselves are DATA and are seeded per environment by
-- direct DML (CREATED_BY 'P6SEED'), never by a migration.
--
--   SIMSEM_ACCT_NO     the pool account chosen for this task at execution time (the one
--                      credited in leg 1 and debited in leg 2 / any refund). Also copied
--                      onto the leg-2 BASE_FT row's ACCT_NO_SIMSEM.
--   TWO_LEG_STATE      how far the flow got:
--                        NONE          not a two-leg task, or leg 1 not yet confirmed
--                        LEG1_DONE     cross into simsem confirmed; leg 2 pending/UNKNOWN
--                        LEG2_DONE     outward from simsem confirmed (terminal, EXECUTED)
--                        REFUND_DONE   leg 2 refused, funds returned from simsem (FAILED)
--                        REFUND_FAILED leg 2 refused AND the refund could not be confirmed
--                                      - funds may be stranded in simsem; needs attention
--   JOURNAL_NO_SIMSEM  leg 1's core journal (the credit INTO the simsem account). Leg 2's
--                      journal rides on the existing CORE_JOURNAL column, same as a
--                      single-leg transfer.
--
-- All columns NULL for every existing task and for every single-leg (same-currency or
-- in-house) transfer, so this ALTER is safe on live data. Schema-only: no data rides in
-- this migration.

ALTER TABLE TRX_TASK ADD (
    SIMSEM_ACCT_NO     NVARCHAR2(34),
    TWO_LEG_STATE      NVARCHAR2(16),
    JOURNAL_NO_SIMSEM  NVARCHAR2(40)
);
