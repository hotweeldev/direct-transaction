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


    // ---- P7: the BI-Fast verdicts and wire ----

    @Test
    void aBiFastCreditEnvelopeMapsTheSharedVerdictPlusTheSwitchIdentifiers() {
        var success = CoreTransferClient.mapBiFastResponse(200, json(
                "{\"data\":{\"coreJournal\":\"900067\",\"trxId\":\"20250925BNINIDJA01075210687\","
                        + "\"endToEndId\":\"20250925BNINIDJA010O0175210687\",\"reasonCode\":\"U000\"}}"));
        assertThat(success.status()).isEqualTo(Status.SUCCESS);
        assertThat(success.coreJournal()).isEqualTo("900067");
        assertThat(success.trxId()).isEqualTo("20250925BNINIDJA01075210687");
        assertThat(success.endToEndId()).isEqualTo("20250925BNINIDJA010O0175210687");

        // 2xx without a journal is UNKNOWN, never success - identifiers still read.
        var noJournal = CoreTransferClient.mapBiFastResponse(200, json(
                "{\"data\":{\"trxId\":\"X1\",\"reasonCode\":\"U000\"}}"));
        assertThat(noJournal.status()).isEqualTo(Status.UNKNOWN);
        assertThat(noJournal.trxId()).isEqualTo("X1");

        var refused = CoreTransferClient.mapBiFastResponse(422, json(
                "{\"code\":\"BUSINESS_RULE\",\"message\":\"(SOA) ACCOUNT NOT ABLE TO DO TRANSACTION\"}"));
        assertThat(refused.status()).isEqualTo(Status.REFUSED);
        assertThat(refused.message()).contains("ACCOUNT NOT ABLE");
        assertThat(refused.trxId()).isNull();

        assertThat(CoreTransferClient.mapBiFastResponse(504, null).status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void aBiFastInquiryFourTwentyTwoIsTheSwitchsRefusalAndAFiveXxIsUnavailable() {
        assertThatThrownBy(() -> CoreTransferClient.mapBiFastInquiryResponse(422, json(
                "{\"code\":\"BIFAST_INQUIRY_REJECTED\",\"message\":\"(SOA) ACCOUNT NOT ABLE TO DO TRANSACTION\"}")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("(SOA) ACCOUNT NOT ABLE TO DO TRANSACTION")
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("BIFAST_INQUIRY_REJECTED"));
        assertThatThrownBy(() -> CoreTransferClient.mapBiFastInquiryResponse(502, null))
                .isInstanceOf(ServiceUnavailableException.class);

        var ok = CoreTransferClient.mapBiFastInquiryResponse(200, json(
                "{\"data\":{\"creditorName\":\"Vastarion\",\"creditorId\":\"23231453124123\","
                        + "\"creditorType\":\"01\",\"creditorAccountType\":\"SVGS\","
                        + "\"creditorResidentStatus\":\"01\",\"creditorTownName\":\"0300\","
                        + "\"settlementDate\":\"2026-09-04\",\"reasonCode\":\"U000\"}}"));
        assertThat(ok.creditorName()).isEqualTo("Vastarion");
        assertThat(ok.creditorAccountType()).isEqualTo("SVGS");
        assertThat(ok.settlementDate()).isEqualTo("2026-09-04");
        assertThat(ok.creditorRegistrationId()).isNull();
    }

    @Test
    void transferBiFastAndInquireBiFastPostToTheirPathsWithTheContractFieldNames() throws Exception {
        var received = new java.util.concurrent.ConcurrentHashMap<String, String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/core/transfers/bifast/inquiry", exchange -> {
            received.put("inquiry", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"data\":{\"creditorName\":\"Vastarion\",\"creditorId\":\"23231453124123\","
                    + "\"creditorType\":\"01\",\"settlementDate\":\"2026-09-04\",\"reasonCode\":\"U000\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/internal/v1/core/transfers/bifast", exchange -> {
            received.put("credit", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            received.put("creditApiKey", exchange.getRequestHeaders().getFirst("X-Api-Key"));
            byte[] body = ("{\"data\":{\"coreJournal\":\"900067\",\"trxId\":\"T-1\","
                    + "\"endToEndId\":\"E-1\",\"reasonCode\":\"U000\",\"message\":\"OK\"}}")
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

            CoreTransferClient.BiFastInquiry inquiry = client.inquireBiFast(
                    new CoreTransferClient.BiFastInquiryInstruction("REF-1", "113179933", "10000.00",
                            "2500.00", "BMRIIDJA", "9876543210", null, null, "01"));
            CoreTransferClient.BiFastOutcome credit = client.transferBiFast(
                    new CoreTransferClient.BiFastInstruction("REF-2", "113179933", "9876543210",
                            "10000.00", "2500.00", "BMRIIDJA", "Vastarion", "23231453124123", "01",
                            "SVGS", "01", "0300", null, null, "bayar", "2026-09-04", "01"));

            assertThat(inquiry.creditorName()).isEqualTo("Vastarion");
            JsonNode inquiryBody = json(received.get("inquiry"));
            assertThat(inquiryBody.path("reference").asText()).isEqualTo("REF-1");
            assertThat(inquiryBody.path("fromAccount").asText()).isEqualTo("113179933");
            assertThat(inquiryBody.path("amount").asText()).isEqualTo("10000.00");
            assertThat(inquiryBody.path("fee").asText()).isEqualTo("2500.00");
            assertThat(inquiryBody.path("receivingBic").asText()).isEqualTo("BMRIIDJA");
            assertThat(inquiryBody.path("toAccount").asText()).isEqualTo("9876543210");
            assertThat(inquiryBody.path("transactionPurpose").asText()).isEqualTo("01");

            assertThat(credit.status()).isEqualTo(Status.SUCCESS);
            assertThat(credit.coreJournal()).isEqualTo("900067");
            assertThat(credit.trxId()).isEqualTo("T-1");
            assertThat(credit.endToEndId()).isEqualTo("E-1");
            assertThat(received.get("creditApiKey")).isEqualTo("k-1");
            JsonNode creditBody = json(received.get("credit"));
            assertThat(creditBody.path("reference").asText()).isEqualTo("REF-2");
            assertThat(creditBody.path("creditorId").asText()).isEqualTo("23231453124123");
            assertThat(creditBody.path("creditorAccountType").asText()).isEqualTo("SVGS");
            assertThat(creditBody.path("settlementDate").asText()).isEqualTo("2026-09-04");
            assertThat(creditBody.path("transactionPurpose").asText()).isEqualTo("01");
            assertThat(creditBody.path("description").asText()).isEqualTo("bayar");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void inquireBiFastWithTheHopDisabledIsUnavailableWithoutAnyCall() {
        IntegrationProperties properties = new IntegrationProperties();
        properties.setEnabled(false);
        CoreTransferClient client = new CoreTransferClient(properties, MAPPER);

        assertThatThrownBy(() -> client.inquireBiFast(new CoreTransferClient.BiFastInquiryInstruction(
                "REF-1", "113179933", "10000.00", "2500.00", "BMRIIDJA", "9876543210", null, null, "01")))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    /**
     * The real SNAP success body (legacy log 2023-05-31, passed through by
     * direct-integration under its own camelCase names): the name lives in
     * virtualAccountName, blank strings mean absent, and the whole bill block rides
     * along for the legacy VIRTUAL_ACCOUNT_FT row.
     */
    @Test
    void aVaInquiryReadsTheFullSnapBillBlock() {
        CoreTransferClient.VaInquiry bill = CoreTransferClient.mapVaInquiryResponse(200, json(
                "{\"data\":" + "{\"responseCode\":\"000\",\"responseMessage\":\"Success\",\"clientId\":\"320\",\"trxId\":\"1496387780\",\"billingNumber\":\"8320211228147123\",\"billingLabel\":\"No.VA\",\"virtualAccountNumber\":\"8320211228147123\",\"virtualAccountName\":\"test66666\",\"vaNameLabel\":\"Nama\",\"virtualAccountTrxType\":\"o\",\"billedAmountLabel\":\"Minimum Bayar\",\"billedAmountValue\":\"OPEN PAYMENT\",\"billedAmount\":\"0\",\"additionalLabel1\":\"\",\"additionalLabel2\":\"\",\"additionalLabel3\":\"\",\"additionalValue1\":\"\",\"additionalValue2\":\"\",\"additionalValue3\":\"\",\"feeAmountLabel\":\"Biaya admin\",\"feeAmountValue\":\"Rp0\",\"feeAmount\":\"0\",\"accountNumberTo\":\"\",\"inquiryRequestId\":\"\"}" + "}"), "8320211228147123");

        assertThat(bill.billingNumber()).isEqualTo("8320211228147123");
        assertThat(bill.billingName()).isEqualTo("test66666");
        assertThat(bill.virtualAccountName()).isEqualTo("test66666");
        assertThat(bill.virtualAccountTrxType()).isEqualTo("o");
        assertThat(bill.billedAmount()).isEqualByComparingTo("0");
        assertThat(bill.billedAmountLabel()).isEqualTo("Minimum Bayar");
        assertThat(bill.billedAmountValue()).isEqualTo("OPEN PAYMENT");
        assertThat(bill.feeAmount()).isEqualByComparingTo("0");
        assertThat(bill.feeAmountLabel()).isEqualTo("Biaya admin");
        assertThat(bill.feeAmountValue()).isEqualTo("Rp0");
        assertThat(bill.trxId()).isEqualTo("1496387780");
        assertThat(bill.clientId()).isEqualTo("320");
        assertThat(bill.responseCode()).isEqualTo("000");
        // "" is how the service spells "absent".
        assertThat(bill.inquiryRequestId()).isNull();
        assertThat(bill.accountNumberTo()).isNull();
        assertThat(bill.additionalLabel1()).isNull();
        assertThat(bill.additionalValue3()).isNull();
    }
}
