package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bill block a VA billing inquiry answers (P3), as the FE echoes it back on the
 * submit and as it is frozen on {@code TRX_TASK.VA_BILL_JSON} (V11) until the release
 * books the legacy {@code VIRTUAL_ACCOUNT_FT} row from it.
 *
 * <p>The legacy SNAP log showed the inquiry carries the whole presentation block the legacy
 * row stores verbatim - labels, display values, the open/fixed flag, the VA service's own
 * ids - so the service keeps it as one JSON document rather than fourteen columns: nothing
 * here is queried, everything is copied. Keys are the camelCase names the inquiry endpoint
 * answers; unknown keys are ignored on the way in so the VA service may grow the block
 * without a release here.
 *
 * <p>{@code trxType} {@code o} is an <em>open payment</em>: the customer types the amount
 * and {@code billedAmountValue} reads "OPEN PAYMENT". Anything else is a fixed bill whose
 * {@code billedAmount} the submit must match. A missing flag is read as open - the only
 * reading under which the customer's typed amount is never refused on a guess.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record VaBill(
        String trxType,
        String billingLabel,
        String vaNameLabel,
        String billedAmountLabel,
        String billedAmountValue,
        BigDecimal billedAmount,
        String feeAmountLabel,
        String feeAmountValue,
        BigDecimal feeAmount,
        String accountNumberTo,
        String trxId,
        String clientId,
        String additionalLabel1,
        String additionalLabel2,
        String additionalLabel3,
        String additionalValue1,
        String additionalValue2,
        String additionalValue3) {

    private static final Logger log = LoggerFactory.getLogger(VaBill.class);

    /** The open-payment flag as the VA service spells it. */
    public static final String TRX_TYPE_OPEN = "o";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** True for an open payment (customer-typed amount) or when the flag is absent. */
    public boolean isOpen() {
        return trxType == null || trxType.isBlank() || TRX_TYPE_OPEN.equalsIgnoreCase(trxType.trim());
    }

    /** {@code OPEN} or {@code FIXED} - the vocabulary the FE and the task detail use. */
    public String kind() {
        return isOpen() ? "OPEN" : "FIXED";
    }

    /** The block as the JSON text frozen on the task; null when the block is null. */
    public static String toJson(VaBill bill) {
        if (bill == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(bill);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise the VA bill block", e);
        }
    }

    /**
     * The frozen block, or null when the column is empty or does not parse. A row that
     * predates V11 (or whose JSON was hand-edited) must never stop a release: the booking
     * then falls back to the constants the pre-V11 code wrote.
     */
    public static VaBill fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, VaBill.class);
        } catch (JsonProcessingException e) {
            log.warn("VA_BILL_JSON does not parse; booking with the default labels: {}", e.getMessage());
            return null;
        }
    }
}
