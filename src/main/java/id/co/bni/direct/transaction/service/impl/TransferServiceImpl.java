package id.co.bni.direct.transaction.service.impl;

import id.co.bni.direct.transaction.dto.request.TransferRequests.InquiryRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpChallengeRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.SubmitTransferRequest;
import id.co.bni.direct.transaction.dto.response.TransferResponses.InquiryResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.OtpChallengeResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.StageResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.SubmitResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.TaskDetailResponse;
import id.co.bni.direct.transaction.entity.TransferRows.BankLimitRow;
import id.co.bni.direct.transaction.entity.TransferRows.CorpFlagsRow;
import id.co.bni.direct.transaction.entity.TransferRows.MakerRow;
import id.co.bni.direct.transaction.entity.TransferRows.MatrixBandRow;
import id.co.bni.direct.transaction.entity.TransferRows.MatrixSignatureRow;
import id.co.bni.direct.transaction.entity.TransferRows.UsageLimitRow;
import id.co.bni.direct.transaction.entity.TransferRows.WorkflowUserRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.exception.NotFoundException;
import id.co.bni.direct.transaction.integration.AccountNameClient;
import id.co.bni.direct.transaction.integration.UmasAuthenticatorClient;
import id.co.bni.direct.transaction.dto.response.TransferResponses.StageActionResponse;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.ExecutionService;
import id.co.bni.direct.transaction.service.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Transfer ke BNI (MNU_GCME_050200) maker flow.
 *
 * <p>The submit pipeline runs the checks in the order the legacy application runs them -
 * role, source account, service resolution, the limit ladder, maker scheme, OTP, then the
 * approval matrix - and each refusal is a 422 with a machine code the FE switches on. The
 * whole submit is one transaction: the TRX_REF_NO_SEQUENCE row lock taken while minting
 * the reference number is released by the same commit that makes the task visible.
 *
 * <p>Limit USAGE is read-only tonight: the ladder checks {@code usage + amount <= max}
 * but increments nothing. Legacy increments usage (and writes BASE_FT) at RELEASE, which
 * is the next phase's work, alongside approve/reject.
 */
@Service
public class TransferServiceImpl implements TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferServiceImpl.class);

    public static final String MENU_CD = "MNU_GCME_050200";
    /** The FE-facing display name for {@link #MENU_CD}; other menus fall back to the code. */
    public static final String MENU_NAME = "Transfer ke BNI";
    /** In-house transfer to an own (registered) account vs a third-party account. */
    public static final String SRVC_IN_HOUSE_OWN = "GCM_FTR_IH_OWN";
    public static final String SRVC_IN_HOUSE_3RD = "GCM_FTR_IH_3RD";

    private static final DateTimeFormatter REF_NO_PREFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final TransferMapper transferMapper;
    private final TrxTaskMapper trxTaskMapper;
    private final AccountNameClient accountNameClient;
    private final UmasAuthenticatorClient authenticatorClient;
    private final ExecutionService executionService;
    private final ExecutionOutbox executionOutbox;
    private final TransactionTemplate submitTransaction;

    public TransferServiceImpl(TransferMapper transferMapper,
                               TrxTaskMapper trxTaskMapper,
                               AccountNameClient accountNameClient,
                               UmasAuthenticatorClient authenticatorClient,
                               ExecutionService executionService,
                               ExecutionOutbox executionOutbox,
                               PlatformTransactionManager transactionManager) {
        this.transferMapper = transferMapper;
        this.trxTaskMapper = trxTaskMapper;
        this.accountNameClient = accountNameClient;
        this.authenticatorClient = authenticatorClient;
        this.executionService = executionService;
        this.executionOutbox = executionOutbox;
        this.submitTransaction = new TransactionTemplate(transactionManager);
    }

    /** The menu's display name on the wire; only Transfer ke BNI exists this phase. */
    public static String menuName(String menuCd) {
        return MENU_CD.equals(menuCd) ? MENU_NAME : menuCd;
    }

    @Override
    public InquiryResponse inquiry(String companyId, InquiryRequest request) {
        AccountNameClient.AccountName name = accountNameClient.fetchAccountName(request.accountNumber());
        return new InquiryResponse(
                name.accountNumber() != null ? name.accountNumber() : request.accountNumber(),
                name.accountName(), name.currency(), name.status());
    }

    @Override
    public OtpChallengeResponse otpChallenge(String companyId, OtpChallengeRequest request) {
        MakerRow user = transferMapper.findMaker(companyId, request.userId());
        if (user == null) {
            throw new NotFoundException("Pengguna tidak ditemukan.");
        }
        UmasAuthenticatorClient.Challenge challenge =
                authenticatorClient.requestChallenge(request.userId());
        return new OtpChallengeResponse(challenge.challenge(), challenge.verificationId(),
                user.authTypCd());
    }

    /**
     * The workflow write is one transaction end to end (a {@link TransactionTemplate}
     * now, not {@code @Transactional}: the transaction must COMMIT before the execution
     * seam runs, and a self-invoked transactional method cannot express that). The OTP
     * verification hop runs inside it on purpose: verifying first and opening the
     * transaction after would leave a window where a verified OTP is spent on a submit
     * whose writes then fail. The trade is an Oracle connection held across one HTTP call
     * - acceptable for a maker action, and the TRX_REF_NO_SEQUENCE lock is only taken
     * after every check has passed, so the shared counter is never held across a
     * rejection.
     *
     * <p>In sync mode a single-user task is born READY_TO_EXECUTE and handed to the
     * execution seam AFTER that commit - synchronously, so the 201 carries the execution
     * verdict (EXECUTED / FAILED / UNKNOWN) rather than a status the next poll would
     * contradict. In kafka mode it is born QUEUED with its outbox row in the same commit,
     * the seam is NOT called, and the 201 answers QUEUED - the verdict lands on the task
     * (and the completed topic) once the consumer executes it.
     */
    @Override
    public SubmitResponse submit(String companyId, String actor, SubmitTransferRequest request) {
        SubmitResponse submitted = submitTransaction.execute(
                tx -> submitInTransaction(companyId, actor, request));
        if (!"READY_TO_EXECUTE".equals(submitted.status())) {
            return submitted;
        }
        ExecutionService.ExecutionResult executed = executionService.execute(submitted.taskId());
        return new SubmitResponse(submitted.taskId(), submitted.refNo(), executed.status());
    }

    private SubmitResponse submitInTransaction(String companyId, String actor,
                                               SubmitTransferRequest request) {
        BigDecimal amount = request.amount().amount();
        String currency = request.amount().currency();

        // a. The actor must hold a maker workflow role (CRP_USR_MK, CRP_USR_MK_AP_RL, ...).
        MakerRow maker = transferMapper.findMaker(companyId, request.userId());
        if (maker == null || maker.wfRoleCd() == null || !maker.wfRoleCd().contains("MK")) {
            throw new BusinessRuleException("NOT_MAKER",
                    "Anda tidak memiliki kewenangan maker untuk transaksi ini.");
        }

        // b. Source account must be in the maker's account group WITH debit permission.
        if (maker.acctGroupId() == null
                || transferMapper.countDebitAccount(maker.acctGroupId(), request.sourceAccountNo()) == 0) {
            throw new BusinessRuleException("SOURCE_ACCT_FORBIDDEN",
                    "Rekening sumber tidak tersedia untuk didebit oleh pengguna ini.");
        }

        // c. Beneficiary registered in the same chain (any permission) = own transfer.
        boolean ownBeneficiary = maker.acctGroupId() != null
                && transferMapper.countAnyAccount(maker.acctGroupId(), request.beneficiaryAccountNo()) > 0;
        String srvcCd = ownBeneficiary ? SRVC_IN_HOUSE_OWN : SRVC_IN_HOUSE_3RD;

        // d. The limit ladder - every rung only binds when its row exists.
        BankLimitRow bankLimit = transferMapper.findBankLimit(srvcCd, currency);
        if (bankLimit != null && (amount.compareTo(nvl(bankLimit.minAmtLmt())) < 0
                || (bankLimit.maxAmtLmt() != null && amount.compareTo(bankLimit.maxAmtLmt()) > 0))) {
            throw new BusinessRuleException("BANK_LIMIT",
                    "Nominal transaksi berada di luar batas limit bank untuk layanan ini.");
        }

        UsageLimitRow corpLimit = transferMapper.findCorpLimit(companyId, srvcCd, currency);
        if (corpLimit != null && corpLimit.maxAmtLmt() != null
                && nvl(corpLimit.amtLmtUsage()).add(amount).compareTo(corpLimit.maxAmtLmt()) > 0) {
            throw new BusinessRuleException("COMPANY_LIMIT",
                    "Nominal transaksi melebihi sisa limit harian perusahaan.");
        }

        UsageLimitRow groupLimit = transferMapper.findGroupLimit(maker.groupId(), srvcCd, currency);
        if (groupLimit != null && groupLimit.maxAmtLmt() != null
                && nvl(groupLimit.amtLmtUsage()).add(amount).compareTo(groupLimit.maxAmtLmt()) > 0) {
            throw new BusinessRuleException("GROUP_LIMIT",
                    "Nominal transaksi melebihi sisa limit grup pengguna.");
        }

        BigDecimal debitLimit = transferMapper.findAccountDebitLimit(companyId, request.sourceAccountNo());
        if (debitLimit != null && amount.compareTo(debitLimit) > 0) {
            throw new BusinessRuleException("ACCOUNT_LIMIT",
                    "Nominal transaksi melebihi limit debit rekening sumber.");
        }

        // e. The maker's own scheme ceiling, when their level has one for this currency.
        if (maker.aprvLvlCd() != null) {
            BigDecimal makeLimit = transferMapper.findMakerSchemeLimit(companyId, maker.aprvLvlCd(), currency);
            if (makeLimit != null && amount.compareTo(makeLimit) > 0) {
                throw new BusinessRuleException("MAKER_SCHEME_LIMIT",
                        "Nominal transaksi melebihi limit pembuatan transaksi Anda.");
            }
        }

        // f. OTP. A failed verification is a non-2xx from the authenticator = data, and
        // becomes this pipeline's own rejection; only transport failure is a 503.
        UmasAuthenticatorClient.Verification verification = authenticatorClient
                .verifyTransaction(request.userId(), request.otp().challenge(), request.otp().response());
        if (!verification.verified()) {
            throw new BusinessRuleException("OTP_INVALID", "Kode OTP tidak valid atau sudah kedaluwarsa.");
        }

        // h. Cash-lite single-user companies skip the matrix entirely: no stages, the task
        // is immediately ready to execute (execution itself is wired next phase).
        CorpFlagsRow flags = transferMapper.findCorpFlags(companyId);
        boolean singleUser = flags != null
                && "Y".equals(flags.isCashLite()) && "Y".equals(flags.isSingleUser());

        List<MatrixSignatureRow> signatures = List.of();
        if (!singleUser) {
            // g. The approval matrix for this menu+currency decides the stages.
            String masterId = transferMapper.findMatrixMasterId(companyId, MENU_CD, currency);
            if (masterId == null) {
                throw new BusinessRuleException("NO_MATRIX",
                        "Matriks persetujuan untuk menu dan mata uang ini belum diatur.");
            }
            List<MatrixBandRow> bands = transferMapper.findMatrixBands(masterId);
            if (bands.isEmpty()) {
                throw new BusinessRuleException("NO_MATRIX",
                        "Matriks persetujuan untuk menu dan mata uang ini belum diatur.");
            }
            // Bands come ascending by LONG_AMT_LMT (a band's floor is the band below it,
            // per direct-admin's ApprovalMatrixServiceImpl). The first band whose ceiling
            // covers the amount wins; an amount above the top band's ceiling uses the
            // highest band rather than being rejected - the matrix always answers.
            MatrixBandRow band = bands.stream()
                    .filter(b -> b.longAmtLmt() != null && amount.compareTo(b.longAmtLmt()) <= 0)
                    .findFirst()
                    .orElse(bands.get(bands.size() - 1));
            signatures = transferMapper.findBandSignatures(band.id());
        }

        // h2. CANDIDATE MATERIALIZATION - the eligible-user set is FROZEN at submit (see
        // V2's comments). Computed BEFORE the reference-number lock so a refusal here
        // never holds the shared counter, and so a task that nobody could ever approve or
        // release is refused outright instead of stranding in the inbox.
        List<List<WorkflowUserRow>> approvalCandidates = List.of();
        List<WorkflowUserRow> releaseCandidates = List.of();
        if (!singleUser) {
            List<WorkflowUserRow> users = transferMapper.findWorkflowUsers(companyId);
            approvalCandidates = new ArrayList<>();
            for (MatrixSignatureRow signature : signatures) {
                List<WorkflowUserRow> candidates =
                        selectApprovalCandidates(users, signature, maker);
                if (candidates.isEmpty()) {
                    throw new BusinessRuleException("NO_ELIGIBLE_APPROVER",
                            "Tidak ada pengguna yang berwenang menyetujui transaksi ini. "
                                    + "Periksa pengaturan matriks persetujuan dan peran pengguna.");
                }
                approvalCandidates.add(candidates);
            }
            releaseCandidates = selectReleaseCandidates(users);
            if (releaseCandidates.isEmpty()) {
                throw new BusinessRuleException("NO_ELIGIBLE_RELEASER",
                        "Tidak ada pengguna yang berwenang melepas transaksi ini. "
                                + "Periksa peran releaser pada perusahaan Anda.");
            }
        }

        // i. Reference number off the legacy shared counter, locked FOR UPDATE.
        String refNo = nextRefNo(transferMapper, srvcCd, companyId);

        // j. The task, its stages, and the SUBMIT action row.
        String taskId = newId();
        int approvalStages = signatures.size();
        String status;
        Integer currentStageSeq;
        if (singleUser) {
            // No workflow to run: straight to execution - in-request (sync) or via the
            // event pipeline (kafka), where the outbox row is written below.
            status = executionOutbox.isKafkaMode() ? "QUEUED" : "READY_TO_EXECUTE";
            currentStageSeq = null;
        } else if (approvalStages > 0) {
            status = "PENDING_APPROVAL";
            currentStageSeq = 1;
        } else {
            // A band with no signature rows: nothing to approve, straight to release.
            status = "PENDING_RELEASE";
            currentStageSeq = 1;
        }

        trxTaskMapper.insertTask(new TrxTaskRows.TaskInsert(
                taskId, companyId, MENU_CD, srvcCd, refNo, status, currentStageSeq,
                request.sourceAccountNo(), request.beneficiaryAccountNo(), request.beneficiaryName(),
                amount, currency, request.remark(), null, null,
                "IMMEDIATE",
                maker.corpUserId(),
                maker.userName() != null ? maker.userName() : maker.userId(),
                singleUser ? "Y" : "N",
                actor));

        if (!singleUser) {
            int seq = 1;
            for (MatrixSignatureRow signature : signatures) {
                trxTaskMapper.insertStage(new TrxTaskRows.StageInsert(
                        newId(), taskId, seq, "APPROVAL",
                        signature.aprvLvlCd(),
                        signature.usrGrpOpt(),
                        signature.corpUsrGrpId(),
                        signature.noUsr() != null && signature.noUsr() > 0 ? signature.noUsr() : 1,
                        seq == 1 ? "ACTIVE" : "WAITING",
                        actor));
                insertCandidates(taskId, seq, approvalCandidates.get(seq - 1), actor);
                seq++;
            }
            // The final RELEASE stage every non-single-user task carries. The candidate
            // rows ARE the enforcement now: eligibility at approve time reads only the
            // frozen set, never the group option again.
            trxTaskMapper.insertStage(new TrxTaskRows.StageInsert(
                    newId(), taskId, seq, "RELEASE", null, "1", null, 1,
                    approvalStages == 0 ? "ACTIVE" : "WAITING",
                    actor));
            insertCandidates(taskId, seq, releaseCandidates, actor);
        }

        trxTaskMapper.insertAction(new TrxTaskRows.ActionInsert(
                newId(), taskId, null, "SUBMIT",
                maker.corpUserId(),
                maker.userName() != null ? maker.userName() : maker.userId(),
                maker.groupId(),
                verification.verificationId(),
                null));

        // Kafka mode: the QUEUED task and its EXECUTION_REQUESTED event are one commit.
        if ("QUEUED".equals(status)) {
            executionOutbox.enqueueExecutionRequested(taskId, refNo, companyId);
        }

        log.info("Transfer task submitted: taskId={} refNo={} status={} stages={}",
                taskId, refNo, status, singleUser ? 0 : approvalStages + 1);

        // A READY_TO_EXECUTE task is handed to the execution seam by submit() AFTER this
        // transaction commits - the seam opens transactions of its own and must never run
        // against an uncommitted claim of the same row.
        return new SubmitResponse(taskId, refNo, status);
    }

    /**
     * The frozen candidate set of one APPROVAL stage. Filters, in order: the workflow
     * role must contain AP; the stage's level must match when it names one (null = any
     * level); the user's group must satisfy the stage's USR_GRP_OPT; and the MAKER IS
     * EXCLUDED - a maker never approves their own task, whatever roles they hold.
     * (Legacy's admin module lets a checker approve their own maintenance request; for
     * transactions the safer reading is exclusion - flagged for business confirmation.)
     */
    private List<WorkflowUserRow> selectApprovalCandidates(List<WorkflowUserRow> users,
                                                           MatrixSignatureRow signature,
                                                           MakerRow maker) {
        return dedupeByUserId(users.stream()
                .filter(u -> u.wfRoleCd() != null && u.wfRoleCd().contains("AP"))
                .filter(u -> signature.aprvLvlCd() == null
                        || signature.aprvLvlCd().equals(u.aprvLvlCd()))
                .filter(u -> matchesGroupOption(signature.usrGrpOpt(),
                        signature.corpUsrGrpId(), maker.groupId(), u.groupId()))
                .filter(u -> !u.corpUserId().equals(maker.corpUserId()))
                .toList());
    }

    /**
     * RELEASE candidates: any user whose workflow role contains RL - any group, any
     * level, and the maker is NOT excluded (a maker holding RL may release their own
     * task once others approved it).
     */
    private List<WorkflowUserRow> selectReleaseCandidates(List<WorkflowUserRow> users) {
        return dedupeByUserId(users.stream()
                .filter(u -> u.wfRoleCd() != null && u.wfRoleCd().contains("RL"))
                .toList());
    }

    /**
     * '1' any group, '2' SAME group as the maker, '3' DIFFERENT group, '4' the stage's
     * specific group. An unknown or null option is read as ANY - refusing the submit over
     * a dirty legacy option digit would strand valid matrices.
     */
    private static boolean matchesGroupOption(String usrGrpOpt, String specificGroupId,
                                              String makerGroupId, String userGroupId) {
        if ("2".equals(usrGrpOpt)) {
            return userGroupId != null && userGroupId.equals(makerGroupId);
        }
        if ("3".equals(usrGrpOpt)) {
            return userGroupId == null || !userGroupId.equals(makerGroupId);
        }
        if ("4".equals(usrGrpOpt)) {
            return userGroupId != null && userGroupId.equals(specificGroupId);
        }
        return true;
    }

    /** UK_TRX_TASK_CAND is per login id; legacy duplicates keep their first row. */
    private static List<WorkflowUserRow> dedupeByUserId(List<WorkflowUserRow> users) {
        Map<String, WorkflowUserRow> byUserId = new LinkedHashMap<>();
        for (WorkflowUserRow user : users) {
            byUserId.putIfAbsent(user.userId(), user);
        }
        return List.copyOf(byUserId.values());
    }

    private void insertCandidates(String taskId, int stageSeq,
                                  List<WorkflowUserRow> candidates, String actor) {
        for (WorkflowUserRow candidate : candidates) {
            trxTaskMapper.insertCandidate(new TrxTaskRows.CandidateInsert(
                    newId(), taskId, stageSeq,
                    candidate.userId(),
                    candidate.corpUserId(),
                    candidate.userName() != null ? candidate.userName() : candidate.userId(),
                    candidate.groupId(),
                    actor));
        }
    }

    @Override
    public TaskDetailResponse detail(String companyId, String taskId, String userId) {
        TrxTaskRows.TaskRow task = trxTaskMapper.findTask(companyId, taskId);
        if (task == null) {
            throw new NotFoundException("Transaksi tidak ditemukan.");
        }
        // The action history, grouped under its stage. SUBMIT rows carry a null stageSeq
        // (the submit is not an act inside a stage) and stay off the stage list.
        Map<Integer, List<StageActionResponse>> actionsByStage = trxTaskMapper.findActions(taskId).stream()
                .filter(a -> a.stageSeq() != null)
                .collect(Collectors.groupingBy(TrxTaskRows.ActionRow::stageSeq,
                        Collectors.mapping(a -> new StageActionResponse(
                                        a.actorUserName(), a.action(), a.note(), a.createdDt()),
                                Collectors.toList())));
        List<StageResponse> stages = trxTaskMapper.findStages(taskId).stream()
                .map(s -> new StageResponse(s.seqNo(), s.stageType(), s.aprvLvlCd(),
                        s.usrGrpOpt(), s.requiredCount(), s.completedCount(), s.status(),
                        actionsByStage.getOrDefault(s.seqNo(), List.of())))
                .toList();
        return new TaskDetailResponse(task.id(), task.refNo(), menuName(task.menuCd()),
                task.status(), task.trxAmt(), task.trxCcyCd(), task.remAcctNo(),
                task.benAcctNo(), task.benAcctNm(), task.remark1(), task.makerUserName(),
                task.createdDt(), stages,
                task.coreJournal(), task.trxRefNo(), task.executedDt());
    }

    /**
     * 20 digits: yyyyMMddHHmmss + a 6-digit counter shared with the legacy application
     * through TRX_REF_NO_SEQUENCE. The row is locked FOR UPDATE inside the submit
     * transaction, so two makers - or a maker here and one on the legacy screens - never
     * mint the same number. A (SERVICE_CD, CORP_ID) pair with no row yet gets one starting
     * at 1; the counter is taken modulo 1,000,000 to keep the 6-digit shape (legacy's live
     * counters, e.g. 228540 in VIRTUAL_ACCOUNT_FT.REF_NO, are 6-digit too).
     *
     * <p>Static and mapper-parameterized because the EXECUTION phase mints from the same
     * generator (BASE_FT.TRX_REF_NO) - one implementation, two callers, one counter.
     */
    static String nextRefNo(TransferMapper transferMapper, String srvcCd, String corpId) {
        Long current = transferMapper.lockRefNoValue(srvcCd, corpId);
        long next;
        if (current == null) {
            next = 1L;
            transferMapper.insertRefNoSeq(srvcCd, corpId, next);
        } else {
            next = current + 1;
            transferMapper.updateRefNoSeq(srvcCd, corpId, next);
        }
        return LocalDateTime.now().format(REF_NO_PREFIX) + String.format("%06d", next % 1_000_000L);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
