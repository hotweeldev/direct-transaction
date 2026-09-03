-- P1 (LLG/SKN + RTGS): the domestic-transfer instruction payload on TRX_TASK.
--
-- TRX_TASK is THIS service's own table (V1), so widening it is regular schema work -
-- the no-Flyway rule covers DATA and legacy tables, not our own DDL. Same freeze-at-submit
-- reasoning as V1's payload columns: everything the execution call needs is denormalized
-- here so an admin editing the bank master mid-flight can never change what the approver
-- saw and released.
--
-- Column naming follows BASE_FT's own vocabulary where a matching column exists there
-- (BEN_ADDR_1..3, LLD_IS_REM_RES / LLD_IS_BEN_RES, BEN_TYPE, BEN_DOM_BNK_ID) so the
-- execution-time BASE_FT write is a straight copy.
--
--   BEN_DOM_BNK_ID  the chosen COM_MT_DOM_BANK.ID (FK by convention, not constraint -
--                   legacy tables carry no FKs to our tables and vice versa)
--   BEN_BNK_CD      what goes upstream as the bank code: the 7-digit sandi kliring (LLG)
--                   or the RTGS/BIC member code (RTGS)
--   BEN_BNK_BIC     the final bank BIC - kliring's bankPenerimaAkhir (8 chars); for RTGS
--                   the same value as BEN_BNK_CD
--   BEN_BNK_NM      display name, frozen for the approval screens
--   LLD_IS_*_RES    residency codes in the METHOD'S OWN vocabulary (kliring 1/2/3,
--                   RTGS 0/1) - never translated, per the integration contract
--   FEE_AMT         the flat fee frozen at submit (P1 placeholder; the P4 engine will
--                   compute it) - the debited total is TRX_AMT + FEE_AMT
--
-- All columns NULL for the in-house types, so this ALTER is safe on live data.

ALTER TABLE TRX_TASK ADD (
    BEN_DOM_BNK_ID NVARCHAR2(40),
    BEN_BNK_CD     NVARCHAR2(20),
    BEN_BNK_NM     NVARCHAR2(200),
    BEN_BNK_BIC    NVARCHAR2(20),
    BEN_ADDR_1     NVARCHAR2(100),
    BEN_ADDR_2     NVARCHAR2(100),
    BEN_ADDR_3     NVARCHAR2(100),
    BEN_PHONE      NVARCHAR2(20),
    BEN_POSTAL_CD  NVARCHAR2(8),
    BEN_ID_TYPE    NVARCHAR2(4),
    BEN_ID_NO      NVARCHAR2(24),
    BEN_TYPE       NVARCHAR2(2),
    LLD_IS_REM_RES NVARCHAR2(2),
    LLD_IS_BEN_RES NVARCHAR2(2),
    FEE_AMT        NUMBER(25,7)
);
