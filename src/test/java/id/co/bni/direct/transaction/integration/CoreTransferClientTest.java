package id.co.bni.direct.transaction.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.integration.CoreTransferClient.InterbankOutcome;
import id.co.bni.direct.transaction.integration.CoreTransferClient.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import id.co.bni.direct.transaction.config.IntegrationProperties;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.exception.ServiceUnavailableException;
import id.co.bni.direct.transaction.integration.CoreTransferClient.TransferOutcome;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interbank verdict mapping (P2) in isolation - the piece the mocked-client
 * execution tests can never see: how an HTTP status plus envelope becomes a
 * SUCCESS / UNKNOWN / REFUSED outcome, and above all that responseCode 68
 * (IN_PROCESS) maps to UNKNOWN, never to a refusal.
 */
class CoreTransferClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aSuccessBodyMapsToSuccessWithTheTrace() {
        InterbankOutcome outcome = CoreTransferClient.mapInterbankResponse(200, json(
                "{\"data\":{\"status\":\"SUCCESS\",\"responseCode\":\"00\","
                        + "\"retrievalRefNo\":\"RRN000123\",\"beneficiaryName\":\"YOSUA\"}}"));

        assertThat(outcome.status()).isEqualTo(Status.SUCCESS);
        assertThat(outcome.retrievalRefNo()).isEqualTo("RRN000123");
        assertThat(outcome.responseCode()).isEqualTo("00");
    }

    @Test
    void inProcessCode68MapsToUnknownNeverRefused() {
        InterbankOutcome outcome = CoreTransferClient.mapInterbankResponse(200, json(
                "{\"data\":{\"status\":\"IN_PROCESS\",\"responseCode\":\"68\","
                        + "\"retrievalRefNo\":\"RRN000124\"}}"));

        assertThat(outcome.status()).isEqualTo(Status.UNKNOWN);
        assertThat(outcome.responseCode()).isEqualTo("68");
        assertThat(outcome.retrievalRefNo()).isEqualTo("RRN000124");
        assertThat(outcome.message()).contains("68");
    }

    @Test
    void aGatewayTimeoutIsUnknown() {
        InterbankOutcome outcome = CoreTransferClient.mapInterbankResponse(504, null);

        assertThat(outcome.status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void aRefusalCarriesTheUpstreamMessage() {
        InterbankOutcome outcome = CoreTransferClient.mapInterbankResponse(502, json(
                "{\"code\":\"0108\",\"message\":\"Rekening tujuan tidak ditemukan.\"}"));

        assertThat(outcome.status()).isEqualTo(Status.REFUSED);
        assertThat(outcome.message()).isEqualTo("Rekening tujuan tidak ditemukan.");
    }

    @Test
    void aTwoHundredWithoutAStatusIsUnknownNotSuccess() {
        InterbankOutcome outcome = CoreTransferClient.mapInterbankResponse(200, json("{}"));

        assertThat(outcome.status()).isEqualTo(Status.UNKNOWN);
    }


    // ---- The shared money-moving verdict (P3 made it a static so the VA path is testable) ----

    @Test
    void aJournalOnTwoHundredIsSuccess() {
        TransferOutcome outcome = CoreTransferClient.mapTransferResponse("/va", 200, json(
                "{\"data\":{\"coreJournal\":\"907409\",\"message\":\"OK\"}}"));

        assertThat(outcome.status()).isEqualTo(Status.SUCCESS);
        assertThat(outcome.coreJournal()).isEqualTo("907409");
    }

    @Test
    void aTwoHundredWithoutAJournalIsUnknownNeverSuccess() {
        // The VA service's "journalNum null" shape: 2xx, no journal. Unreconcilable -
        // the payment may have happened - so UNKNOWN, exactly like a timeout.
        TransferOutcome absent = CoreTransferClient.mapTransferResponse("/va", 200, json(
                "{\"data\":{\"message\":\"Client Tidak Ditemukan.\"}}"));
        TransferOutcome blank = CoreTransferClient.mapTransferResponse("/va", 200, json(
                "{\"data\":{\"coreJournal\":\"\"}}"));

        assertThat(absent.status()).isEqualTo(Status.UNKNOWN);
        assertThat(blank.status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void aFiveOhFourIsUnknownAndAnyOtherFailureIsARefusalWithTheMessage() {
        TransferOutcome timeout = CoreTransferClient.mapTransferResponse("/va", 504, null);
        TransferOutcome refused = CoreTransferClient.mapTransferResponse("/va", 422, json(
                "{\"code\":\"VA_PAYMENT_REJECTED\",\"message\":\"Client Tidak Ditemukan.\"}"));
        TransferOutcome bare = CoreTransferClient.mapTransferResponse("/va", 502, null);

        assertThat(timeout.status()).isEqualTo(Status.UNKNOWN);
        assertThat(refused.status()).isEqualTo(Status.REFUSED);
        assertThat(refused.message()).isEqualTo("Client Tidak Ditemukan.");
        assertThat(bare.status()).isEqualTo(Status.REFUSED);
        assertThat(bare.message()).contains("502");
    }

    // ---- P3: the VA inquiry verdict ----

    @Test
    void aVaInquirySuccessIsReadLenientlyWithNullsAllowed() {
        CoreTransferClient.VaInquiry full = CoreTransferClient.mapVaInquiryResponse(200, json(
                "{\"data\":{\"billingNumber\":\"8241002201234567\",\"inquiryRequestId\":\"INQ-1\","
                        + "\"billingName\":\"PT TOKOPEDIA\",\"billedAmount\":\"150000\","
                        + "\"currency\":\"IDR\",\"responseCode\":\"00\",\"responseMessage\":\"OK\"}}"),
                "8241002201234567");
        CoreTransferClient.VaInquiry sparse = CoreTransferClient.mapVaInquiryResponse(200, json(
                "{\"data\":{\"responseCode\":\"00\"}}"), "8241002201234567");
        CoreTransferClient.VaInquiry money = CoreTransferClient.mapVaInquiryResponse(200, json(
                "{\"data\":{\"billedAmount\":{\"amount\":\"150000.00\",\"currency\":\"IDR\"}}}"),
                "8241002201234567");

        assertThat(full.inquiryRequestId()).isEqualTo("INQ-1");
        assertThat(full.billingName()).isEqualTo("PT TOKOPEDIA");
        assertThat(full.billedAmount()).isEqualByComparingTo("150000");
        assertThat(full.currency()).isEqualTo("IDR");
        assertThat(sparse.billingNumber()).isEqualTo("8241002201234567");
        assertThat(sparse.inquiryRequestId()).isNull();
        assertThat(sparse.billedAmount()).isNull();
        assertThat(money.billedAmount()).isEqualByComparingTo("150000");
    }

    @Test
    void aVaInquiryFourTwentyTwoIsTheServicesRefusalAndAFiveXxIsUnavailable() {
        assertThatThrownBy(() -> CoreTransferClient.mapVaInquiryResponse(422, json(
                "{\"code\":\"VA_INQUIRY_REJECTED\",\"message\":\"Client Tidak Ditemukan.\"}"), "1"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Client Tidak Ditemukan.")
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("VA_INQUIRY_REJECTED"));
        assertThatThrownBy(() -> CoreTransferClient.mapVaInquiryResponse(503, null, "1"))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> CoreTransferClient.mapVaInquiryResponse(200, json("null"), "1"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    // ---- P3: the VA wire against a local HTTP server (paths, headers, bodies) ----

    @Test
    void transferVaPostsTheInstructionToTheVaPathAndInquireVaToItsInquiryPath() throws Exception {
        var received = new java.util.concurrent.ConcurrentHashMap<String, String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/core/transfers/va/inquiry", exchange -> {
            received.put("inquiry", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            received.put("inquiryApiKey", exchange.getRequestHeaders().getFirst("X-Api-Key"));
            byte[] body = ("{\"data\":{\"billingNumber\":\"8241002201234567\",\"inquiryRequestId\":\"INQ-1\","
                    + "\"billingName\":\"PT TOKOPEDIA\",\"billedAmount\":\"150000\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/internal/v1/core/transfers/va", exchange -> {
            received.put("payment", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"data\":{\"coreJournal\":\"907409\",\"message\":\"OK\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            IntegrationProperties properties = new IntegrationProperties();
            properties.setEnabled(true);
            properties.setApiKey("k-1");
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            CoreTransferClient client = new CoreTransferClient(properties, MAPPER);

            CoreTransferClient.VaInquiry inquiry = client.inquireVa("REF-1", "8241002201234567", "113179933");
            TransferOutcome payment = client.transferVa(new CoreTransferClient.VaInstruction(
                    "REF-1", "113179933", "8241002201234567", "150000.00", "INQ-1"));

            assertThat(inquiry.inquiryRequestId()).isEqualTo("INQ-1");
            assertThat(inquiry.billingName()).isEqualTo("PT TOKOPEDIA");
            assertThat(inquiry.billedAmount()).isEqualByComparingTo("150000");
            assertThat(received.get("inquiryApiKey")).isEqualTo("k-1");
            JsonNode inquiryBody = json(received.get("inquiry"));
            assertThat(inquiryBody.path("reference").asText()).isEqualTo("REF-1");
            assertThat(inquiryBody.path("billingNumber").asText()).isEqualTo("8241002201234567");
            assertThat(inquiryBody.path("fromAccount").asText()).isEqualTo("113179933");

            assertThat(payment.status()).isEqualTo(Status.SUCCESS);
            assertThat(payment.coreJournal()).isEqualTo("907409");
            JsonNode paymentBody = json(received.get("payment"));
            assertThat(paymentBody.path("reference").asText()).isEqualTo("REF-1");
            assertThat(paymentBody.path("fromAccount").asText()).isEqualTo("113179933");
            assertThat(paymentBody.path("billingNumber").asText()).isEqualTo("8241002201234567");
            assertThat(paymentBody.path("amount").asText()).isEqualTo("150000.00");
            assertThat(paymentBody.path("inquiryRequestId").asText()).isEqualTo("INQ-1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void inquireVaWithTheHopDisabledIsUnavailableWithoutAnyCall() {
        IntegrationProperties properties = new IntegrationProperties();
        properties.setEnabled(false);
        CoreTransferClient client = new CoreTransferClient(properties, MAPPER);

        assertThatThrownBy(() -> client.inquireVa("REF-1", "8241002201234567", "113179933"))
                .isInstanceOf(ServiceUnavailableException.class);
    }
}
