-- P2 (RTOL / ATM Bersama): the interbank switch's trace on TRX_TASK.
--
-- TRX_TASK is THIS service's own table (V1), so widening it is regular schema work -
-- the no-Flyway rule covers DATA and legacy tables, not our own DDL (same reasoning as
-- V5's payload columns).
--
--   RETRIEVAL_REF_NO       the switch's retrieval reference number (RRN) - the only
--                          cross-network identifier an ONLINE transfer ever gets (the
--                          switch protocol has no core journal), stored on every outcome
--                          where the switch answered at all and the key a manual
--                          reconciliation of an UNKNOWN task starts from
--   INTERBANK_RESPONSE_CD  the switch's ISO-8583-style response code: 00 success,
--                          68 in-process (task lands UNKNOWN, usage kept, NEVER
--                          auto-failed or resent), anything else a refusal
--
-- Both NULL for every other transfer type, so this ALTER is safe on live data.

ALTER TABLE TRX_TASK ADD (
    RETRIEVAL_REF_NO      NVARCHAR2(40),
    INTERBANK_RESPONSE_CD NVARCHAR2(4)
);
