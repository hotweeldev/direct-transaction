package id.co.bni.direct.transaction.service.impl;

import id.co.bni.direct.transaction.dto.request.TransferRequests.InquiryRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.InterbankInquiryRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpChallengeRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.SubmitTransferRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.VaInquiryRequest;
import id.co.bni.direct.transaction.config.TransferTypeProperties;
import id.co.bni.direct.transaction.dto.response.TransferResponses.BankResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.InquiryResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.InterbankInquiryResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.MethodInfoResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.OtpChallengeResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.StageResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.SubmitResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.TaskDetailResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.VaInquiryResponse;
import id.co.bni.direct.transaction.entity.TransferRows.BankLimitRow;
import id.co.bni.direct.transaction.entity.TransferRows.CorpFlagsRow;
import id.co.bni.direct.transaction.entity.TransferRows.DomBankRow;
import id.co.bni.direct.transaction.entity.TransferRows.MakerRow;
import id.co.bni.direct.transaction.entity.TransferRows.MatrixBandRow;
import id.co.bni.direct.transaction.entity.TransferRows.MatrixSignatureRow;
import id.co.bni.direct.transaction.entity.TransferRows.UsageLimitRow;
import id.co.bni.direct.transaction.entity.TransferRows.WorkflowUserRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.exception.NotFoundException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import id.co.bni.direct.transaction.exception.ServiceUnavailableException;
import id.co.bni.direct.transaction.integration.AccountNameClient;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.integration.UmasAuthenticatorClient;
import id.co.bni.direct.transaction.dto.response.TransferResponses.StageActionResponse;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.ExecutionService;
import id.co.bni.direct.transaction.service.TransferService;
import id.co.bni.direct.transaction.service.AmountRules;
import id.co.bni.direct.transaction.service.TransferType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
    /**
     * Transfer ke Bank Lain - the legacy menu the LLG/RTGS types belong to (canonical
     * code per the legacy menu catalog: 050300 between 050200 Transfer ke Rekening BNI
     * and 050400 Transfer Internasional). Its approval matrix is keyed on this code, so
     * a company must have a CORP_APRV_MTRX_MSTR row for it before domestic submits pass.
     */
    public static final String MENU_CD_BANK_LAIN = "MNU_GCME_050300";
    public static final String MENU_NAME_BANK_LAIN = "Transfer ke Bank Lain";
    /**
     * Transfer ke Virtual Account (P3). The legacy menu catalog has no menu of its own
     * for PAYING a VA: the VA name inquiry is registered under Transfer ke Rekening BNI
     * (API_SRVC "Inquiry InHouse and VA Beneficiary Name" -> MNU_GCME_050200), and the
     * MNU_GCME_4001xx menus are the VA OWNER's management/report screens, not payment.
     * So a VA payment books under the in-house menu code - that menu's approval matrix
     * applies - while the legacy "VA Billing Single" service code
     * ({@link TransferType#SRVC_VA}) carries its own limit rows. The display name is
     * resolved off the service code, see {@link #menuName(String, String)}.
     */
    public static final String MENU_CD_VA = MENU_CD;
    public static final String MENU_NAME_VA = "Transfer ke Virtual Account";
    /**
     * The legacy SYS_PARAM row "Virtual Account Format": the regex a VA number must
     * match (e.g. {@code 8[0-9]{15}|988[0-9]{13}|...}). Read at submit and inquiry so
     * the accepted formats stay a parameter, not code; {@link #VA_FALLBACK_FORMAT}
     * binds only when the row is missing or its value does not compile.
     */
    public static final String SYS_PARAM_VA_FORMAT = "SYS_PARAM_1007";
    private static final Pattern VA_FALLBACK_FORMAT = Pattern.compile("[0-9]{10,20}");
    /** In-house transfer to an own (registered) account vs a third-party account. */
    public static final String SRVC_IN_HOUSE_OWN = "GCM_FTR_IH_OWN";
    public static final String SRVC_IN_HOUSE_3RD = "GCM_FTR_IH_3RD";
    /** The legacy SYS_PARAM rows carrying each domestic method's display parameters. */
    public static final String SYS_PARAM_LLG = "SYS_PARAM_TRF_SME_LLG";
    public static final String SYS_PARAM_RTGS = "SYS_PARAM_TRF_SME_RTGS";
    public static final String SYS_PARAM_ONLINE = "SYS_PARAM_TRF_SME_ONLINE";
    /**
     * Core banking rate type 02 = Regular (counter rate) - the P5 default. Kurs khusus
     * (SmartForex) is task 5.2, blocked(env); until then the FE's special-rate option
     * stays disabled and every cross submit books at a regular-type rate.
     */
    public static final String RATE_TYPE_REGULAR = "02";
    /** trxPBI unit code sent on the underlying check; the DEV samples use "01". */
    private static final String UNDERLYING_UNIT_CODE = "01";

    private static final DateTimeFormatter REF_NO_PREFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final TransferMapper transferMapper;
    private final TrxTaskMapper trxTaskMapper;
    private final AccountNameClient accountNameClient;
    private final CoreTransferClient coreTransferClient;
    private final UmasAuthenticatorClient authenticatorClient;
    private final ExecutionService executionService;
    private final ExecutionOutbox executionOutbox;
    private final TransferTypeProperties transferTypeProperties;
    private final TransactionTemplate submitTransaction;

    public TransferServiceImpl(TransferMapper transferMapper,
                               TrxTaskMapper trxTaskMapper,
                               AccountNameClient accountNameClient,
                               CoreTransferClient coreTransferClient,
                               UmasAuthenticatorClient authenticatorClient,
                               ExecutionService executionService,
                               ExecutionOutbox executionOutbox,
                               TransferTypeProperties transferTypeProperties,
                               PlatformTransactionManager transactionManager) {
        this.transferMapper = transferMapper;
        this.trxTaskMapper = trxTaskMapper;
        this.accountNameClient = accountNameClient;
        this.coreTransferClient = coreTransferClient;
        this.authenticatorClient = authenticatorClient;
        this.executionService = executionService;
        this.executionOutbox = executionOutbox;
        this.transferTypeProperties = transferTypeProperties;
        this.submitTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * The display name off the menu AND the service: a VA payment shares the in-house
     * menu code (see {@link #MENU_CD_VA}) and is told apart by its service code.
     */
    public static String menuName(String menuCd, String srvcCd) {
        if (TransferType.SRVC_VA.equals(srvcCd)) {
            return MENU_NAME_VA;
        }
        return menuName(menuCd);
    }

    /** The menu's display name on the wire; unknown menus fall back to the code. */
    public static String menuName(String menuCd) {
        if (MENU_CD.equals(menuCd)) {
            return MENU_NAME;
        }
        if (MENU_CD_BANK_LAIN.equals(menuCd)) {
            return MENU_NAME_BANK_LAIN;
        }
        return menuCd;
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
        return new SubmitResponse(submitted.taskId(), submitted.refNo(), executed.status(),
                submitted.advisoryMessage());
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

        // c. The product. BNI (P0) resolves own- vs third-party in-house off the account
        // chain; LLG/RTGS (P1, Transfer ke Bank Lain) each pin one domestic service code
        // - the beneficiary lives at another bank, so the own/3rd probe does not apply.
        // Domestic submits are shape-checked here (bank row, per-method mandatory
        // fields) BEFORE any limit work, and carry a flat fee: what the limit ladder
        // must validate is the DEBITED total, amount + fee (legacy's nominalPenarikan =
        // nominalDikirim + biaya). Fee is zero for in-house, so P0 math is unchanged.
        TransferType type = parseType(request.transferType());
        // 5.6: the velis decimal rules bind every submit - the credit amount here, the
        // debit amount inside resolveMultiCurrency once the source currency is known.
        AmountRules.validateScale(amount, currency, "Nominal transfer");
        TrxTaskRows.DomesticInsert domestic = null;
        TrxTaskRows.CrossInsert cross = null;
        String srvcCd;
        String menuCd;
        if (type.isVirtualAccount()) {
            // P3: the VA number rides BEN_ACCT_NO, the billing name BEN_ACCT_NM; the flat
            // VA fee travels on the same FEE_AMT the domestic types use, so the ladder,
            // execution and the detail screen all read one column. No own/3rd probe: a
            // VA is never one of the company's own accounts.
            domestic = resolveVirtualAccount(request, currency);
            srvcCd = TransferType.SRVC_VA;
            menuCd = MENU_CD_VA;
        } else if (type.isDomestic()) {
            domestic = resolveDomestic(type, request, currency);
            // P6: a valas source account on LLG/RTGS freezes the same V7 block P5 uses;
            // execution then routes through a simsem account in two legs.
            cross = resolveDomesticCross(type, request, amount);
            srvcCd = switch (type) {
                case LLG -> TransferType.SRVC_DOM_LLG;
                case RTGS -> TransferType.SRVC_DOM_RTGS;
                default -> TransferType.SRVC_DOM_ONLINE;
            };
            menuCd = MENU_CD_BANK_LAIN;
        } else {
            boolean ownBeneficiary = maker.acctGroupId() != null
                    && transferMapper.countAnyAccount(maker.acctGroupId(), request.beneficiaryAccountNo()) > 0;
            srvcCd = ownBeneficiary ? SRVC_IN_HOUSE_OWN : SRVC_IN_HOUSE_3RD;
            menuCd = MENU_CD;
            cross = resolveMultiCurrency(companyId, request, amount, currency);
        }

        // The DEBIT side is what the limits guard (P5): for a cross transfer that is
        // the customer-typed debit amount in the SOURCE currency; everywhere else the
        // credit amount (+ the flat domestic fee) in the wire currency - P0/P1 math
        // unchanged.
        String ladderCcy = cross != null && cross.debitCcyCd() != null
                ? cross.debitCcyCd() : currency;
        BigDecimal ladderAmt = cross != null && cross.debitAmt() != null
                ? cross.debitAmt() : amount;
        // P6: on a cross Bank Lain transfer the flat IDR fee is INSIDE the leg-1 credit
        // the customer's debit amount pays for (see SimsemRefunder.leg1Credit), so the
        // ladder sees the debit amount alone - an IDR fee cannot be added to a valas
        // figure. Single-leg domestic math (amount + fee) is unchanged.
        BigDecimal totalDebit = ladderAmt.add(domestic != null && cross == null
                ? nvl(domestic.feeAmt()) : BigDecimal.ZERO);

        // d. The limit ladder - every rung only binds when its row exists, and every
        // rung sees the debited total.
        BankLimitRow bankLimit = transferMapper.findBankLimit(srvcCd, ladderCcy);
        if (bankLimit != null && (totalDebit.compareTo(nvl(bankLimit.minAmtLmt())) < 0
                || (bankLimit.maxAmtLmt() != null && totalDebit.compareTo(bankLimit.maxAmtLmt()) > 0))) {
            throw new BusinessRuleException("BANK_LIMIT",
                    "Nominal transaksi berada di luar batas limit bank untuk layanan ini.");
        }

        UsageLimitRow corpLimit = transferMapper.findCorpLimit(companyId, srvcCd, ladderCcy);
        if (corpLimit != null && corpLimit.maxAmtLmt() != null
                && nvl(corpLimit.amtLmtUsage()).add(totalDebit).compareTo(corpLimit.maxAmtLmt()) > 0) {
            throw new BusinessRuleException("COMPANY_LIMIT",
                    "Nominal transaksi melebihi sisa limit harian perusahaan.");
        }

        UsageLimitRow groupLimit = transferMapper.findGroupLimit(maker.groupId(), srvcCd, ladderCcy);
        if (groupLimit != null && groupLimit.maxAmtLmt() != null
                && nvl(groupLimit.amtLmtUsage()).add(totalDebit).compareTo(groupLimit.maxAmtLmt()) > 0) {
            throw new BusinessRuleException("GROUP_LIMIT",
                    "Nominal transaksi melebihi sisa limit grup pengguna.");
        }

        BigDecimal debitLimit = transferMapper.findAccountDebitLimit(companyId, request.sourceAccountNo());
        if (debitLimit != null && totalDebit.compareTo(debitLimit) > 0) {
            throw new BusinessRuleException("ACCOUNT_LIMIT",
                    "Nominal transaksi melebihi limit debit rekening sumber.");
        }

        // e. The maker's own scheme ceiling, when their level has one for this currency.
        if (maker.aprvLvlCd() != null) {
            BigDecimal makeLimit = transferMapper.findMakerSchemeLimit(companyId, maker.aprvLvlCd(), ladderCcy);
            if (makeLimit != null && totalDebit.compareTo(makeLimit) > 0) {
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
            // g. The approval matrix for this menu+currency decides the stages. P5:
            // for a cross transfer the matrix is looked for in the DEBIT currency
            // first; a company that only maintains an IDR matrix falls back to it,
            // with the bands compared against the IDR-equivalent BASE_AMT (stated
            // assumption, flagged in the roadmap notes).
            BigDecimal matrixAmt = ladderAmt;
            String masterId = transferMapper.findMatrixMasterId(companyId, menuCd, ladderCcy);
            if (masterId == null && cross != null && !"IDR".equals(ladderCcy)) {
                masterId = transferMapper.findMatrixMasterId(companyId, menuCd, "IDR");
                if (cross.baseAmt() != null) {
                    matrixAmt = cross.baseAmt();
                }
            }
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
            BigDecimal bandAmt = matrixAmt;
            MatrixBandRow band = bands.stream()
                    .filter(b -> b.longAmtLmt() != null && bandAmt.compareTo(b.longAmtLmt()) <= 0)
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
                taskId, companyId, menuCd, srvcCd, refNo, status, currentStageSeq,
                request.sourceAccountNo(), request.beneficiaryAccountNo(), request.beneficiaryName(),
                amount, currency, request.remark(), null, null,
                "IMMEDIATE",
                maker.corpUserId(),
                maker.userName() != null ? maker.userName() : maker.userId(),
                singleUser ? "Y" : "N",
                actor,
                domestic,
                cross,
                type.isVirtualAccount() ? trimTo(request.inquiryRequestId(), 64) : null));

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
        return new SubmitResponse(taskId, refNo, status,
                cross != null ? cross.advisoryMsg() : null);
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
        TransferType type = TransferType.fromServiceCode(task.srvcCd());
        BigDecimal fee = task.feeAmt();
        BigDecimal total = task.trxAmt() == null ? null
                : task.trxAmt().add(fee != null ? fee : BigDecimal.ZERO);
        return new TaskDetailResponse(task.id(), task.refNo(), menuName(task.menuCd(), task.srvcCd()),
                task.status(), task.trxAmt(), task.trxCcyCd(), task.remAcctNo(),
                task.benAcctNo(), task.benAcctNm(), task.remark1(), task.makerUserName(),
                task.createdDt(), stages,
                task.coreJournal(), task.trxRefNo(), task.executedDt(),
                type.name(), task.benDomBnkId(), task.benBnkNm(), task.benBnkCd(),
                task.benBnkBic(), fee, total,
                task.retrievalRefNo(), task.interbankResponseCd(),
                task.debitCcyCd(), task.debitAmt(), task.exchangeRate(),
                task.advisoryMsg(), task.sourceProductType(),
                task.twoLegState(), task.simsemAcctNo(), task.journalNoSimsem());
    }

    @Override
    public List<BankResponse> banks(String companyId, String method) {
        TransferType type = parseType(method);
        if (!type.isDomestic()) {
            throw new BusinessRuleException("TRANSFER_FIELDS_INVALID",
                    "Parameter method harus LLG, RTGS, atau ONLINE.");
        }
        List<DomBankRow> rows = switch (type) {
            case LLG -> transferMapper.findClearingBanks();
            case RTGS -> transferMapper.findRtgsBanks();
            default -> transferMapper.findOnlineBanks();
        };
        // ONLINE routes by the 3-digit interbank code alone - no BIC on that wire.
        return rows.stream()
                .map(bank -> switch (type) {
                    case LLG -> new BankResponse(bank.id(), bank.cd(), bank.nm(), bic8(bank.memberCd()));
                    case RTGS -> new BankResponse(bank.id(), rtgsBic(bank), bank.nm(), rtgsBic(bank));
                    default -> new BankResponse(bank.id(), bank.onlineCd(), bank.nm(), null);
                })
                .toList();
    }

    @Override
    public MethodInfoResponse methodInfo(String companyId, String method) {
        TransferType type = parseType(method);
        if (!type.isDomestic()) {
            throw new BusinessRuleException("TRANSFER_FIELDS_INVALID",
                    "Parameter method harus LLG, RTGS, atau ONLINE.");
        }
        String cd = switch (type) {
            case LLG -> SYS_PARAM_LLG;
            case RTGS -> SYS_PARAM_RTGS;
            default -> SYS_PARAM_ONLINE;
        };
        SysParamMethodInfo info = SysParamMethodInfo.parse(transferMapper.findSysParamValue(cd));
        BigDecimal fee = info.fee();
        if (fee == null) {
            // The param row is missing or its fee token unparsable: fall back to the
            // configured per-method placeholder so the FE always gets a fee to show.
            fee = methodProps(type).getFee();
        }
        return new MethodInfoResponse(type.name(), "IDR",
                info.minAmount(), info.maxAmount(), fee, info.estimatedDuration());
    }

    /**
     * The interbank (ONLINE) beneficiary inquiry - the FE's "Periksa" step before an
     * ONLINE submit (P2). The FE sends the chosen bank row's id; the 3-digit interbank
     * code is re-derived server-side, same rule as submit. The reference pair is minted
     * in the TRX_REF_NO shape but with a random tail - an inquiry must never consume or
     * lock the shared booking counter.
     */
    @Override
    public InterbankInquiryResponse interbankInquiry(String companyId,
                                                     InterbankInquiryRequest request) {
        DomBankRow bank = transferMapper.findDomBank(request.beneficiaryBankId().trim());
        String onlineCd = bank == null ? null : onlineCd(bank);
        if (onlineCd == null) {
            throw new BusinessRuleException("BENEFICIARY_BANK_INVALID",
                    "Bank penerima tidak ditemukan atau tidak dapat dirutekan via transfer online.");
        }
        String refNo = inquiryRefNo();
        CoreTransferClient.InterbankInquiry inquiry = coreTransferClient.inquireInterbank(
                new CoreTransferClient.InterbankInstruction(
                        request.sourceAccountNo(), request.beneficiaryAccountNo(), onlineCd,
                        CoreTransferClient.amountString(request.amount()),
                        refNo, refNo));
        return new InterbankInquiryResponse(
                inquiry.beneficiaryName(),
                inquiry.beneficiaryBankName() != null ? inquiry.beneficiaryBankName() : bank.nm(),
                inquiry.retrievalRefNo());
    }

    /**
     * The Transfer ke Virtual Account billing inquiry (P3) - the FE's "Periksa" step.
     * The VA service keys the inquiry on the paying account, so the caller must hold
     * debit rights on it (the same chain the submit checks); the VA number is validated
     * against the legacy format parameter before any upstream call. The inquiry's
     * {@code inquiryRequestId} comes back for the FE to echo on the submit, together
     * with the configured flat fee so the FE never hardcodes it. The reference is minted
     * in the TRX_REF_NO shape with a random tail - an inquiry never touches the booking
     * counter.
     */
    @Override
    public VaInquiryResponse vaInquiry(String companyId, VaInquiryRequest request) {
        MakerRow user = transferMapper.findMaker(companyId, request.userId());
        if (user == null) {
            throw new NotFoundException("Pengguna tidak ditemukan.");
        }
        if (user.acctGroupId() == null
                || transferMapper.countDebitAccount(user.acctGroupId(), request.sourceAccountNo()) == 0) {
            throw new BusinessRuleException("SOURCE_ACCT_FORBIDDEN",
                    "Rekening sumber tidak tersedia untuk didebit oleh pengguna ini.");
        }
        String vaNumber = validateVaNumber(request.vaNumber());
        CoreTransferClient.VaInquiry inquiry = coreTransferClient.inquireVa(
                inquiryRefNo(), vaNumber, request.sourceAccountNo());
        return new VaInquiryResponse(
                vaNumber,
                inquiry.billingName(),
                inquiry.billedAmount(),
                inquiry.currency() != null ? inquiry.currency() : "IDR",
                inquiry.inquiryRequestId(),
                nvl(transferTypeProperties.getVa().getFee()),
                inquiry.responseCode(),
                inquiry.responseMessage());
    }

    /**
     * The VA block frozen at submit (P3): IDR only (the VA billing wire carries no
     * currency), a VA number in the legacy format, and the flat VA fee on FEE_AMT. The
     * rest of the domestic block stays NULL - there is no destination bank.
     */
    private TrxTaskRows.DomesticInsert resolveVirtualAccount(SubmitTransferRequest request,
                                                             String currency) {
        if (!"IDR".equals(currency)) {
            throw new BusinessRuleException("TRANSFER_FIELDS_INVALID",
                    "Transfer ke Virtual Account hanya tersedia untuk mata uang IDR.");
        }
        validateVaNumber(request.beneficiaryAccountNo());
        BigDecimal fee = transferTypeProperties.getVa().getFee();
        return new TrxTaskRows.DomesticInsert(
                null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                fee != null ? fee : BigDecimal.ZERO);
    }

    /** The trimmed VA number, or the 422 when it does not match the legacy format. */
    private String validateVaNumber(String vaNumber) {
        String va = vaNumber == null ? "" : vaNumber.trim();
        if (!vaFormat().matcher(va).matches()) {
            throw new BusinessRuleException("VA_NUMBER_INVALID",
                    "Nomor Virtual Account tidak sesuai format.");
        }
        return va;
    }

    /** SYS_PARAM_1007 compiled, or the fallback when the row is absent or malformed. */
    private Pattern vaFormat() {
        String regex = transferMapper.findSysParamValue(SYS_PARAM_VA_FORMAT);
        if (regex != null && !regex.isBlank()) {
            try {
                return Pattern.compile("(?:" + regex.trim() + ")");
            } catch (PatternSyntaxException e) {
                log.warn("SYS_PARAM {} does not compile as a regex; using the fallback VA format",
                        SYS_PARAM_VA_FORMAT);
            }
        }
        return VA_FALLBACK_FORMAT;
    }

    /** A 20-digit inquiry reference: timestamp prefix + random tail, never the counter. */
    private static String inquiryRefNo() {
        return LocalDateTime.now().format(REF_NO_PREFIX)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    /** The bank row's 3-digit interbank code, or null when it cannot route ONLINE. */
    private static String onlineCd(DomBankRow bank) {
        String onlineCd = bank.onlineCd() == null ? null : bank.onlineCd().trim();
        return onlineCd == null || onlineCd.isEmpty() ? null : onlineCd;
    }

    /** The per-method config constants (fee, TSA where the protocol has one). */
    private TransferTypeProperties.Method methodProps(TransferType type) {
        return switch (type) {
            case LLG -> transferTypeProperties.getLlg();
            case RTGS -> transferTypeProperties.getRtgs();
            case ONLINE -> transferTypeProperties.getOnline();
            default -> throw new IllegalArgumentException(type.name());
        };
    }

    /** The wire type, or the submit pipeline's own 422 - never a raw 500. */
    private static TransferType parseType(String wire) {
        try {
            return TransferType.fromWire(wire);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("TRANSFER_FIELDS_INVALID", e.getMessage());
        }
    }

    /**
     * The domestic (LLG/RTGS) instruction block, shape-checked and FROZEN at submit.
     * The FE sends only the chosen COM_MT_DOM_BANK id; both routing codes are re-derived
     * server-side from that one row (CD = sandi kliring when 7-digit, MEMBER_CD = BIC)
     * and validated per method - kliring needs the 7-digit sandi AND an 8-char final
     * BIC, RTGS needs a BIC plus the beneficiary postal code the upstream mandates.
     * Residency codes stay in each method's own vocabulary (kliring 1=Penduduk,
     * RTGS 0=Resident) and default to resident. The flat P1 fee is attached here so the
     * ladder and, later, execution both see the same frozen figure.
     */
    private TrxTaskRows.DomesticInsert resolveDomestic(TransferType type,
                                                       SubmitTransferRequest request,
                                                       String currency) {
        if (!"IDR".equals(currency)) {
            throw new BusinessRuleException("TRANSFER_FIELDS_INVALID",
                    "Transfer LLG/RTGS hanya tersedia untuk mata uang IDR.");
        }
        if (isBlank(request.beneficiaryName())) {
            throw new BusinessRuleException("TRANSFER_FIELDS_INVALID",
                    "Nama penerima wajib diisi untuk transfer antar bank.");
        }
        if (isBlank(request.beneficiaryBankId())) {
            throw new BusinessRuleException("BENEFICIARY_BANK_INVALID",
                    "Bank penerima wajib dipilih.");
        }
        DomBankRow bank = transferMapper.findDomBank(request.beneficiaryBankId().trim());
        if (bank == null) {
            throw new BusinessRuleException("BENEFICIARY_BANK_INVALID",
                    "Bank penerima tidak ditemukan atau tidak aktif.");
        }
        if (type == TransferType.ONLINE) {
            String onlineCd = onlineCd(bank);
            if (onlineCd == null) {
                throw new BusinessRuleException("BENEFICIARY_BANK_INVALID",
                        "Bank yang dipilih tidak memiliki kode bank online/ATM Bersama; "
                                + "pilih bank dari daftar Online.");
            }
            // The interbank wire carries no address/postal/residency/beneficiary-type
            // fields: anything the FE sends there is accepted and ignored, exactly as
            // the BNI type ignores the whole domestic block. No BIC either - the
            // 3-digit interbank code alone routes the transfer.
            BigDecimal onlineFee = methodProps(type).getFee();
            return new TrxTaskRows.DomesticInsert(
                    bank.id(), onlineCd, bank.nm(), null,
                    null, null, null, null, null, null, null, null, null, null,
                    onlineFee != null ? onlineFee : BigDecimal.ZERO);
        }
        String bankCode;
        String bic;
        if (type == TransferType.LLG) {
            if (bank.cd() == null || !bank.cd().matches("\\d{7}")) {
                throw new BusinessRuleException("BENEFICIARY_BANK_INVALID",
                        "Bank yang dipilih tidak memiliki sandi kliring 7 digit; pilih bank dari daftar LLG.");
            }
            bic = bic8(bank.memberCd());
            if (bic == null) {
                throw new BusinessRuleException("BENEFICIARY_BANK_INVALID",
                        "Bank yang dipilih tidak memiliki kode BIC; pilih bank dari daftar LLG.");
            }
            bankCode = bank.cd();
        } else {
            bic = rtgsBic(bank);
            if (bic == null) {
                throw new BusinessRuleException("BENEFICIARY_BANK_INVALID",
                        "Bank yang dipilih tidak memiliki kode BIC/RTGS; pilih bank dari daftar RTGS.");
            }
            if (isBlank(request.beneficiaryPostalCode())) {
                throw new BusinessRuleException("TRANSFER_FIELDS_INVALID",
                        "Kode pos penerima wajib diisi untuk transfer RTGS.");
            }
            bankCode = bic;
        }
        TransferTypeProperties.Method method = methodProps(type);
        String residentDefault = type == TransferType.LLG ? "1" : "0";
        return new TrxTaskRows.DomesticInsert(
                bank.id(), bankCode, bank.nm(), bic,
                trimTo(request.beneficiaryAddress1(), 100),
                trimTo(request.beneficiaryAddress2(), 100),
                trimTo(request.beneficiaryAddress3(), 100),
                trimTo(request.beneficiaryPhone(), 20),
                trimTo(request.beneficiaryPostalCode(), 8),
                trimTo(request.beneficiaryIdType(), 4),
                trimTo(request.beneficiaryIdNumber(), 24),
                type == TransferType.LLG
                        ? (isBlank(request.beneficiaryType()) ? "1" : request.beneficiaryType().trim())
                        : null,
                isBlank(request.remitterResidencyCode())
                        ? residentDefault : request.remitterResidencyCode().trim(),
                isBlank(request.beneficiaryResidencyCode())
                        ? residentDefault : request.beneficiaryResidencyCode().trim(),
                method.getFee() != null ? method.getFee() : BigDecimal.ZERO);
    }

    /**
     * P6: the cross-currency resolution of a Transfer ke Bank Lain submit. Kliring and
     * RTGS are IDR-only wires, so the CREDIT stays IDR (resolveDomestic already refused
     * anything else); what may be valas is the SOURCE account, and then execution books
     * the transfer in two legs through a simsem account (DepTransferCross into it, kliring
     * or RTGS out of it).
     *
     * <p>Engaged, like the P5 path, only by an explicit multi-currency field
     * ({@code debitAmount} or {@code rateType}) - the FE knows the source account's
     * currency from its account list and sends the debit amount whenever it is not IDR.
     * A plain IDR submit never gains the AccountShortDetails probe it did not have. Once
     * engaged the probe is mandatory (503 when it does not answer); a source that turns
     * out to be IDR is the ordinary single-leg shape and the debit fields are ignored.
     *
     * <p>Rules: {@code debitAmount} is the customer-typed amount in the source currency
     * and must cover the outward amount PLUS the flat fee at the rate the FE displayed -
     * that IDR total is what leg 1 credits the simsem account with, and it is never
     * re-quoted server-side (no rate call: the credit side is IDR, so the P5 rule
     * "credit ccy IDR -> baseAmount = credit amount" applies and BASE_AMT freezes the
     * outward amount). No trxPBI gate: that corridor is IDR-to-valas, and here the valas
     * side is the source. ONLINE (interbank switch) has no simsem route and stays IDR
     * source only.
     */
    private TrxTaskRows.CrossInsert resolveDomesticCross(TransferType type,
                                                         SubmitTransferRequest request,
                                                         BigDecimal creditAmount) {
        boolean engaged = request.debitAmount() != null || !isBlank(request.rateType());
        if (!engaged) {
            return null;
        }
        AccountNameClient.ShortDetails source =
                accountNameClient.fetchShortDetails(request.sourceAccountNo());
        String sourceCcy = upper(source.currency());
        if (sourceCcy == null || "IDR".equals(sourceCcy)) {
            // Same currency: the P1/P2 shape, nothing frozen.
            return null;
        }
        if (type == TransferType.ONLINE) {
            throw new BusinessRuleException("TRANSFER_FIELDS_INVALID",
                    "Transfer Online/ATM Bersama hanya tersedia dari rekening sumber IDR; "
                            + "gunakan LLG atau RTGS untuk rekening valas.");
        }
        BigDecimal debitAmt = request.debitAmount();
        if (debitAmt == null) {
            throw new BusinessRuleException("TRANSFER_FIELDS_INVALID",
                    "Nominal debit wajib diisi untuk transfer antar mata uang.");
        }
        AmountRules.validateScale(debitAmt, sourceCcy, "Nominal debit");
        String rateType = isBlank(request.rateType())
                ? RATE_TYPE_REGULAR : request.rateType().trim();
        String productType = source.productType() != null && !source.productType().isBlank()
                ? source.productType().trim() : null;
        return new TrxTaskRows.CrossInsert(
                sourceCcy,
                debitAmt,
                null,
                creditAmount,
                rateType,
                productType,
                null, null, null, null, null, null);
    }

    /**
     * The P5 multi-currency resolution of a Transfer ke BNI submit (task 5.4), run in
     * the List-Integration order: source-account probe -> trxPBI underlying check
     * (IDR->valas) -> rate -> the frozen V7 block. Answers null for the plain P0 shape
     * (IDR->IDR wire with no multi-currency field), so that path never gains an
     * upstream call it did not have.
     *
     * <p>Rules (velis Fund Transfer sheet + stated assumptions, flagged in the roadmap
     * notes): {@code amount} is the CREDIT amount - when the FE sends
     * {@code beneficiaryCurrency} (from its inquiry step) it must equal
     * {@code amount.currency}; the source account's currency comes from the
     * AccountShortDetails probe, and the transfer is CROSS when the two differ. A cross
     * submit requires the customer-typed {@code debitAmount}. The baseAmount is NEVER
     * taken from the FE: credit ccy IDR -> credit amount; debit ccy IDR -> debit
     * amount; else debitAmount x the debit currency's buy rate (rateType, default 02
     * Regular), rounded to the IDR decimal rule (0 dp).
     */
    private TrxTaskRows.CrossInsert resolveMultiCurrency(String companyId,
                                                         SubmitTransferRequest request,
                                                         BigDecimal amount,
                                                         String currency) {
        String beneficiaryCcy = upper(request.beneficiaryCurrency());
        boolean engaged = beneficiaryCcy != null
                || request.debitAmount() != null
                || !isBlank(request.rateType())
                || !"IDR".equals(currency)
                || !isBlank(request.underlyingDocType())
                || !isBlank(request.underlyingDocNumber());
        if (!engaged) {
            return null;
        }
        String creditCcy = currency;
        if (beneficiaryCcy != null && !beneficiaryCcy.equals(creditCcy)) {
            // amount is the CREDIT amount, so its currency must be the beneficiary's.
            throw new BusinessRuleException("TRANSFER_FIELDS_INVALID",
                    "Nominal transfer adalah nominal kredit: mata uang nominal ("
                            + creditCcy + ") harus sama dengan mata uang rekening penerima ("
                            + beneficiaryCcy + ").");
        }

        // The source account as core banking knows it: currency + product type. LON
        // routes execution to LoanTransfer. Mandatory once this path is engaged - a
        // cross corridor is never guessed from a hop that did not answer (503).
        AccountNameClient.ShortDetails source =
                accountNameClient.fetchShortDetails(request.sourceAccountNo());
        String sourceCcy = upper(source.currency());
        String productType = source.productType() != null && !source.productType().isBlank()
                ? source.productType().trim() : null;
        if (sourceCcy == null) {
            // Stated assumption: an upstream row without a currency cannot prove a
            // cross corridor; read as same-currency rather than refused.
            sourceCcy = creditCcy;
        }

        boolean isCross = !sourceCcy.equals(creditCcy);
        BigDecimal debitAmt = request.debitAmount();
        if (isCross && debitAmt == null) {
            throw new BusinessRuleException("TRANSFER_FIELDS_INVALID",
                    "Nominal debit wajib diisi untuk transfer antar mata uang.");
        }
        if (!isCross) {
            // Same currency: one amount, one currency - P0 semantics, nothing frozen.
            debitAmt = null;
        }
        if (debitAmt != null) {
            AmountRules.validateScale(debitAmt, sourceCcy, "Nominal debit");
        }

        String rateType = isBlank(request.rateType())
                ? RATE_TYPE_REGULAR : request.rateType().trim();

        // The velis baseAmount formula - computed server-side, frozen at submit.
        BigDecimal baseAmt = null;
        BigDecimal exchangeRate = null;
        if (isCross) {
            if ("IDR".equals(creditCcy)) {
                baseAmt = amount;
            } else if ("IDR".equals(sourceCcy)) {
                baseAmt = debitAmt;
            } else {
                exchangeRate = buyRate(rateType, sourceCcy);
                baseAmt = AmountRules.round(debitAmt.multiply(exchangeRate), "IDR");
            }
        }

        // trxPBI: mandatory on the IDR->valas purchase corridor, BEFORE any task exists.
        String advisory = null;
        if (isCross && "IDR".equals(sourceCcy)) {
            advisory = underlyingGate(companyId, request, amount, creditCcy, debitAmt);
        }

        if (!isCross && !"LON".equalsIgnoreCase(productType)
                && isBlank(request.underlyingDocType())
                && isBlank(request.underlyingDocNumber())) {
            // Same currency, ordinary deposit source: nothing to freeze.
            return null;
        }
        return new TrxTaskRows.CrossInsert(
                isCross ? sourceCcy : null,
                debitAmt,
                exchangeRate,
                baseAmt,
                isCross ? rateType : null,
                productType,
                advisory,
                trimTo(request.underlyingDocType(), 40),
                trimTo(request.underlyingDocNumber(), 40),
                trimTo(request.underlyingDocName(), 100),
                request.underlyingDocAmount(),
                trimTo(request.underlyingDocExpiry(), 10));
    }

    /**
     * The trxPBI gate (P5). Blocking rule (stated assumption, flagged in the roadmap):
     * {@code underlyingRequired} with no declared underlying document is a 422
     * {@code UNDERLYING_REQUIRED} carrying the upstream message; {@code statementRequired}
     * alone never blocks - the advisory is frozen on the task and returned on the 201.
     * The check is mandatory for this corridor, so an unreachable check is the client's
     * 503, never a pass (fail-closed).
     */
    private String underlyingGate(String companyId, SubmitTransferRequest request,
                                  BigDecimal creditAmount, String creditCcy,
                                  BigDecimal debitAmt) {
        String cif = transferMapper.findCorpHostCif(companyId);
        if (cif == null || cif.isBlank()) {
            throw new ServiceUnavailableException(
                    "Data CIF perusahaan tidak tersedia untuk pemeriksaan underlying.");
        }
        // The effective rate of THIS instruction: IDR paid per unit of valas bought.
        BigDecimal rate = debitAmt.divide(creditAmount, 6, RoundingMode.HALF_UP);
        CoreTransferClient.UnderlyingCheck check = coreTransferClient.checkUnderlying(
                new CoreTransferClient.UnderlyingCheckInstruction(
                        cif.trim(),
                        request.sourceAccountNo(),
                        "IDR",
                        creditCcy,
                        CoreTransferClient.amountString(creditAmount),
                        rate.toPlainString(),
                        UNDERLYING_UNIT_CODE,
                        LocalDate.now().toString(),
                        null,
                        trimTo(request.underlyingDocType(), 40),
                        trimTo(!isBlank(request.underlyingDocName())
                                ? request.underlyingDocName()
                                : request.underlyingDocNumber(), 100)));
        boolean docDeclared = !isBlank(request.underlyingDocType())
                || !isBlank(request.underlyingDocNumber());
        if (check.underlyingRequired() && !docDeclared) {
            throw new BusinessRuleException("UNDERLYING_REQUIRED",
                    !isBlank(check.message())
                            ? check.message()
                            : "Transaksi ini memerlukan dokumen underlying sesuai "
                                    + "ketentuan Bank Indonesia.");
        }
        if (!check.statementRequired()) {
            return null;
        }
        return !isBlank(check.message())
                ? trimTo(check.message(), 400)
                : "Nasabah wajib melengkapi surat pernyataan sesuai ketentuan Bank Indonesia.";
    }

    /** The debit currency's buy rate against IDR off the rates endpoint; 422 when absent. */
    private BigDecimal buyRate(String rateType, String debitCcy) {
        return coreTransferClient.fetchRates(rateType).stream()
                .filter(r -> debitCcy.equalsIgnoreCase(r.currency()))
                .map(TransferServiceImpl::effectiveBuyRate)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("RATE_UNAVAILABLE",
                        "Kurs untuk mata uang " + debitCcy + " tidak tersedia."));
    }

    /** buyRate scaled by the quotation units ("01" = per unit; "100" = per hundred). */
    private static BigDecimal effectiveBuyRate(CoreTransferClient.Rate rate) {
        try {
            BigDecimal buy = new BigDecimal(rate.buyRate().trim());
            BigDecimal units = rate.units() == null || rate.units().isBlank()
                    ? BigDecimal.ONE : new BigDecimal(rate.units().trim());
            if (units.compareTo(BigDecimal.ZERO) <= 0) {
                units = BigDecimal.ONE;
            }
            return units.compareTo(BigDecimal.ONE) == 0
                    ? buy : buy.divide(units, 7, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    /** An 8-char BIC, trimming an 11-char branch-qualified one; null when unusable. */
    private static String bic8(String memberCd) {
        if (memberCd == null) {
            return null;
        }
        String bic = memberCd.trim();
        if (bic.matches("[A-Za-z0-9]{11}")) {
            return bic.substring(0, 8);
        }
        return bic.matches("[A-Za-z0-9]{8}") ? bic : null;
    }

    /** The RTGS routing code: MEMBER_CD where present, else a BIC-shaped CD. */
    private static String rtgsBic(DomBankRow bank) {
        String fromMember = bank.memberCd() == null ? null : bank.memberCd().trim();
        if (fromMember != null && !fromMember.isEmpty()) {
            return fromMember;
        }
        String cd = bank.cd() == null ? null : bank.cd().trim();
        return cd != null && !cd.matches("\\d+") ? cd : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimTo(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
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
