-- The execution phase's bookkeeping on TRX_TASK.
--
-- V3 also widens the STATUS vocabulary V1 documented. The full set is now:
--   PENDING_APPROVAL / PENDING_RELEASE / REJECTED / READY_TO_EXECUTE /
--   EXECUTING / EXECUTED / FAILED / UNKNOWN
-- EXECUTING is the claimed-but-not-finished state (the optimistic-VERSION claim that
-- makes double-execution impossible commits it BEFORE the core hop, so a crash mid-call
-- leaves a visibly stuck EXECUTING task instead of a silently re-executable one).
-- UNKNOWN is the timeout verdict: the core transfer call timed out and the contract
-- forbids resending - the money may or may not have moved, reconciliation via
-- /transfers/status is a documented TODO. FAILED carries its reason in the EXECUTE
-- action row's NOTE (TRX_TASK_ACTION gains the EXECUTE action value alongside V1's
-- SUBMIT / APPROVE / REJECT / RELEASE - no DDL needed, the column is free text).
--
--   CORE_JOURNAL - core banking's journal number, the handle its support team asks for.
--                  Stored the moment the core call answers 200; a journal that is
--                  dropped makes the transfer unreconcilable.
--   TRX_REF_NO   - the 20-digit reference minted from TRX_REF_NO_SEQUENCE at EXECUTION
--                  time (not the submit-time REF_NO): BASE_FT.TRX_REF_NO carries the
--                  same value, so the workflow task and the legacy booking row correlate.
--   EXECUTED_DT  - when the terminal execution verdict landed.
--
-- All three are NULL for every task that has not reached execution, so this ALTER is
-- safe on live data.

ALTER TABLE TRX_TASK ADD (
    CORE_JOURNAL NVARCHAR2(40),
    TRX_REF_NO   NVARCHAR2(40),
    EXECUTED_DT  DATE
);
