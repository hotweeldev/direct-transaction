-- P7 (Transfer ke Bank Lain via BI-Fast): the frozen instruction block the two-call
-- BI-Fast wire needs between the maker's inquiry and the release-time credit transfer.
--
-- BI-Fast is stateful across its calls the way VA billing is (V9): inquiry-transfer
-- answers the creditor's identity (id, type, account type, resident status, town) and a
-- settlement date, and credit-transfer must echo every one of them back. The maker runs
-- the inquiry on the form, the credit runs at release - hours later, another thread,
-- another user - so the block lives on the task. The credit transfer then answers the
-- switch's own identifiers (trxId, endToEndId) which the legacy BASE_FT row also carries;
-- they are stored here too so the detail screen can show them without a BASE_FT join.
--
-- TRX_TASK is THIS service's own table (V1), so widening it is regular schema work - the
-- no-Flyway rule covers DATA and legacy tables, not our own DDL (same reasoning as
-- V5-V9). The destination bank's BI-Fast BIC rides the existing BEN_BNK_CD / BEN_BNK_BIC
-- (COM_MT_DOM_BANK.BIFAST_CD), the flat fee the existing FEE_AMT.
--
--   BIFAST_PURPOSE_CD       transactionPurpose, a COM_MT_BIFAST_TRX_PURPOSE.CD (01 Investment,
--                           02 Transfer of Wealth, 03 Purchase, 99 Others)
--   BIFAST_CRED_ID          credId echoed from the inquiry
--   BIFAST_CRED_TYPE        credType (01 individual, 02 corporate per the switch)
--   BIFAST_CRED_ACCT_TYPE   credAccType (SVGS, CACC, ...)
--   BIFAST_CRED_RSDNT_STS   credRsdntStatus
--   BIFAST_CRED_TOWN        credTownName
--   BIFAST_SETTLEMENT_DT    settlementDate as the switch spelled it (yyyy-MM-dd), echoed verbatim
--   PROXY_TYPE / PROXY_ID   the proxy (alias) route when the maker paid a proxy instead of
--                           an account number; NULL on the account route
--   TRX_ID / END_TO_END_ID  the switch identifiers answered by credit-transfer
--
-- All NULL for every existing task and for every non-BI-Fast transfer; schema-only, no
-- data rides in this migration.

ALTER TABLE TRX_TASK ADD (
    BIFAST_PURPOSE_CD      NVARCHAR2(10),
    BIFAST_CRED_ID         NVARCHAR2(40),
    BIFAST_CRED_TYPE       NVARCHAR2(10),
    BIFAST_CRED_ACCT_TYPE  NVARCHAR2(10),
    BIFAST_CRED_RSDNT_STS  NVARCHAR2(10),
    BIFAST_CRED_TOWN       NVARCHAR2(40),
    BIFAST_SETTLEMENT_DT   NVARCHAR2(20),
    PROXY_TYPE             NVARCHAR2(20),
    PROXY_ID               NVARCHAR2(100),
    TRX_ID                 NVARCHAR2(100),
    END_TO_END_ID          NVARCHAR2(100)
);
