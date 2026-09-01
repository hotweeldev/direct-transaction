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
}
