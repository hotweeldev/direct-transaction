package id.co.bni.direct.transaction.entity;

import org.apache.ibatis.annotations.AutomapConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Row shapes for this service's own TRX_TASK / TRX_TASK_STAGE / TRX_TASK_ACTION tables. */
public final class TrxTaskRows {

    private TrxTaskRows() {
    }

    /**
     * Everything TRX_TASK holds that the maker-submit phase writes or reads back.
     * {@code domestic} is null for the in-house types - its V5 columns then stay NULL.
     */
    public record TaskInsert(
            String id,
            String corpId,
            String menuCd,
            String srvcCd,
            String refNo,
            String status,
            Integer currentStageSeq,
            String remAcctNo,
            String benAcctNo,
            String benAcctNm,
            BigDecimal trxAmt,
            String trxCcyCd,
            String remark1,
            String remark2,
            String remark3,
            String instructionMode,
            String makerUserId,
            String makerUserName,
            String isSingleUser,
            String createdBy,
            DomesticInsert domestic,
            CrossInsert cross,
            String vaInquiryReqId,
            BiFastInsert bifast,
            String vaBillJson,
            /** 'REMITTER' or 'BENEFICIARY'; null on tasks minted before the charge engine. */
            String chargeTo,
            /**
             * What this task reserved of the daily ceilings, frozen so a release gives back
             * exactly what was taken. Null on every task minted before the reservation
             * existed, and on transfers no ceiling row applies to.
             */
            LimitReservationInsert limit) {

        /** The pre-reservation shape (everything but the limit block). */
        public TaskInsert(String id, String corpId, String menuCd, String srvcCd,
                          String refNo, String status, Integer currentStageSeq,
                          String remAcctNo, String benAcctNo, String benAcctNm,
                          BigDecimal trxAmt, String trxCcyCd, String remark1,
                          String remark2, String remark3, String instructionMode,
                          String makerUserId, String makerUserName, String isSingleUser,
                          String createdBy, DomesticInsert domestic, CrossInsert cross,
                          String vaInquiryReqId, BiFastInsert bifast, String vaBillJson,
                          String chargeTo) {
            this(id, corpId, menuCd, srvcCd, refNo, status, currentStageSeq, remAcctNo,
                    benAcctNo, benAcctNm, trxAmt, trxCcyCd, remark1, remark2, remark3,
                    instructionMode, makerUserId, makerUserName, isSingleUser, createdBy,
                    domestic, cross, vaInquiryReqId, bifast, vaBillJson, chargeTo, null);
        }

        /** The pre-charge-engine shape (everything but the charge bearer). */
        public TaskInsert(String id, String corpId, String menuCd, String srvcCd,
                          String refNo, String status, Integer currentStageSeq,
                          String remAcctNo, String benAcctNo, String benAcctNm,
                          BigDecimal trxAmt, String trxCcyCd, String remark1,
                          String remark2, String remark3, String instructionMode,
                          String makerUserId, String makerUserName, String isSingleUser,
                          String createdBy, DomesticInsert domestic, CrossInsert cross,
                          String vaInquiryReqId, BiFastInsert bifast, String vaBillJson) {
            this(id, corpId, menuCd, srvcCd, refNo, status, currentStageSeq, remAcctNo,
                    benAcctNo, benAcctNm, trxAmt, trxCcyCd, remark1, remark2, remark3,
                    instructionMode, makerUserId, makerUserName, isSingleUser, createdBy,
                    domestic, cross, vaInquiryReqId, bifast, vaBillJson, null, null);
        }

        /** The pre-V11 shape (everything but the frozen VA bill block). */
        public TaskInsert(String id, String corpId, String menuCd, String srvcCd,
                          String refNo, String status, Integer currentStageSeq,
                          String remAcctNo, String benAcctNo, String benAcctNm,
                          BigDecimal trxAmt, String trxCcyCd, String remark1,
                          String remark2, String remark3, String instructionMode,
                          String makerUserId, String makerUserName, String isSingleUser,
                          String createdBy, DomesticInsert domestic, CrossInsert cross,
                          String vaInquiryReqId, BiFastInsert bifast) {
            this(id, corpId, menuCd, srvcCd, refNo, status, currentStageSeq, remAcctNo,
                    benAcctNo, benAcctNm, trxAmt, trxCcyCd, remark1, remark2, remark3,
                    instructionMode, makerUserId, makerUserName, isSingleUser, createdBy,
                    domestic, cross, vaInquiryReqId, bifast, null, null);
        }

        /** The pre-P7 shape (everything but the V10 BI-Fast block). */
        public TaskInsert(String id, String corpId, String menuCd, String srvcCd,
                          String refNo, String status, Integer currentStageSeq,
                          String remAcctNo, String benAcctNo, String benAcctNm,
                          BigDecimal trxAmt, String trxCcyCd, String remark1,
                          String remark2, String remark3, String instructionMode,
                          String makerUserId, String makerUserName, String isSingleUser,
                          String createdBy, DomesticInsert domestic, CrossInsert cross,
                          String vaInquiryReqId) {
            this(id, corpId, menuCd, srvcCd, refNo, status, currentStageSeq, remAcctNo,
                    benAcctNo, benAcctNm, trxAmt, trxCcyCd, remark1, remark2, remark3,
                    instructionMode, makerUserId, makerUserName, isSingleUser, createdBy,
                    domestic, cross, vaInquiryReqId, null);
        }

        /** The P0 shape; the V5 domestic and V7 cross blocks stay NULL. */
        public TaskInsert(String id, String corpId, String menuCd, String srvcCd,
                          String refNo, String status, Integer currentStageSeq,
                          String remAcctNo, String benAcctNo, String benAcctNm,
                          BigDecimal trxAmt, String trxCcyCd, String remark1,
                          String remark2, String remark3, String instructionMode,
                          String makerUserId, String makerUserName, String isSingleUser,
                          String createdBy) {
            this(id, corpId, menuCd, srvcCd, refNo, status, currentStageSeq, remAcctNo,
                    benAcctNo, benAcctNm, trxAmt, trxCcyCd, remark1, remark2, remark3,
                    instructionMode, makerUserId, makerUserName, isSingleUser, createdBy,
                    null);
        }

        /** The pre-P5 shape (V5 domestic block only); the V7 cross block stays NULL. */
        public TaskInsert(String id, String corpId, String menuCd, String srvcCd,
                          String refNo, String status, Integer currentStageSeq,
                          String remAcctNo, String benAcctNo, String benAcctNm,
                          BigDecimal trxAmt, String trxCcyCd, String remark1,
                          String remark2, String remark3, String instructionMode,
                          String makerUserId, String makerUserName, String isSingleUser,
                          String createdBy, DomesticInsert domestic) {
            this(id, corpId, menuCd, srvcCd, refNo, status, currentStageSeq, remAcctNo,
                    benAcctNo, benAcctNm, trxAmt, trxCcyCd, remark1, remark2, remark3,
                    instructionMode, makerUserId, makerUserName, isSingleUser, createdBy,
                    domestic, null);
        }

        /** The pre-P3 shape (domestic + cross blocks); the V9 VA inquiry id stays NULL. */
        public TaskInsert(String id, String corpId, String menuCd, String srvcCd,
                          String refNo, String status, Integer currentStageSeq,
                          String remAcctNo, String benAcctNo, String benAcctNm,
                          BigDecimal trxAmt, String trxCcyCd, String remark1,
                          String remark2, String remark3, String instructionMode,
                          String makerUserId, String makerUserName, String isSingleUser,
                          String createdBy, DomesticInsert domestic, CrossInsert cross) {
            this(id, corpId, menuCd, srvcCd, refNo, status, currentStageSeq, remAcctNo,
                    benAcctNo, benAcctNm, trxAmt, trxCcyCd, remark1, remark2, remark3,
                    instructionMode, makerUserId, makerUserName, isSingleUser, createdBy,
                    domestic, cross, null);
        }
    }

    /**
     * The V5 domestic (LLG/RTGS) instruction block frozen on TRX_TASK at submit.
     * {@code benBnkCd} is what goes upstream as the bank code (sandi kliring for LLG,
     * RTGS/BIC for RTGS); {@code benBnkBic} the final-bank BIC. Residency codes are in
     * the method's own vocabulary. {@code feeAmt} is the flat P1 fee - the debited total
     * is TRX_AMT + FEE_AMT.
     */
    public record DomesticInsert(
            String benDomBnkId,
            String benBnkCd,
            String benBnkNm,
            String benBnkBic,
            String benAddr1,
            String benAddr2,
            String benAddr3,
            String benPhone,
            String benPostalCd,
            String benIdType,
            String benIdNo,
            String benType,
            String lldIsRemRes,
            String lldIsBenRes,
            BigDecimal feeAmt) {
    }

    /**
     * The V13 limit block: which daily ceiling line this task consumed, in which currency
     * combination, and how much of it - in the CEILING's currency, which is not necessarily
     * the transfer's. Frozen at submit because the combination is derived from the source
     * account's currency, and an administrator can change that under a task that is still
     * waiting for its approvals.
     */
    public record LimitReservationInsert(
            String srvcCcyMtrxId,
            String ccyMtrxCd,
            String ccyCd,
            BigDecimal reservedAmt) {
    }

    /**
     * The V7 multi-currency block frozen on TRX_TASK at submit (P5).
     * {@code debitCcyCd}/{@code debitAmt} only for a CROSS transfer (source currency !=
     * credit currency); {@code baseAmt} is the server-computed IDR equivalent
     * DepTransferCross mandates - NEVER taken from the FE; {@code exchangeRate} only when
     * a rate was actually fetched (valas-to-valas); {@code sourceProductType} whenever the
     * source account was resolved (LON routes execution to LoanTransfer);
     * {@code advisoryMsg} the non-blocking trxPBI advisory; {@code undDoc*} the
     * maker-declared underlying document.
     */
    public record CrossInsert(
            String debitCcyCd,
            BigDecimal debitAmt,
            BigDecimal exchangeRate,
            BigDecimal baseAmt,
            String rateType,
            String sourceProductType,
            String advisoryMsg,
            String undDocType,
            String undDocNo,
            String undDocNm,
            BigDecimal undDocAmt,
            String undDocExpiry) {
    }

    /**
     * The V10 BI-Fast block frozen on TRX_TASK at submit (P7): the transaction purpose
     * and the creditor identity the inquiry answered, echoed verbatim on the credit
     * transfer at release; the proxy route when the maker paid an alias. Null on every
     * other product.
     */
    public record BiFastInsert(
            String purposeCd,
            String credId,
            String credType,
            String credAcctType,
            String credRsdntSts,
            String credTown,
            String settlementDt,
            String proxyType,
            String proxyId) {
    }

    /** One TRX_TASK_STAGE row at insert time. */
    public record StageInsert(
            String id,
            String trxTaskId,
            Integer seqNo,
            String stageType,
            String aprvLvlCd,
            String usrGrpOpt,
            String corpUsrGrpId,
            Integer requiredCount,
            String status,
            String createdBy) {
    }

    /** One TRX_TASK_ACTION row at insert time. */
    public record ActionInsert(
            String id,
            String trxTaskId,
            Integer stageSeq,
            String action,
            String actorUserId,
            String actorUserName,
            String actorGroupId,
            String otpVerificationId,
            String note) {
    }

    /** One TRX_TASK_CANDIDATE row at insert time - the frozen eligible-user set (V2). */
    public record CandidateInsert(
            String id,
            String trxTaskId,
            Integer stageSeq,
            String userId,
            String corpUsrId,
            String userName,
            String corpUsrGrpId,
            String createdBy) {
    }

    /**
     * The task as the detail and approve flows read it back. {@code version} is the
     * optimistic-lock counter every approve/reject claim must match.
     */
    public record TaskRow(
            String id,
            String refNo,
            String menuCd,
            String srvcCd,
            String status,
            Integer currentStageSeq,
            BigDecimal trxAmt,
            String trxCcyCd,
            String remAcctNo,
            String benAcctNo,
            String benAcctNm,
            String remark1,
            String makerUserName,
            LocalDateTime createdDt,
            Long version,
            String coreJournal,
            String trxRefNo,
            LocalDateTime executedDt,
            String benDomBnkId,
            String benBnkNm,
            String benBnkCd,
            String benBnkBic,
            BigDecimal feeAmt,
            String retrievalRefNo,
            String interbankResponseCd,
            String debitCcyCd,
            BigDecimal debitAmt,
            BigDecimal exchangeRate,
            String sourceProductType,
            String advisoryMsg,
            String twoLegState,
            String simsemAcctNo,
            String journalNoSimsem,
            String trxId,
            String endToEndId,
            String bifastPurposeCd,
            String vaBillJson,
            /** 'REMITTER' or 'BENEFICIARY'; null on tasks minted before the charge engine. */
            String chargeTo,
            String lmtSrvcCcyMtrxId,
            String lmtCcyMtrxCd,
            String lmtCcyCd,
            BigDecimal lmtReservedAmt,
            /** CORP_USR.ID of the maker - the group whose ceiling the reservation used. */
            String makerUserId) {

        /** The pre-reservation shape (everything but the limit block). */
        public TaskRow(String id, String refNo, String menuCd, String srvcCd,
                       String status, Integer currentStageSeq, BigDecimal trxAmt,
                       String trxCcyCd, String remAcctNo, String benAcctNo,
                       String benAcctNm, String remark1, String makerUserName,
                       LocalDateTime createdDt, Long version, String coreJournal,
                       String trxRefNo, LocalDateTime executedDt, String benDomBnkId,
                       String benBnkNm, String benBnkCd, String benBnkBic,
                       BigDecimal feeAmt, String retrievalRefNo, String interbankResponseCd,
                       String debitCcyCd, BigDecimal debitAmt, BigDecimal exchangeRate,
                       String sourceProductType, String advisoryMsg, String twoLegState,
                       String simsemAcctNo, String journalNoSimsem, String trxId,
                       String endToEndId, String bifastPurposeCd, String vaBillJson,
                       String chargeTo) {
            this(id, refNo, menuCd, srvcCd, status, currentStageSeq, trxAmt, trxCcyCd,
                    remAcctNo, benAcctNo, benAcctNm, remark1, makerUserName, createdDt,
                    version, coreJournal, trxRefNo, executedDt, benDomBnkId, benBnkNm,
                    benBnkCd, benBnkBic, feeAmt, retrievalRefNo, interbankResponseCd,
                    debitCcyCd, debitAmt, exchangeRate, sourceProductType, advisoryMsg,
                    twoLegState, simsemAcctNo, journalNoSimsem, trxId, endToEndId,
                    bifastPurposeCd, vaBillJson, chargeTo, null, null, null, null, null);
        }

        /** The pre-charge-engine shape (everything but the charge bearer). */
        public TaskRow(String id, String refNo, String menuCd, String srvcCd,
                       String status, Integer currentStageSeq, BigDecimal trxAmt,
                       String trxCcyCd, String remAcctNo, String benAcctNo,
                       String benAcctNm, String remark1, String makerUserName,
                       LocalDateTime createdDt, Long version, String coreJournal,
                       String trxRefNo, LocalDateTime executedDt, String benDomBnkId,
                       String benBnkNm, String benBnkCd, String benBnkBic,
                       BigDecimal feeAmt, String retrievalRefNo, String interbankResponseCd,
                       String debitCcyCd, BigDecimal debitAmt, BigDecimal exchangeRate,
                       String sourceProductType, String advisoryMsg, String twoLegState,
                       String simsemAcctNo, String journalNoSimsem, String trxId,
                       String endToEndId, String bifastPurposeCd, String vaBillJson) {
            this(id, refNo, menuCd, srvcCd, status, currentStageSeq, trxAmt, trxCcyCd,
                    remAcctNo, benAcctNo, benAcctNm, remark1, makerUserName, createdDt,
                    version, coreJournal, trxRefNo, executedDt, benDomBnkId, benBnkNm,
                    benBnkCd, benBnkBic, feeAmt, retrievalRefNo, interbankResponseCd,
                    debitCcyCd, debitAmt, exchangeRate, sourceProductType, advisoryMsg,
                    twoLegState, simsemAcctNo, journalNoSimsem, trxId, endToEndId,
                    bifastPurposeCd, vaBillJson, null, null, null, null, null, null);
        }

        /** The pre-V11 shape (everything but the frozen VA bill block). */
        public TaskRow(String id, String refNo, String menuCd, String srvcCd,
                       String status, Integer currentStageSeq, BigDecimal trxAmt,
                       String trxCcyCd, String remAcctNo, String benAcctNo,
                       String benAcctNm, String remark1, String makerUserName,
                       LocalDateTime createdDt, Long version, String coreJournal,
                       String trxRefNo, LocalDateTime executedDt, String benDomBnkId,
                       String benBnkNm, String benBnkCd, String benBnkBic,
                       BigDecimal feeAmt, String retrievalRefNo, String interbankResponseCd,
                       String debitCcyCd, BigDecimal debitAmt, BigDecimal exchangeRate,
                       String sourceProductType, String advisoryMsg, String twoLegState,
                       String simsemAcctNo, String journalNoSimsem, String trxId,
                       String endToEndId, String bifastPurposeCd) {
            this(id, refNo, menuCd, srvcCd, status, currentStageSeq, trxAmt, trxCcyCd,
                    remAcctNo, benAcctNo, benAcctNm, remark1, makerUserName, createdDt,
                    version, coreJournal, trxRefNo, executedDt, benDomBnkId, benBnkNm,
                    benBnkCd, benBnkBic, feeAmt, retrievalRefNo, interbankResponseCd,
                    debitCcyCd, debitAmt, exchangeRate, sourceProductType, advisoryMsg,
                    twoLegState, simsemAcctNo, journalNoSimsem, trxId, endToEndId,
                    bifastPurposeCd, null);
        }

        /** The pre-P7 shape - everything but the BI-Fast identifiers and purpose. */
        public TaskRow(String id, String refNo, String menuCd, String srvcCd,
                       String status, Integer currentStageSeq, BigDecimal trxAmt,
                       String trxCcyCd, String remAcctNo, String benAcctNo,
                       String benAcctNm, String remark1, String makerUserName,
                       LocalDateTime createdDt, Long version, String coreJournal,
                       String trxRefNo, LocalDateTime executedDt, String benDomBnkId,
                       String benBnkNm, String benBnkCd, String benBnkBic,
                       BigDecimal feeAmt, String retrievalRefNo, String interbankResponseCd,
                       String debitCcyCd, BigDecimal debitAmt, BigDecimal exchangeRate,
                       String sourceProductType, String advisoryMsg, String twoLegState,
                       String simsemAcctNo, String journalNoSimsem) {
            this(id, refNo, menuCd, srvcCd, status, currentStageSeq, trxAmt, trxCcyCd,
                    remAcctNo, benAcctNo, benAcctNm, remark1, makerUserName, createdDt,
                    version, coreJournal, trxRefNo, executedDt, benDomBnkId, benBnkNm,
                    benBnkCd, benBnkBic, feeAmt, retrievalRefNo, interbankResponseCd,
                    debitCcyCd, debitAmt, exchangeRate, sourceProductType, advisoryMsg,
                    twoLegState, simsemAcctNo, journalNoSimsem, null, null, null, null);
        }

        /**
         * MyBatis must build rows through the canonical constructor - with a second
         * (convenience) constructor present, arg-name-based auto-mapping refuses to
         * choose on its own (the P1 E2E lesson, see ExecutionTaskRow).
         */
        @AutomapConstructor
        public TaskRow {
        }

        /** The pre-P5 shape - callers and tests that never touch the P5 block. */
        public TaskRow(String id, String refNo, String menuCd, String srvcCd,
                       String status, Integer currentStageSeq, BigDecimal trxAmt,
                       String trxCcyCd, String remAcctNo, String benAcctNo,
                       String benAcctNm, String remark1, String makerUserName,
                       LocalDateTime createdDt, Long version, String coreJournal,
                       String trxRefNo, LocalDateTime executedDt, String benDomBnkId,
                       String benBnkNm, String benBnkCd, String benBnkBic,
                       BigDecimal feeAmt, String retrievalRefNo, String interbankResponseCd) {
            this(id, refNo, menuCd, srvcCd, status, currentStageSeq, trxAmt, trxCcyCd,
                    remAcctNo, benAcctNo, benAcctNm, remark1, makerUserName, createdDt,
                    version, coreJournal, trxRefNo, executedDt, benDomBnkId, benBnkNm,
                    benBnkCd, benBnkBic, feeAmt, retrievalRefNo, interbankResponseCd,
                    null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * The task as the EXECUTION phase reads it - by ID alone (the caller already proved
     * company scope when the task was released), carrying what the core call and the
     * BASE_FT write need: the resolved service, the frozen instruction payload, the
     * maker's CORP_USR.ID and the VERSION the EXECUTING claim must match.
     */
    public record ExecutionTaskRow(
            String id,
            String corpId,
            String menuCd,
            String srvcCd,
            String refNo,
            String status,
            String remAcctNo,
            String benAcctNo,
            String benAcctNm,
            BigDecimal trxAmt,
            String trxCcyCd,
            String remark1,
            String makerUserId,
            Long version,
            String benDomBnkId,
            String benBnkCd,
            String benBnkBic,
            String benAddr1,
            String benAddr2,
            String benAddr3,
            String benPhone,
            String benPostalCd,
            String benIdType,
            String benIdNo,
            String benType,
            String lldIsRemRes,
            String lldIsBenRes,
            BigDecimal feeAmt,
            String debitCcyCd,
            BigDecimal debitAmt,
            BigDecimal baseAmt,
            String rateType,
            String sourceProductType,
            String vaInquiryReqId,
            String bifastPurposeCd,
            String bifastCredId,
            String bifastCredType,
            String bifastCredAcctType,
            String bifastCredRsdntSts,
            String bifastCredTown,
            String bifastSettlementDt,
            String proxyType,
            String proxyId,
            String vaBillJson,
            /** 'REMITTER' or 'BENEFICIARY'; null on tasks minted before the charge engine. */
            String chargeTo,
            String lmtSrvcCcyMtrxId,
            String lmtCcyMtrxCd,
            String lmtCcyCd,
            BigDecimal lmtReservedAmt) {

        /** The pre-reservation shape (everything but the limit block). */
        public ExecutionTaskRow(String id, String corpId, String menuCd, String srvcCd,
                                String refNo, String status, String remAcctNo,
                                String benAcctNo, String benAcctNm, BigDecimal trxAmt,
                                String trxCcyCd, String remark1, String makerUserId,
                                Long version, String benDomBnkId, String benBnkCd,
                                String benBnkBic, String benAddr1, String benAddr2,
                                String benAddr3, String benPhone, String benPostalCd,
                                String benIdType, String benIdNo, String benType,
                                String lldIsRemRes, String lldIsBenRes, BigDecimal feeAmt,
                                String debitCcyCd, BigDecimal debitAmt, BigDecimal baseAmt,
                                String rateType, String sourceProductType, String vaInquiryReqId,
                                String bifastPurposeCd, String bifastCredId, String bifastCredType,
                                String bifastCredAcctType, String bifastCredRsdntSts,
                                String bifastCredTown, String bifastSettlementDt,
                                String proxyType, String proxyId, String vaBillJson,
                                String chargeTo) {
            this(id, corpId, menuCd, srvcCd, refNo, status, remAcctNo, benAcctNo,
                    benAcctNm, trxAmt, trxCcyCd, remark1, makerUserId, version,
                    benDomBnkId, benBnkCd, benBnkBic, benAddr1, benAddr2, benAddr3,
                    benPhone, benPostalCd, benIdType, benIdNo, benType, lldIsRemRes,
                    lldIsBenRes, feeAmt, debitCcyCd, debitAmt, baseAmt, rateType,
                    sourceProductType, vaInquiryReqId,
                    bifastPurposeCd, bifastCredId, bifastCredType, bifastCredAcctType,
                    bifastCredRsdntSts, bifastCredTown, bifastSettlementDt, proxyType, proxyId,
                    vaBillJson, chargeTo, null, null, null, null);
        }

        /** The pre-charge-engine shape (everything but the charge bearer). */
        public ExecutionTaskRow(String id, String corpId, String menuCd, String srvcCd,
                                String refNo, String status, String remAcctNo,
                                String benAcctNo, String benAcctNm, BigDecimal trxAmt,
                                String trxCcyCd, String remark1, String makerUserId,
                                Long version, String benDomBnkId, String benBnkCd,
                                String benBnkBic, String benAddr1, String benAddr2,
                                String benAddr3, String benPhone, String benPostalCd,
                                String benIdType, String benIdNo, String benType,
                                String lldIsRemRes, String lldIsBenRes, BigDecimal feeAmt,
                                String debitCcyCd, BigDecimal debitAmt, BigDecimal baseAmt,
                                String rateType, String sourceProductType, String vaInquiryReqId,
                                String bifastPurposeCd, String bifastCredId, String bifastCredType,
                                String bifastCredAcctType, String bifastCredRsdntSts,
                                String bifastCredTown, String bifastSettlementDt,
                                String proxyType, String proxyId, String vaBillJson) {
            this(id, corpId, menuCd, srvcCd, refNo, status, remAcctNo, benAcctNo,
                    benAcctNm, trxAmt, trxCcyCd, remark1, makerUserId, version,
                    benDomBnkId, benBnkCd, benBnkBic, benAddr1, benAddr2, benAddr3,
                    benPhone, benPostalCd, benIdType, benIdNo, benType, lldIsRemRes,
                    lldIsBenRes, feeAmt, debitCcyCd, debitAmt, baseAmt, rateType,
                    sourceProductType, vaInquiryReqId,
                    bifastPurposeCd, bifastCredId, bifastCredType, bifastCredAcctType,
                    bifastCredRsdntSts, bifastCredTown, bifastSettlementDt, proxyType, proxyId,
                    vaBillJson, null, null, null, null, null);
        }

        /** The pre-V11 shape (everything but the frozen VA bill block). */
        public ExecutionTaskRow(String id, String corpId, String menuCd, String srvcCd,
                                String refNo, String status, String remAcctNo,
                                String benAcctNo, String benAcctNm, BigDecimal trxAmt,
                                String trxCcyCd, String remark1, String makerUserId,
                                Long version, String benDomBnkId, String benBnkCd,
                                String benBnkBic, String benAddr1, String benAddr2,
                                String benAddr3, String benPhone, String benPostalCd,
                                String benIdType, String benIdNo, String benType,
                                String lldIsRemRes, String lldIsBenRes, BigDecimal feeAmt,
                                String debitCcyCd, BigDecimal debitAmt, BigDecimal baseAmt,
                                String rateType, String sourceProductType, String vaInquiryReqId,
                                String bifastPurposeCd, String bifastCredId, String bifastCredType,
                                String bifastCredAcctType, String bifastCredRsdntSts,
                                String bifastCredTown, String bifastSettlementDt,
                                String proxyType, String proxyId) {
            this(id, corpId, menuCd, srvcCd, refNo, status, remAcctNo, benAcctNo,
                    benAcctNm, trxAmt, trxCcyCd, remark1, makerUserId, version,
                    benDomBnkId, benBnkCd, benBnkBic, benAddr1, benAddr2, benAddr3,
                    benPhone, benPostalCd, benIdType, benIdNo, benType, lldIsRemRes,
                    lldIsBenRes, feeAmt, debitCcyCd, debitAmt, baseAmt, rateType,
                    sourceProductType, vaInquiryReqId,
                    bifastPurposeCd, bifastCredId, bifastCredType, bifastCredAcctType,
                    bifastCredRsdntSts, bifastCredTown, bifastSettlementDt, proxyType, proxyId,
                    null, null);
        }

        /** The pre-P7 shape (everything but the V10 BI-Fast block). */
        public ExecutionTaskRow(String id, String corpId, String menuCd, String srvcCd,
                                String refNo, String status, String remAcctNo,
                                String benAcctNo, String benAcctNm, BigDecimal trxAmt,
                                String trxCcyCd, String remark1, String makerUserId,
                                Long version, String benDomBnkId, String benBnkCd,
                                String benBnkBic, String benAddr1, String benAddr2,
                                String benAddr3, String benPhone, String benPostalCd,
                                String benIdType, String benIdNo, String benType,
                                String lldIsRemRes, String lldIsBenRes, BigDecimal feeAmt,
                                String debitCcyCd, BigDecimal debitAmt, BigDecimal baseAmt,
                                String rateType, String sourceProductType, String vaInquiryReqId) {
            this(id, corpId, menuCd, srvcCd, refNo, status, remAcctNo, benAcctNo,
                    benAcctNm, trxAmt, trxCcyCd, remark1, makerUserId, version,
                    benDomBnkId, benBnkCd, benBnkBic, benAddr1, benAddr2, benAddr3,
                    benPhone, benPostalCd, benIdType, benIdNo, benType, lldIsRemRes,
                    lldIsBenRes, feeAmt, debitCcyCd, debitAmt, baseAmt, rateType,
                    sourceProductType, vaInquiryReqId,
                    null, null, null, null, null, null, null, null, null, null);
        }

        /** The pre-P3 shape (everything but the V9 VA inquiry id). */
        public ExecutionTaskRow(String id, String corpId, String menuCd, String srvcCd,
                                String refNo, String status, String remAcctNo,
                                String benAcctNo, String benAcctNm, BigDecimal trxAmt,
                                String trxCcyCd, String remark1, String makerUserId,
                                Long version, String benDomBnkId, String benBnkCd,
                                String benBnkBic, String benAddr1, String benAddr2,
                                String benAddr3, String benPhone, String benPostalCd,
                                String benIdType, String benIdNo, String benType,
                                String lldIsRemRes, String lldIsBenRes, BigDecimal feeAmt,
                                String debitCcyCd, BigDecimal debitAmt, BigDecimal baseAmt,
                                String rateType, String sourceProductType) {
            this(id, corpId, menuCd, srvcCd, refNo, status, remAcctNo, benAcctNo,
                    benAcctNm, trxAmt, trxCcyCd, remark1, makerUserId, version,
                    benDomBnkId, benBnkCd, benBnkBic, benAddr1, benAddr2, benAddr3,
                    benPhone, benPostalCd, benIdType, benIdNo, benType, lldIsRemRes,
                    lldIsBenRes, feeAmt, debitCcyCd, debitAmt, baseAmt, rateType,
                    sourceProductType, null);
        }

        /**
         * MyBatis must build rows through the canonical constructor: with a second
         * (convenience) constructor present, arg-name-based auto-mapping refuses to
         * choose one on its own and findTaskForExecution dies with an
         * ExecutorException - seen live on the first kafka-mode LLG execution.
         */
        @AutomapConstructor
        public ExecutionTaskRow {
        }

        /** The P0 shape - in-house tasks and tests that never touch the domestic block. */
        public ExecutionTaskRow(String id, String corpId, String menuCd, String srvcCd,
                                String refNo, String status, String remAcctNo,
                                String benAcctNo, String benAcctNm, BigDecimal trxAmt,
                                String trxCcyCd, String remark1, String makerUserId,
                                Long version) {
            this(id, corpId, menuCd, srvcCd, refNo, status, remAcctNo, benAcctNo,
                    benAcctNm, trxAmt, trxCcyCd, remark1, makerUserId, version,
                    null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null);
        }

        /** The P1/P2 shape - domestic tasks and tests that never touch the P5 block. */
        public ExecutionTaskRow(String id, String corpId, String menuCd, String srvcCd,
                                String refNo, String status, String remAcctNo,
                                String benAcctNo, String benAcctNm, BigDecimal trxAmt,
                                String trxCcyCd, String remark1, String makerUserId,
                                Long version, String benDomBnkId, String benBnkCd,
                                String benBnkBic, String benAddr1, String benAddr2,
                                String benAddr3, String benPhone, String benPostalCd,
                                String benIdType, String benIdNo, String benType,
                                String lldIsRemRes, String lldIsBenRes, BigDecimal feeAmt) {
            this(id, corpId, menuCd, srvcCd, refNo, status, remAcctNo, benAcctNo,
                    benAcctNm, trxAmt, trxCcyCd, remark1, makerUserId, version,
                    benDomBnkId, benBnkCd, benBnkBic, benAddr1, benAddr2, benAddr3,
                    benPhone, benPostalCd, benIdType, benIdNo, benType, lldIsRemRes,
                    lldIsBenRes, feeAmt, null, null, null, null, null);
        }
    }

    /**
     * The P6 two-leg execution state read back by reconciliation and the detail screen:
     * the task's status, how far the two-leg flow got ({@code twoLegState}), the chosen
     * simsem account and leg 1's journal. Leg 2's journal rides on the ordinary
     * CORE_JOURNAL column.
     */
    public record TwoLegState(
            String status,
            String twoLegState,
            String simsemAcctNo,
            String journalNoSimsem) {
    }

    /** One stage as the detail endpoint reads it back. */
    public record StageRow(
            Integer seqNo,
            String stageType,
            String aprvLvlCd,
            String usrGrpOpt,
            Integer requiredCount,
            Integer completedCount,
            String status) {
    }

    /** One candidate row of the active stage, as the eligibility check reads it. */
    public record CandidateRow(
            String corpUsrId,
            String userName,
            String corpUsrGrpId) {
    }

    /** One inbox line: the task joined with the ACTIVE stage the user is a candidate on. */
    public record InboxRow(
            String id,
            String refNo,
            String menuCd,
            String status,
            BigDecimal trxAmt,
            String trxCcyCd,
            String remAcctNo,
            String benAcctNo,
            String benAcctNm,
            String remark1,
            String makerUserName,
            LocalDateTime createdDt,
            Integer stageSeq,
            String stageType,
            Integer requiredCount,
            Integer completedCount) {
    }

    /** One action row as the detail's history reads it; {@code stageSeq} null on SUBMIT. */
    public record ActionRow(
            Integer stageSeq,
            String action,
            String actorUserName,
            String note,
            LocalDateTime createdDt) {
    }
}
