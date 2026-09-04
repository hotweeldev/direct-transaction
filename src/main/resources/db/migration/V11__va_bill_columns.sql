-- P3 follow-up (Transfer ke Virtual Account): the bill block the VA billing inquiry
-- answers, frozen on the task as JSON.
--
-- The legacy SNAP log (VA Billing Inquiry-Payment (SendVA).md, 2023-05-31) showed what a
-- successful billing-inquiry really carries: not just a name and an amount but the whole
-- presentation block the legacy VIRTUAL_ACCOUNT_FT row stores verbatim - billingLabel,
-- vaNameLabel, virtualAccountTrxType ('o' = open payment, the customer types the
-- amount; anything else = a fixed bill), billedAmountLabel / billedAmountValue
-- ("OPEN PAYMENT" or the bill's figure), feeAmount with its label and display value,
-- accountNumberTo, the VA service's own trxId and clientId, and three additional
-- label/value pairs. The maker sees it at inquiry time; the release runs hours later on
-- another thread, and the legacy row must carry exactly what the inquiry said. So the
-- block is echoed by the FE on the submit and frozen here, the same way V9 freezes
-- inquiryRequestId and V10 the BI-Fast creditor block.
--
-- One JSON column rather than fourteen: every field is presentation text the service
-- only copies into VIRTUAL_ACCOUNT_FT, nothing here is queried or joined on, and the
-- VA service may add fields (it already answers more than the spec listed) without
-- another migration. Keys are the camelCase names the inquiry endpoint answers.
--
-- TRX_TASK is THIS service's own table (V1), so widening it is regular schema work - the
-- no-Flyway rule covers DATA and legacy tables, not our own DDL (same reasoning as
-- V5-V10). NULL for every existing task and for every non-VA transfer; schema-only, no
-- data rides in this migration.

ALTER TABLE TRX_TASK ADD (
    VA_BILL_JSON  NVARCHAR2(2000)
);
