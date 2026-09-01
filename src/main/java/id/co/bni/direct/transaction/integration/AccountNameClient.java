package id.co.bni.direct.transaction.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.bni.direct.transaction.config.CorrelationContext;
import id.co.bni.direct.transaction.config.IntegrationProperties;
import id.co.bni.direct.transaction.exception.NotFoundException;
import id.co.bni.direct.transaction.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Beneficiary name inquiry - forwarded to direct-integration, which owns the core-banking
 * hop.
 *
 * <p>Same construction as direct-account's {@code AccountBalanceClient}: a plain class
 * built by a {@code @Configuration}, a {@link RestClient} pinned to the configured base
 * URL, {@code exchange(...)} so a non-2xx answer is data rather than an exception.
 *
 * <p>Unlike that client this one DOES throw. A name inquiry is not decoration - the user
 * confirms a transfer against what it answers - so a hop that cannot answer is a 503 the
 * screen must show, and an unknown account is a 404, both with readable Indonesian text.
 */
public class AccountNameClient {

    private static final Logger log = LoggerFactory.getLogger(AccountNameClient.class);

    private static final String NAME_PATH = "/internal/v1/core/accounts/name";

    private final IntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public AccountNameClient(IntegrationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    /** What the inquiry answers with; exactly the fields the FE renders. */
    public record AccountName(String accountNumber, String accountName, String currency, String status) {
    }

    /**
     * The account's registered name, or throws: {@link NotFoundException} when core
     * banking does not know the account, {@link ServiceUnavailableException} when the hop
     * is disabled, unreachable, or answering something unexpected.
     */
    public AccountName fetchAccountName(String accountNumber) {
        if (!properties.isEnabled()) {
            throw new ServiceUnavailableException(
                    "Layanan inquiry rekening sedang tidak tersedia. Silakan coba lagi nanti.");
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("accountNumber", accountNumber);
            return restClient.post()
                    .uri(NAME_PATH)
                    .header("X-Api-Key", properties.getApiKey())
                    .header("X-Correlation-Id", CorrelationContext.currentCorrelationId())
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) {
                            throw new NotFoundException("Rekening tujuan tidak ditemukan.");
                        }
                        if (status < 200 || status >= 300) {
                            log.warn("name inquiry answered {}", status);
                            throw new ServiceUnavailableException(
                                    "Layanan inquiry rekening sedang bermasalah. Silakan coba lagi nanti.");
                        }
                        return parse(response.bodyTo(JsonNode.class));
                    });
        } catch (NotFoundException | ServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // Transport-level failure (refused, timed out, DNS). The account number is not
            // logged: WARN lines travel to shared Elasticsearch and the correlation id is
            // enough to find the request.
            log.warn("name inquiry call failed: {}", e.getMessage());
            throw new ServiceUnavailableException(
                    "Layanan inquiry rekening sedang tidak tersedia. Silakan coba lagi nanti.");
        }
    }

    /**
     * direct-integration answers an {@code ApiResponse} envelope; the payload rides under
     * {@code data}. Looked for there and, failing that, at the root - the defensive
     * reading direct-account's balance client established.
     */
    private AccountName parse(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new ServiceUnavailableException(
                    "Layanan inquiry rekening menjawab dengan format yang tidak dikenal.");
        }
        JsonNode node = root.has("data") ? root.path("data") : root;
        return new AccountName(
                node.path("accountNumber").asText(null),
                node.path("accountName").asText(null),
                node.path("currency").asText(null),
                node.path("status").asText(null));
    }
}
