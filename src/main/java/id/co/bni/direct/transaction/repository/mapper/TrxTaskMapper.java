package id.co.bni.direct.transaction.repository.mapper;

import java.util.List;

import id.co.bni.direct.transaction.entity.TrxTaskRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * This service's own workflow tables: TRX_TASK, TRX_TASK_STAGE, TRX_TASK_ACTION and (V2)
 * TRX_TASK_CANDIDATE. Statements live in {@code resources/mapper/TrxTaskMapper.xml}; every
 * method here must have a matching statement id there ({@code MapperStatementsTest}).
 */
@Mapper
public interface TrxTaskMapper {

    int insertTask(TrxTaskRows.TaskInsert task);

    int insertStage(TrxTaskRows.StageInsert stage);

    int insertAction(TrxTaskRows.ActionInsert action);

    /** One frozen-candidate row; the set is written at submit and never re-derived. */
    int insertCandidate(TrxTaskRows.CandidateInsert candidate);

    /** The task, scoped by company - a task id alone must not cross companies. */
    TrxTaskRows.TaskRow findTask(@Param("companyId") String companyId,
                                 @Param("taskId") String taskId);

    List<TrxTaskRows.StageRow> findStages(@Param("taskId") String taskId);

    /** All action rows of a task, oldest first - the detail screen's history. */
    List<TrxTaskRows.ActionRow> findActions(@Param("taskId") String taskId);

    /**
     * The user's candidate row on one stage, entered by LOGIN id; null means the user was
     * not in the frozen set - the NOT_ELIGIBLE case.
     */
    TrxTaskRows.CandidateRow findCandidate(@Param("taskId") String taskId,
                                           @Param("stageSeq") Integer stageSeq,
                                           @Param("userId") String userId);

    /**
     * The names of candidates on one stage who have not acted on it yet - the "waiting
     * on" list of the view-only workflow ladder. Ordered by name; the service truncates.
     */
    List<String> findPendingCandidateNames(@Param("taskId") String taskId,
                                           @Param("stageSeq") Integer stageSeq);

    /** How many actions this actor (CORP_USR.ID) already wrote on one stage. */
    int countStageActions(@Param("taskId") String taskId,
                          @Param("stageSeq") Integer stageSeq,
                          @Param("actorUserId") String actorUserId);

    /**
     * The inbox: tasks of the company where the user is a candidate on the CURRENTLY
     * ACTIVE stage and has not yet acted on it, newest first.
     */
    List<TrxTaskRows.InboxRow> findInbox(@Param("companyId") String companyId,
                                         @Param("userId") String userId);

    /**
     * The task badge (contract §4): one indexed COUNT over the same joins as
     * {@link #findInbox}, split by stage type. Never builds a list - it is polled every
     * 30 seconds by every open browser tab.
     */
    TrxTaskRows.TaskSummaryRow findTaskSummary(@Param("companyId") String companyId,
                                               @Param("userId") String userId);

    // ---- Notification recipients (resolved producer-side, in the workflow transaction) ----

    /**
     * The frozen candidates of one stage, as notification addressees; the role comes from
     * the stage type (RELEASE -> RELEASER, otherwise APPROVER). Reads TRX_TASK_CANDIDATE
     * only - the eligible set is never re-derived from the live role tables (V2).
     */
    List<TrxTaskRows.NotificationRecipientRow> findNotificationCandidates(
            @Param("taskId") String taskId, @Param("stageSeq") Integer stageSeq);

    /**
     * The task's maker, off TRX_TASK.MAKER_USER_ID. CORP_USR is LEFT joined for the login
     * id alone (TRX_TASK stores the surrogate, not the login id, precisely because the
     * login id can be renamed while a task is in flight); a missing user row costs the
     * support field, never the notification.
     */
    TrxTaskRows.NotificationRecipientRow findNotificationMaker(@Param("taskId") String taskId);

    /**
     * Everyone who has already acted on the task in one of the given actions, deduplicated.
     * The login id comes from the actor's own candidate row on the stage they acted in.
     */
    List<TrxTaskRows.NotificationRecipientRow> findNotificationActors(
            @Param("taskId") String taskId, @Param("actions") List<String> actions);

    /**
     * The optimistic claim every approve/reject makes: moves the task to its new status
     * and stage ONLY when VERSION still matches what the caller read. 0 rows updated
     * means a concurrent actor got there first - the double-approve guard.
     */
    int updateTaskProgress(@Param("taskId") String taskId,
                           @Param("expectedVersion") Long expectedVersion,
                           @Param("status") String status,
                           @Param("currentStageSeq") Integer currentStageSeq,
                           @Param("updatedBy") String updatedBy);

    /** Store a stage's new completed count and status. */
    int updateStageProgress(@Param("taskId") String taskId,
                            @Param("seqNo") Integer seqNo,
                            @Param("completedCount") Integer completedCount,
                            @Param("status") String status,
                            @Param("updatedBy") String updatedBy);

    /** Flip one stage's status alone - activating the next stage. */
    int updateStageStatus(@Param("taskId") String taskId,
                          @Param("seqNo") Integer seqNo,
                          @Param("status") String status,
                          @Param("updatedBy") String updatedBy);

    /** A reject closes every stage still WAITING or ACTIVE ('CLOSED', not 'DONE'). */
    int closeOpenStages(@Param("taskId") String taskId,
                        @Param("updatedBy") String updatedBy);

    // ---- The execution phase ----

    /** The task as execution reads it, by ID alone; null when the task does not exist. */
    TrxTaskRows.ExecutionTaskRow findTaskForExecution(@Param("taskId") String taskId);

    /**
     * The EXECUTING claim: moves READY_TO_EXECUTE (sync) or QUEUED (kafka mode, V4) to
     * EXECUTING only when VERSION still matches. 0 rows = another executor (or a crashed
     * one) got there first - the double-execute guard, which also makes a redelivered
     * Kafka event a no-op. Committed BEFORE the core call so a crash mid-call leaves a
     * visibly stuck EXECUTING task, never a re-executable one.
     */
    int claimExecution(@Param("taskId") String taskId,
                       @Param("expectedVersion") Long expectedVersion,
                       @Param("updatedBy") String updatedBy);

    /** The success verdict: EXECUTED with the core journal and the minted TRX_REF_NO. */
    int markExecuted(@Param("taskId") String taskId,
                     @Param("coreJournal") String coreJournal,
                     @Param("trxRefNo") String trxRefNo,
                     @Param("updatedBy") String updatedBy);

    /** A non-success verdict (FAILED / UNKNOWN); guards on EXECUTING like markExecuted. */
    int markExecutionOutcome(@Param("taskId") String taskId,
                             @Param("status") String status,
                             @Param("updatedBy") String updatedBy);

    /**
     * The releasing actor's CORP_USR.ID - the latest RELEASE action row. Null on the
     * single-user path, where no release ever happened and the maker stands in.
     */
    String findReleaseActorId(@Param("taskId") String taskId);

    /**
     * The interbank switch's trace (P2 ONLINE): retrieval reference number + response
     * code, stored on every outcome where the switch answered at all - EXECUTED, UNKNOWN
     * (code 68 in-process) and FAILED alike.
     */
    int updateInterbankResult(@Param("taskId") String taskId,
                              @Param("retrievalRefNo") String retrievalRefNo,
                              @Param("responseCd") String responseCd);

    // ---- P7: BI-Fast ----

    /**
     * The switch identifiers credit-transfer answered (trxId, endToEndId), stored on the
     * task next to the journal so the detail screen needs no BASE_FT join. Not pinned to
     * a status: written right after the verdict, in the same transaction.
     */
    int updateBiFastResult(@Param("taskId") String taskId,
                           @Param("trxId") String trxId,
                           @Param("endToEndId") String endToEndId,
                           @Param("updatedBy") String updatedBy);

    // ---- P6: two-leg (simsem) execution ----

    /**
     * Pins the simsem account chosen for this task before leg 1 is sent, so a leg-1
     * timeout still leaves a record of which holding account may hold the money.
     * TWO_LEG_STATE is not touched. Guarded on EXECUTING.
     */
    int markSimsemSelected(@Param("taskId") String taskId,
                           @Param("simsemAcctNo") String simsemAcctNo,
                           @Param("updatedBy") String updatedBy);

    /**
     * Leg 1 confirmed: records the chosen simsem account and leg 1's journal and moves
     * TWO_LEG_STATE to LEG1_DONE, keeping the task EXECUTING (leg 2 has not run). Guarded
     * on EXECUTING so only the claim holder can advance it.
     */
    int markLeg1Done(@Param("taskId") String taskId,
                     @Param("simsemAcctNo") String simsemAcctNo,
                     @Param("journalNoSimsem") String journalNoSimsem,
                     @Param("updatedBy") String updatedBy);

    /** Set TWO_LEG_STATE alone (LEG2_DONE / REFUND_DONE / REFUND_FAILED) - no status change. */
    int updateTwoLegState(@Param("taskId") String taskId,
                          @Param("twoLegState") String twoLegState,
                          @Param("updatedBy") String updatedBy);

    /** The two-leg state a reconciliation reads; null when the task does not exist. */
    TrxTaskRows.TwoLegState findTwoLegState(@Param("taskId") String taskId);

    /**
     * Reconciliation found leg 2 DID land: finalize EXECUTED with leg 2's journal and
     * TWO_LEG_STATE LEG2_DONE. Guarded on the exact pre-state (UNKNOWN + LEG1_DONE) so a
     * concurrent reconcile or a re-post is a no-op (0 rows).
     */
    int reconcileToExecuted(@Param("taskId") String taskId,
                            @Param("coreJournal") String coreJournal,
                            @Param("trxRefNo") String trxRefNo,
                            @Param("updatedBy") String updatedBy);

    /**
     * Reconciliation found leg 2 did NOT land and the refund ran: land the task on its
     * terminal status ({@code FAILED} with REFUND_DONE, or {@code UNKNOWN} with
     * REFUND_FAILED) from the UNKNOWN + LEG1_DONE pre-state. Same 0-rows idempotency.
     */
    int reconcileToRefunded(@Param("taskId") String taskId,
                            @Param("status") String status,
                            @Param("twoLegState") String twoLegState,
                            @Param("updatedBy") String updatedBy);
}
