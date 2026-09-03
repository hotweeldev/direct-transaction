-- P3 (Transfer ke Virtual Account): the one piece of VA state the workflow must carry
-- from the maker's inquiry to execution.
--
-- The VA billing service is stateful across its two calls: Billing Inquiry answers an
-- inquiryRequestId that Billing Payment must echo back, binding the payment to the
-- inquiry the customer saw. The maker runs the inquiry on the form, the payment runs at
-- release - possibly hours later, on another thread, under another user - so the id has
-- to live on the task, not in memory.
--
-- TRX_TASK is THIS service's own table (V1), so widening it is regular schema work - the
-- no-Flyway rule covers DATA and legacy tables, not our own DDL (same reasoning as
-- V5-V8). The VA number itself rides on the existing BEN_ACCT_NO and the billing name on
-- BEN_ACCT_NM; the final legacy record goes to the legacy VIRTUAL_ACCOUNT_FT table, not
-- BASE_FT, so no column is added there.
--
--   VA_INQUIRY_REQ_ID   the inquiryRequestId the VA service answered at inquiry time;
--                       forwarded verbatim on payment. NULL on every non-VA task and on
--                       a VA task whose inquiry answered no id (the payment is then sent
--                       without one - the service decides whether it accepts that).
--
-- NULL for every existing row; schema-only, no data rides in this migration.

ALTER TABLE TRX_TASK ADD (
    VA_INQUIRY_REQ_ID  NVARCHAR2(64)
);
