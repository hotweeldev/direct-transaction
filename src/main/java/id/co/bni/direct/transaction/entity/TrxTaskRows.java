package id.co.bni.direct.transaction.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Row shapes for this service's own TRX_TASK / TRX_TASK_STAGE / TRX_TASK_ACTION tables. */
public final class TrxTaskRows {

    private TrxTaskRows() {
    }

    /** Everything TRX_TASK holds that the maker-submit phase writes or reads back. */
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
            String createdBy) {
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
            LocalDateTime executedDt) {
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
            Long version) {
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
