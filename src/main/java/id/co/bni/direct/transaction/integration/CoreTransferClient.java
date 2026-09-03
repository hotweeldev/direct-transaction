package id.co.bni.direct.transaction.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.bni.direct.transaction.config.CorrelationContext;
import id.co.bni.direct.transaction.config.IntegrationProperties;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The money-moving hop: direct-integration's {@code POST /internal/v1/core/transfers/*}
 * family - {@code /internal} (P0, in-house), {@code /kliring} and {@code /rtgs} (P1,
 * Transfer ke Bank Lain). Same construction as {@link AccountNameClient} - a plain class
 * built by {@code IntegrationConfig}, X-Api-Key, {@code exchange(...)} so a non-2xx answer
 * is data. All three endpoints answer the same envelope ({@code coreJournal},
 * {@code message}), so one outcome parser serves them all.
 *
 * <p><b>NEVER RETRIED.</b> The contract is explicit: these endpoints move money and a
 * timeout must NOT be resent - the transfer may have happened. So the outcome vocabulary
 * has three values, not two: SUCCESS (2xx with a journal), REFUSED (a definite non-2xx
 * answer - core banking is healthy and said no, or the request was bad), and UNKNOWN
 * (504 from the gateway, or a transport failure after the request left - nobody can say
 * whether money moved). Callers must treat UNKNOWN as "reconcile later", never as
 * "try again". Transport failures where the request provably never left (connection
 * refused) are still answered UNKNOWN rather than REFUSED: telling the two apart from
 * exception types is guesswork, and the safe direction is the one that never re-sends.
 */
public class CoreTransferClient {

    private static final Logger log = LoggerFactory.getLogger(CoreTransferClient.class);

    private static final String TRANSFER_PATH = "/internal/v1/core/transfers/internal";
    private static final String KLIRING_PATH = "/internal/v1/core/transfers/kliring";
    private static final String RTGS_PATH = "/internal/v1/core/transfers/rtgs";
    private static final String INTERBANK_PATH = "/internal/v1/core/transfers/interbank";
    private static final String INTERBANK_INQUIRY_PATH = "/internal/v1/core/transfers/interbank/inquiry";
    private static final String CROSS_PATH = "/internal/v1/core/transfers/cross-currency";
    private static final String LOAN_PATH = "/internal/v1/core/transfers/loan";
    private static final String UNDERLYING_PATH = "/internal/v1/core/transfers/underlying-check";
    private static final String RATES_PATH = "/internal/v1/core/rates";
    private static final String STATUS_PATH = "/internal/v1/core/transfers/status";
    private static final String VA_PATH = "/internal/v1/core/transfers/va";
    private static final String VA_INQUIRY_PATH = "/internal/v1/core/transfers/va/inquiry";

    /** The contract caps narrative at 50 characters. */
    private static final int NARRATIVE_MAX = 50;

    // Explicit timeouts, unlike the name-inquiry client: an unanswered money-moving call
    // must become a bounded UNKNOWN, not a request thread parked forever. Read allows for
    // core banking's slow path plus the gateway's own upstream timeout.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    public enum Status { SUCCESS, REFUSED, UNKNOWN }

    /**
     * One attempt's verdict. {@code coreJournal} only on SUCCESS; {@code message} is the
     * envelope's readable text (SUCCESS: upstream confirmation, REFUSED: the refusal).
     */
    public record TransferOutcome(Status status, String coreJournal, String message) {
    }

    /**
     * The kliring (LLG/SKN) instruction - field-for-field the integration contract's
     * {@code KliringTransferRequest}. Amounts ride as decimal STRINGS (the wire shape);
     * use {@link #amountString(BigDecimal)}. Residency codes are kliring's own list
     * (1 Penduduk / 2 Bukan Penduduk / 3 Gabungan).
     */
    public record KliringInstruction(
            String fromAccount,
            String amount,
            String fee,
            String narrative1,
            String narrative2,
            String beneficiaryName,
            String beneficiaryAddress1,
            String beneficiaryAddress2,
            String beneficiaryPhone,
            String beneficiaryPostalCode,
            String beneficiaryIdNumber,
            String beneficiaryIdType,
            String senderName,
            String senderAddress,
            String senderPhone,
            String senderResidencyCode,
            String beneficiaryResidencyCode,
            String clearingBankCode,
            String beneficiaryAccount,
            String beneficiaryType,
            String intermediaryBranch,
            String finalBankBic,
            String tsaCode) {
    }

    /**
     * The RTGS instruction - field-for-field the integration contract's
     * {@code RtgsTransferRequest}. Residency codes are RTGS's own list (0 Resident /
     * 1 Non-Resident); {@code beneficiaryPostalCode} and {@code intermediaryBranch} are
     * mandatory upstream.
     */
    public record RtgsInstruction(
            String fromAccount,
            String amount,
            String fee,
            String narrative1,
            String narrative2,
            String narrative3,
            String beneficiaryName,
            String beneficiaryAddress1,
            String beneficiaryAddress2,
            String beneficiaryPhone,
            String beneficiaryPostalCode,
            String beneficiaryIdNumber,
            String beneficiaryIdType,
            String senderName,
            String senderName2,
            String senderResidencyCode,
            String beneficiaryResidencyCode,
            String rtgsBankCode,
            String beneficiaryAccount,
            String intermediaryBranch,
            String tsaCode) {
    }

    /**
     * The interbank (P2, RTOL / ATM Bersama) instruction - the integration contract's
     * request shape, shared verbatim by the inquiry and the payment.
     * {@code beneficiaryBankCode} is the 3-digit interbank code
     * (COM_MT_DOM_BANK.ONLINE_CD); {@code amount} rides as a decimal string like the
     * other transfer wires ({@link #amountString(BigDecimal)}).
     */
    public record InterbankInstruction(
            String fromAccount,
            String beneficiaryAccount,
            String beneficiaryBankCode,
            String amount,
            String customerRefNo,
            String refNo) {
    }

    /**
     * One interbank payment attempt's verdict. The switch has no core journal - its
     * cross-network identifier is {@code retrievalRefNo} (RRN), and {@code responseCode}
     * is its ISO-style answer. Upstream {@code IN_PROCESS} (responseCode 68) is answered
     * as UNKNOWN on purpose: the money's fate is with the switch, so the caller must
     * treat it exactly like a timeout - usage kept, never auto-failed, never resent.
     */
    public record InterbankOutcome(Status status, String retrievalRefNo,
                                   String responseCode, String message) {
    }

    /** The interbank inquiry's answer - the beneficiary as the switch knows it. */
    public record InterbankInquiry(String beneficiaryName, String beneficiaryBankName,
                                   String beneficiaryAccount, String retrievalRefNo,
                                   String sourceAccountName) {
    }

    /** Money as the integration wire carries it: decimal string + ISO currency. */
    public record Money(String amount, String currency) {
    }

    /**
     * The DepTransferCross instruction (P5) - field-for-field the integration contract's
     * {@code CrossCurrencyTransferRequest}. {@code baseAmount} is the SERVER-computed IDR
     * equivalent frozen on the task at submit - never the FE's figure.
     */
    public record CrossCurrencyInstruction(
            String fromAccount,
            Money debitAmount,
            String toAccount,
            Money creditAmount,
            String baseAmount,
            String rateType,
            String narrative,
            String narrativeExt) {
    }

    /**
     * The LoanTransfer instruction (P5) - the integration contract's
     * {@code LoanTransferRequest} (section 14): each leg carries its own currency and
     * amount, core banking owns the rate arithmetic.
     */
    public record LoanInstruction(
            String fromAccount,
            String fromCurrency,
            String fromAmount,
            String toAccount,
            String toCurrency,
            String toAmount,
            String narrative,
            String rateType) {
    }

    /**
     * The trxPBI underlying-check instruction (P5) - the integration contract's
     * {@code UnderlyingCheckRequest} (section 20). {@code sellCurrency} is what the
     * customer gives up (IDR when buying valas with rupiah), {@code buyCurrency} what
     * they receive; {@code transactionDate} is ISO {@code yyyy-MM-dd}.
     */
    public record UnderlyingCheckInstruction(
            String cif,
            String accountNo,
            String sellCurrency,
            String buyCurrency,
            String amount,
            String rate,
            String unitCode,
            String transactionDate,
            String purposeCode,
            String docType,
            String docNote) {
    }

    /** The trxPBI verdict as the integration endpoint shapes it (section 20). */
    public record UnderlyingCheck(String message, boolean statementRequired,
                                  boolean underlyingRequired) {
    }

    /**
     * One recent posting on an account as {@code DepTrxInquiry} (integration
     * {@code /transfers/status}) reports it - the P6 reconciliation read. {@code amount}
     * keeps the upstream sign: negative is a debit. Currency is not carried by the
     * operation, so none is reported here either.
     */
    public record Posting(String transactionDate, String postDateTime, String journalNo,
                          String narrative, BigDecimal amount) {
    }

    /** One rate-table row (integration section 12); decimal strings, never doubles. */
    public record Rate(String currency, String quoteCurrency, String units,
                       String midRate, String buyRate, String sellRate) {
    }

    /**
     * The Transfer ke Virtual Account payment instruction (P3) - field-for-field the
     * integration contract's VA billing-payment request ({@code fromAccount}, {@code amount} - the integration's own field names, mapped to accountNumberFrom/billedAmount upstream). {@code amount} is a decimal
     * string ({@link #amountString(BigDecimal)}); {@code inquiryRequestId} is what the
     * billing inquiry answered and binds the payment to the inquiry the customer saw
     * (null when the inquiry answered none - the VA service decides whether it accepts
     * that).
     */
    public record VaInstruction(
            String reference,
            String fromAccount,
            String billingNumber,
            String amount,
            String inquiryRequestId) {
    }

    /**
     * The VA billing inquiry answer as the integration endpoint shapes it. Every field
     * but {@code billingNumber} may be null: the success shape has not been observed on
     * DEV (no registered VA yet), so the integration hop parses leniently and this
     * record passes through whatever it found.
     */
    public record VaInquiry(String billingNumber, String inquiryRequestId, String billingName,
                            BigDecimal billedAmount, String currency,
                            String responseCode, String responseMessage) {
    }

    private final IntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public CoreTransferClient(IntegrationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** The wire shape of a money amount: plain decimal string, always 2 decimals. */
    public static String amountString(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * One - and exactly one - attempt to move the money in-house. Amount rides as the
     * contract's Money shape (string amount, always 2 decimals); narrative is trimmed to
     * the cap.
     */
    public TransferOutcome transfer(String fromAccount, String toAccount,
                                    BigDecimal amount, String currency, String narrative) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("fromAccount", fromAccount);
        body.put("toAccount", toAccount);
        ObjectNode money = body.putObject("amount");
        money.put("amount", amountString(amount));
        money.put("currency", currency);
        if (narrative != null && !narrative.isBlank()) {
            String trimmed = narrative.trim();
            body.put("narrative", trimmed.length() > NARRATIVE_MAX
                    ? trimmed.substring(0, NARRATIVE_MAX) : trimmed);
        }
        return post(TRANSFER_PATH, body);
    }

    /** One attempt at an outward kliring (LLG/SKN) transfer. Never retried. */
    public TransferOutcome transferKliring(KliringInstruction instruction) {
        return post(KLIRING_PATH, objectMapper.valueToTree(instruction));
    }

    /**
     * One attempt at a Transfer ke Virtual Account payment (P3). Never retried: the VA
     * billing payment debits the customer, so an unanswered call is UNKNOWN like every
     * other money-moving hop. The integration endpoint answers the kliring contract
     * shape (coreJournal = the VA service's journalNum, present if and only if the
     * payment succeeded), so the shared verdict mapping applies unchanged.
     */
    public TransferOutcome transferVa(VaInstruction instruction) {
        return post(VA_PATH, instruction);
    }

    /**
     * The VA billing inquiry (P3) - a read: the VA service's refusal (422 with the
     * service's own message, e.g. "Client Tidak Ditemukan.") is data and becomes this
     * pipeline's {@code VA_INQUIRY_REJECTED}; only transport failure is a 503. A
     * disabled integration hop is a 503 too, never a fabricated bill.
     */
    public VaInquiry inquireVa(String reference, String billingNumber, String accountNumberFrom) {
        if (!properties.isEnabled()) {
            throw new ServiceUnavailableException(
                    "Layanan inquiry Virtual Account sedang tidak tersedia. Silakan coba lagi nanti.");
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("reference", reference);
            body.put("billingNumber", billingNumber);
            body.put("fromAccount", accountNumberFrom);
            return restClient.post()
                    .uri(VA_INQUIRY_PATH)
                    .header("X-Api-Key", properties.getApiKey())
                    .header("X-Correlation-Id", CorrelationContext.currentCorrelationId())
                    .body(body)
                    .exchange((request, response) -> mapVaInquiryResponse(
                            response.getStatusCode().value(), readSilently(response), billingNumber));
        } catch (BusinessRuleException | ServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("VA inquiry call failed: {}", e.getMessage());
            throw new ServiceUnavailableException(
                    "Layanan inquiry Virtual Account sedang tidak tersedia. Silakan coba lagi nanti.");
        }
    }

    /**
     * The VA inquiry verdict off the HTTP status and envelope. 422 is the VA service's
     * own refusal (the integration hop's {@code VA_INQUIRY_REJECTED}); any other non-2xx
     * is the hop or the service being down. A 2xx is read leniently: the fields the
     * spec names plus the aliases the integration hop maps, all optional.
     */
    static VaInquiry mapVaInquiryResponse(int httpStatus, JsonNode root, String billingNumber) {
        if (httpStatus == 422) {
            String message = root != null ? root.path("message").asText(null) : null;
            throw new BusinessRuleException("VA_INQUIRY_REJECTED",
                    message != null ? message : "Virtual Account tidak dapat ditagihkan.");
        }
        if (httpStatus < 200 || httpStatus >= 300) {
            log.warn("VA inquiry answered {}", httpStatus);
            throw new ServiceUnavailableException(
                    "Layanan inquiry Virtual Account sedang bermasalah. Silakan coba lagi nanti.");
        }
        JsonNode data = root != null && root.has("data") ? root.path("data") : root;
        if (data == null || data.isMissingNode() || data.isNull()) {
            throw new ServiceUnavailableException(
                    "Layanan inquiry Virtual Account menjawab dengan format yang tidak dikenal.");
        }
        String number = data.path("billingNumber").asText(null);
        return new VaInquiry(
                number != null ? number : billingNumber,
                data.path("inquiryRequestId").asText(null),
                data.path("billingName").asText(null),
                parseAmount(data.path("billedAmount").isObject()
                        ? data.path("billedAmount").path("amount").asText(null)
                        : data.path("billedAmount").asText(null)),
                data.path("currency").asText(null),
                data.path("responseCode").asText(null),
                data.path("responseMessage").asText(null));
    }

    /** One attempt at an outward RTGS transfer. Never retried. */
    public TransferOutcome transferRtgs(RtgsInstruction instruction) {
        return post(RTGS_PATH, objectMapper.valueToTree(instruction));
    }

    /**
     * One attempt at a cross-currency (DepTransferCross) transfer. Never retried. The
     * record itself rides as the body - NEVER {@code objectMapper.valueToTree(...)}
     * straight into {@code body(...)} (the P2 ClassCastException lesson).
     */
    public TransferOutcome transferCrossCurrency(CrossCurrencyInstruction instruction) {
        return post(CROSS_PATH, instruction);
    }

    /** One attempt at a loan-source (LoanTransfer) transfer. Never retried. */
    public TransferOutcome transferLoan(LoanInstruction instruction) {
        return post(LOAN_PATH, instruction);
    }

    /**
     * One - and exactly one - interbank (RTOL / ATM Bersama) payment attempt. Never
     * retried; the verdict vocabulary and its mapping live in
     * {@link #mapInterbankResponse(int, JsonNode)}.
     */
    public InterbankOutcome transferInterbank(InterbankInstruction instruction) {
        try {
            return restClient.post()
                    .uri(INTERBANK_PATH)
                    .header("X-Api-Key", properties.getApiKey())
                    .header("X-Correlation-Id", CorrelationContext.currentCorrelationId())
                    .body(instruction)
                    .exchange((request, response) -> mapInterbankResponse(
                            response.getStatusCode().value(), readSilently(response)));
        } catch (Exception e) {
            // Transport failure: the request MAY have reached the switch; never resend.
            log.warn("interbank transfer call failed at transport level: {}", e.getMessage());
            return new InterbankOutcome(Status.UNKNOWN, null, null,
                    "Panggilan ke switching antar bank gagal: status transfer tidak dapat dipastikan.");
        }
    }

    /**
     * The interbank verdict off one HTTP answer, factored out static so the code-68 rule
     * is unit-testable without a server: 2xx + upstream status SUCCESS is the only
     * success; IN_PROCESS (responseCode 68) and any 2xx without a readable status are
     * UNKNOWN (the switch holds the money's fate - reconcile, never refail or resend);
     * 504 is the timeout UNKNOWN; every other non-2xx is a definite refusal. The trace
     * (retrievalRefNo, responseCode) is carried on every verdict where present.
     */
    static InterbankOutcome mapInterbankResponse(int httpStatus, JsonNode root) {
        JsonNode data = root != null && root.has("data") ? root.path("data") : root;
        String retrievalRefNo = data == null ? null : data.path("retrievalRefNo").asText(null);
        String responseCode = data == null ? null : data.path("responseCode").asText(null);
        if (httpStatus >= 200 && httpStatus < 300) {
            String status = data == null ? null : data.path("status").asText(null);
            if ("SUCCESS".equalsIgnoreCase(status)) {
                return new InterbankOutcome(Status.SUCCESS, retrievalRefNo, responseCode, null);
            }
            if (status == null) {
                log.error("interbank transfer answered 2xx without a status");
                return new InterbankOutcome(Status.UNKNOWN, retrievalRefNo, responseCode,
                        "Switching antar bank menjawab tanpa status transaksi.");
            }
            log.warn("interbank transfer IN_PROCESS: responseCode={} rrn={}", responseCode, retrievalRefNo);
            return new InterbankOutcome(Status.UNKNOWN, retrievalRefNo, responseCode,
                    "Transfer antar bank sedang diproses oleh switching (kode "
                            + (responseCode != null ? responseCode : "?") + "). Perlu rekonsiliasi.");
        }
        if (httpStatus == 504) {
            log.warn("interbank transfer timed out upstream (504); NOT retrying");
            return new InterbankOutcome(Status.UNKNOWN, retrievalRefNo, responseCode,
                    "Switching antar bank tidak menjawab tepat waktu.");
        }
        String message = root != null ? root.path("message").asText(null) : null;
        log.warn("interbank transfer refused: http={} code={}", httpStatus,
                root != null ? root.path("code").asText(null) : null);
        return new InterbankOutcome(Status.REFUSED, retrievalRefNo, responseCode,
                message != null ? message
                        : "Switching antar bank menolak transaksi (HTTP " + httpStatus + ").");
    }

    /**
     * The interbank beneficiary inquiry (the FE's "Periksa" step). Read-only and safe,
     * but still a single attempt. Throws like {@link AccountNameClient}: a refusal is
     * this pipeline's own 422, an unreachable hop a 503.
     */
    public InterbankInquiry inquireInterbank(InterbankInstruction instruction) {
        if (!properties.isEnabled()) {
            throw new ServiceUnavailableException(
                    "Layanan inquiry antar bank sedang tidak tersedia. Silakan coba lagi nanti.");
        }
        try {
            return restClient.post()
                    .uri(INTERBANK_INQUIRY_PATH)
                    .header("X-Api-Key", properties.getApiKey())
                    .header("X-Correlation-Id", CorrelationContext.currentCorrelationId())
                    .body(instruction)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        JsonNode root = readSilently(response);
                        if (status < 200 || status >= 300) {
                            String message = root != null ? root.path("message").asText(null) : null;
                            throw new BusinessRuleException("INTERBANK_INQUIRY_FAILED",
                                    message != null ? message
                                            : "Inquiry antar bank ditolak (HTTP " + status + ").");
                        }
                        JsonNode data = root != null && root.has("data") ? root.path("data") : root;
                        if (data == null || data.isMissingNode() || data.isNull()) {
                            throw new ServiceUnavailableException(
                                    "Layanan inquiry antar bank menjawab dengan format yang tidak dikenal.");
                        }
                        return new InterbankInquiry(
                                data.path("beneficiaryName").asText(null),
                                data.path("beneficiaryBankName").asText(null),
                                data.path("beneficiaryAccount").asText(null),
                                data.path("retrievalRefNo").asText(null),
                                data.path("sourceAccountName").asText(null));
                    });
        } catch (BusinessRuleException | ServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("interbank inquiry call failed: {}", e.getMessage());
            throw new ServiceUnavailableException(
                    "Layanan inquiry antar bank sedang tidak tersedia. Silakan coba lagi nanti.");
        }
    }

    /**
     * The bank's rate table for one rate type - a READ (direct-integration retries it),
     * used at submit to compute the DepTransferCross baseAmount. Throws like the
     * inquiry clients: unreachable or malformed answers are the 503 the submit surfaces.
     */
    public List<Rate> fetchRates(String rateType) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("rateType", rateType);
            return restClient.post()
                    .uri(RATES_PATH)
                    .header("X-Api-Key", properties.getApiKey())
                    .header("X-Correlation-Id", CorrelationContext.currentCorrelationId())
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        JsonNode root = readSilently(response);
                        if (status < 200 || status >= 300) {
                            log.warn("rates call answered {}", status);
                            throw new ServiceUnavailableException(
                                    "Layanan kurs sedang tidak tersedia. Silakan coba lagi nanti.");
                        }
                        JsonNode data = root != null && root.has("data") ? root.path("data") : root;
                        List<Rate> rates = new ArrayList<>();
                        if (data != null) {
                            for (JsonNode rate : data.path("rates")) {
                                rates.add(new Rate(
                                        rate.path("currency").asText(null),
                                        rate.path("quoteCurrency").asText(null),
                                        rate.path("units").asText(null),
                                        rate.path("midRate").asText(null),
                                        rate.path("buyRate").asText(null),
                                        rate.path("sellRate").asText(null)));
                            }
                        }
                        return rates;
                    });
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("rates call failed: {}", e.getMessage());
            throw new ServiceUnavailableException(
                    "Layanan kurs sedang tidak tersedia. Silakan coba lagi nanti.");
        }
    }

    /**
     * The last postings on an account ({@code DepTrxInquiry}) - the read that
     * reconciliation runs against a simsem account to learn whether an UNKNOWN leg 2
     * ever left it. A READ (direct-integration retries it); unreachable or malformed
     * answers are the 503 the reconcile endpoint surfaces as "inconclusive", never an
     * empty list - an empty list would read as "leg 2 did not land" and trigger a
     * refund, which must never happen on a hop that did not answer.
     */
    public List<Posting> inquireTransactions(String accountNumber, String transactionType) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("accountNumber", accountNumber);
            body.put("transactionType", transactionType);
            return restClient.post()
                    .uri(STATUS_PATH)
                    .header("X-Api-Key", properties.getApiKey())
                    .header("X-Correlation-Id", CorrelationContext.currentCorrelationId())
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        JsonNode root = readSilently(response);
                        if (status < 200 || status >= 300) {
                            log.warn("transaction status call answered {}", status);
                            throw new ServiceUnavailableException(
                                    "Layanan status transaksi core banking sedang tidak tersedia. "
                                            + "Rekonsiliasi tidak dapat dilakukan saat ini.");
                        }
                        JsonNode data = root != null && root.has("data") ? root.path("data") : root;
                        if (data == null || data.isMissingNode() || data.isNull()) {
                            throw new ServiceUnavailableException(
                                    "Layanan status transaksi menjawab dengan format yang tidak dikenal.");
                        }
                        List<Posting> postings = new ArrayList<>();
                        for (JsonNode row : data.path("transactions")) {
                            postings.add(new Posting(
                                    row.path("transactionDate").asText(null),
                                    row.path("postDateTime").asText(null),
                                    row.path("journalNo").asText(null),
                                    row.path("narrative").asText(null),
                                    parseAmount(row.path("amount").path("amount").asText(null))));
                        }
                        return postings;
                    });
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("transaction status call failed: {}", e.getMessage());
            throw new ServiceUnavailableException(
                    "Layanan status transaksi core banking sedang tidak tersedia. "
                            + "Rekonsiliasi tidak dapat dilakukan saat ini.");
        }
    }

    /** A signed decimal string to BigDecimal; null when absent or unparsable. */
    private static BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The Bank Indonesia underlying/statement check (trxPBI) for an IDR-to-valas
     * purchase. A READ that moves no money - but the submit pipeline treats it as
     * MANDATORY for that corridor, so every failure here is FAIL-CLOSED: a non-2xx
     * (502/504) and a transport failure all become the 503 this throws, never a pass.
     */
    public UnderlyingCheck checkUnderlying(UnderlyingCheckInstruction instruction) {
        try {
            return restClient.post()
                    .uri(UNDERLYING_PATH)
                    .header("X-Api-Key", properties.getApiKey())
                    .header("X-Correlation-Id", CorrelationContext.currentCorrelationId())
                    .body(instruction)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        JsonNode root = readSilently(response);
                        if (status < 200 || status >= 300) {
                            log.warn("underlying check answered {}", status);
                            throw new ServiceUnavailableException(
                                    "Pemeriksaan underlying Bank Indonesia sedang tidak tersedia. "
                                            + "Transaksi IDR ke valas tidak dapat diproses saat ini.");
                        }
                        JsonNode data = root != null && root.has("data") ? root.path("data") : root;
                        if (data == null || data.isMissingNode() || data.isNull()) {
                            throw new ServiceUnavailableException(
                                    "Pemeriksaan underlying Bank Indonesia menjawab dengan format "
                                            + "yang tidak dikenal.");
                        }
                        return new UnderlyingCheck(
                                data.path("message").asText(null),
                                data.path("statementRequired").asBoolean(false),
                                data.path("underlyingRequired").asBoolean(false));
                    });
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("underlying check call failed: {}", e.getMessage());
            throw new ServiceUnavailableException(
                    "Pemeriksaan underlying Bank Indonesia sedang tidak tersedia. "
                            + "Transaksi IDR ke valas tidak dapat diproses saat ini.");
        }
    }

    /** The single-attempt POST all money-moving operations share. */
    private TransferOutcome post(String path, Object body) {
        try {
            return restClient.post()
                    .uri(path)
                    .header("X-Api-Key", properties.getApiKey())
                    .header("X-Correlation-Id", CorrelationContext.currentCorrelationId())
                    .body(body)
                    .exchange((request, response) -> mapTransferResponse(
                            path, response.getStatusCode().value(), readSilently(response)));
        } catch (Exception e) {
            // Transport failure - timed out locally, connection dropped mid-flight,
            // refused. The request MAY have reached core banking; never resend.
            log.warn("core transfer call failed at transport level ({}): {}", path, e.getMessage());
            return new TransferOutcome(Status.UNKNOWN, null,
                    "Panggilan ke core banking gagal: status transfer tidak dapat dipastikan.");
        }
    }

    /**
     * The shared money-moving verdict: 2xx with a coreJournal is SUCCESS, 2xx without one
     * is UNKNOWN (unreconcilable, the money may have moved), 504 is UNKNOWN, any other
     * non-2xx is a definite refusal carrying the upstream message. Every transfer path
     * (in-house, kliring, RTGS, cross, loan, VA) shares it.
     */
    static TransferOutcome mapTransferResponse(String path, int status, JsonNode root) {
        if (status >= 200 && status < 300) {
            JsonNode data = root != null && root.has("data") ? root.path("data") : root;
            String journal = data == null ? null : data.path("coreJournal").asText(null);
            if (journal == null || journal.isBlank()) {
                // A 200 without a journal is unreconcilable - the money
                // may have moved; this is UNKNOWN, not success.
                log.error("core transfer answered 2xx without a coreJournal ({})", path);
                return new TransferOutcome(Status.UNKNOWN, null,
                        "Core banking menjawab tanpa nomor jurnal.");
            }
            return new TransferOutcome(Status.SUCCESS, journal,
                    data.path("message").asText(null));
        }
        if (status == 504) {
            log.warn("core transfer timed out upstream (504) on {}; NOT retrying", path);
            return new TransferOutcome(Status.UNKNOWN, null,
                    "Core banking tidak menjawab tepat waktu.");
        }
        String message = root != null ? root.path("message").asText(null) : null;
        log.warn("core transfer refused: path={} http={} code={}", path, status,
                root != null ? root.path("code").asText(null) : null);
        return new TransferOutcome(Status.REFUSED, null,
                message != null ? message
                        : "Core banking menolak transaksi (HTTP " + status + ").");
    }

    /** The envelope, or null when the body is absent or unparsable - never throws. */
    private static JsonNode readSilently(
            org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(JsonNode.class);
        } catch (Exception e) {
            return null;
        }
    }
}
