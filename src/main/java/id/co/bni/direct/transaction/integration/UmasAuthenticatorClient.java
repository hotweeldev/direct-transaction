package id.co.bni.direct.transaction.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.bni.direct.transaction.config.AuthenticatorProperties;
import id.co.bni.direct.transaction.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The UMAS authenticator - OTP challenge issuance and transaction verification.
 *
 * <p>Follows direct-admin's {@code Umas*Client} construction: a plain class built by a
 * {@code @Configuration}, a {@link RestClient} pinned to the configured base URL,
 * {@code exchange(...)} so a non-2xx answer is data rather than an exception, and the same
 * request headers those clients send - {@code Request-Id} (fresh per call),
 * {@code X-Tenant-Id}, and {@code Mav-API-Key} when configured.
 *
 * <p>Request bodies are BARE JSON, verified against the authenticator-service source
 * (InternalAuthenticatorController binds {@code @RequestBody ChallengeRequest} directly) -
 * unlike UMAS admin, there is NO {@code {data, dataProtected}} envelope on the way in.
 * Only the RESPONSE is enveloped (BaseResponse), so replies are still unwrapped from
 * {@code data}.
 *
 * <p>Verification failure is DATA here, not an exception: per that service's contract a
 * failed verification comes back as a non-200, and the submit pipeline turns it into its
 * own OTP_INVALID rejection. Only a transport-level failure throws, as 503.
 */
public class UmasAuthenticatorClient {

    private static final Logger log = LoggerFactory.getLogger(UmasAuthenticatorClient.class);

    private static final String CHALLENGES_PATH = "/internal/authenticator/v1/challenges";
    private static final String VERIFICATIONS_PATH = "/internal/authenticator/v1/verifications/transaction";

    private final AuthenticatorProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public UmasAuthenticatorClient(AuthenticatorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    /** What a challenge request answers with. */
    public record Challenge(String challenge, String verificationId) {
    }

    /** The outcome of one transaction verification; {@code verificationId} may be null. */
    public record Verification(boolean verified, String verificationId) {
    }

    /** A fresh OTP challenge for one user, or 503 when UMAS cannot be reached. */
    public Challenge requestChallenge(String userId) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("userId", userId);
        try {
            return restClient.post()
                    .uri(CHALLENGES_PATH)
                    .headers(h -> apiHeaders(h::add))
                    .body(data)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        JsonNode body = response.bodyTo(JsonNode.class);
                        if (status < 200 || status >= 300) {
                            log.warn("authenticator challenge answered {}", status);
                            throw new ServiceUnavailableException(
                                    "Layanan otentikasi sedang bermasalah. Silakan coba lagi nanti.");
                        }
                        JsonNode node = unwrap(body);
                        return new Challenge(
                                node.path("challenge").asText(null),
                                node.path("verificationId").asText(null));
                    });
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("authenticator challenge call failed: {}", e.getMessage());
            throw new ServiceUnavailableException(
                    "Layanan otentikasi sedang tidak tersedia. Silakan coba lagi nanti.");
        }
    }

    /**
     * Verify one transaction OTP. A non-2xx is a FAILED verification (that service's
     * contract sends verified failures as non-200), so it comes back as data; only
     * transport failure throws.
     */
    public Verification verifyTransaction(String userId, String challenge, String otpResponse) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("userId", userId);
        data.put("challenge", challenge);
        data.put("response", otpResponse);
        try {
            return restClient.post()
                    .uri(VERIFICATIONS_PATH)
                    .headers(h -> apiHeaders(h::add))
                    .body(data)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        JsonNode body = response.bodyTo(JsonNode.class);
                        JsonNode node = unwrap(body);
                        String verificationId = node.path("verificationId").asText(null);
                        if (status < 200 || status >= 300) {
                            log.info("authenticator verification answered {}", status);
                            return new Verification(false, verificationId);
                        }
                        return new Verification(true, verificationId);
                    });
        } catch (Exception e) {
            log.warn("authenticator verification call failed: {}", e.getMessage());
            throw new ServiceUnavailableException(
                    "Layanan otentikasi sedang tidak tersedia. Silakan coba lagi nanti.");
        }
    }

    /** UMAS answers a BaseResponse envelope; callers only ever want data. */
    private JsonNode unwrap(JsonNode body) {
        if (body == null) {
            return objectMapper.createObjectNode();
        }
        return body.has("data") ? body.path("data") : body;
    }

    private void apiHeaders(BiConsumer<String, String> add) {
        add.accept("Request-Id", "transaction-" + UUID.randomUUID());
        add.accept("X-Tenant-Id", properties.getTenantId());
        if (properties.getMavApiKey() != null && !properties.getMavApiKey().isBlank()) {
            add.accept("Mav-API-Key", properties.getMavApiKey());
        }
    }
}
