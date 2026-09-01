package id.co.bni.direct.transaction.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.bni.direct.transaction.config.CorrelationContext;
import id.co.bni.direct.transaction.config.IntegrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * The money-moving hop: direct-integration's {@code POST /internal/v1/core/transfers/internal}
 * (API-CONTRACT.md §10). Same construction as {@link AccountNameClient} - a plain class
 * built by {@code IntegrationConfig}, X-Api-Key, {@code exchange(...)} so a non-2xx answer
 * is data.
 *
 * <p><b>NEVER RETRIED.</b> The contract is explicit: this endpoint moves money and a
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

    /**
     * One - and exactly one - attempt to move the money. Amount rides as the contract's
     * Money shape (string amount, always 2 decimals); narrative is trimmed to the cap.
     */
    public TransferOutcome transfer(String fromAccount, String toAccount,
                                    BigDecimal amount, String currency, String narrative) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("fromAccount", fromAccount);
        body.put("toAccount", toAccount);
        ObjectNode money = body.putObject("amount");
        money.put("amount", amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        money.put("currency", currency);
        if (narrative != null && !narrative.isBlank()) {
            String trimmed = narrative.trim();
            body.put("narrative", trimmed.length() > NARRATIVE_MAX
                    ? trimmed.substring(0, NARRATIVE_MAX) : trimmed);
        }
        try {
            return restClient.post()
                    .uri(TRANSFER_PATH)
                    .header("X-Api-Key", properties.getApiKey())
                    .header("X-Correlation-Id", CorrelationContext.currentCorrelationId())
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        JsonNode root = readSilently(response);
                        if (status >= 200 && status < 300) {
                            JsonNode data = root != null && root.has("data") ? root.path("data") : root;
                            String journal = data == null ? null : data.path("coreJournal").asText(null);
                            if (journal == null) {
                                // A 200 without a journal is unreconcilable - the money
                                // may have moved; this is UNKNOWN, not success.
                                log.error("core transfer answered 2xx without a coreJournal");
                                return new TransferOutcome(Status.UNKNOWN, null,
                                        "Core banking menjawab tanpa nomor jurnal.");
                            }
                            return new TransferOutcome(Status.SUCCESS, journal,
                                    data.path("message").asText(null));
                        }
                        if (status == 504) {
                            log.warn("core transfer timed out upstream (504); NOT retrying");
                            return new TransferOutcome(Status.UNKNOWN, null,
                                    "Core banking tidak menjawab tepat waktu.");
                        }
                        String message = root != null ? root.path("message").asText(null) : null;
                        log.warn("core transfer refused: http={} code={}", status,
                                root != null ? root.path("code").asText(null) : null);
                        return new TransferOutcome(Status.REFUSED, null,
                                message != null ? message
                                        : "Core banking menolak transaksi (HTTP " + status + ").");
                    });
        } catch (Exception e) {
            // Transport failure - timed out locally, connection dropped mid-flight,
            // refused. The request MAY have reached core banking; never resend.
            log.warn("core transfer call failed at transport level: {}", e.getMessage());
            return new TransferOutcome(Status.UNKNOWN, null,
                    "Panggilan ke core banking gagal: status transfer tidak dapat dipastikan.");
        }
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
