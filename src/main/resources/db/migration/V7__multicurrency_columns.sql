-- P5 (multi-currency inhouse): the cross-currency / loan-source / underlying-check
-- payload on TRX_TASK.
--
-- TRX_TASK is THIS service's own table (V1), so widening it is regular schema work -
-- the no-Flyway rule covers DATA and legacy tables, not our own DDL (same reasoning as
-- V5/V6). Everything here is frozen at submit, so what the approver saw is what executes
-- (rates included: BASE_AMT is computed once, at submit, never re-quoted at release).
--
--   DEBIT_CCY_CD        the SOURCE account's currency - only set when it differs from
--                       TRX_CCY_CD (TRX_CCY_CD stays the CREDIT currency, the amount the
--                       beneficiary receives, per the velis Fund Transfer sheet)
--   DEBIT_AMT           the customer-typed DEBIT amount for a cross transfer; the limit
--                       ladder validates THIS side (+fee), in DEBIT_CCY_CD
--   EXCHANGE_RATE       the buy rate of the debit currency used to compute BASE_AMT -
--                       only populated when a rate was actually fetched (valas->valas)
--   BASE_AMT            the IDR equivalent DepTransferCross mandates: credit ccy IDR ->
--                       credit amount; debit ccy IDR -> debit amount; else
--                       DEBIT_AMT x buy rate, rounded to 0 dp (the IDR decimal rule)
--   RATE_TYPE           core banking rate type ('02' Regular default; kurs khusus via
--                       SmartForex stays blocked(env) - task 5.2)
--   SOURCE_PRODUCT_TYPE core banking's accountProductType of the source account (DEP /
--                       LON / ...) from AccountShortDetails; LON routes execution to the
--                       LoanTransfer operation
--   ADVISORY_MSG        the trxPBI advisory (statement-required corridor): surfaced on
--                       submit and task detail, never blocking
--   UND_DOC_*           the underlying document the maker declared (type, number, name,
--                       amount, expiry) - kept as text; the check consumes them, core
--                       banking does not
--
-- All columns NULL for every existing task and for same-currency non-loan transfers,
-- so this ALTER is safe on live data. Schema-only: no data rides in this migration.

ALTER TABLE TRX_TASK ADD (
    DEBIT_CCY_CD        NVARCHAR2(3),
    DEBIT_AMT           NUMBER(25,7),
    EXCHANGE_RATE       NUMBER(18,7),
    BASE_AMT            NUMBER(25,7),
    RATE_TYPE           NVARCHAR2(2),
    SOURCE_PRODUCT_TYPE NVARCHAR2(8),
    ADVISORY_MSG        NVARCHAR2(400),
    UND_DOC_TYPE        NVARCHAR2(40),
    UND_DOC_NO          NVARCHAR2(40),
    UND_DOC_NM          NVARCHAR2(100),
    UND_DOC_AMT         NUMBER(25,7),
    UND_DOC_EXPIRY      NVARCHAR2(10)
);
