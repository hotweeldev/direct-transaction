package id.co.bni.direct.transaction.service;

/**
 * The transfer products this workflow engine knows. The wire value rides in
 * {@code SubmitTransferRequest.transferType} (absent = BNI, the P0 behavior); persisted
 * state derives the type back from the task's resolved SRVC_CD, so no extra column exists.
 *
 * <p>BNI resolves to one of two service codes at submit (own vs third-party beneficiary);
 * LLG, RTGS and ONLINE (P2: RTOL / ATM Bersama) each map to exactly one domestic service
 * code, which is also what the limit ladder keys on (tasks 1.5 / 2.3).
 */
public enum TransferType {

    BNI, LLG, RTGS, ONLINE, VA, BIFAST;

    public static final String SRVC_DOM_LLG = "GCM_FTR_DOM_LLG";
    public static final String SRVC_DOM_RTGS = "GCM_FTR_DOM_RTGS";
    public static final String SRVC_DOM_ONLINE = "GCM_FTR_DOM_ONLINE";
    /**
     * Transfer ke Bank Lain via BI-Fast (P7): the legacy domestic service code the
     * limits (BANK_TRX_LMT, CORP_LMT_PC_DTL) are keyed on; the approval matrix is keyed
     * on the shared Bank Lain menu like LLG/RTGS/ONLINE.
     */
    public static final String SRVC_DOM_BIFAST = "GCM_FTR_DOM_BIFAST";
    /**
     * Transfer ke Virtual Account (P3): the legacy COM_ST_SRVC "VA Billing Single" service
     * (currency category IDR). Limits (BANK_TRX_LMT, CORP_LMT_PC_DTL) are keyed on it in
     * the legacy data; the approval matrix is keyed on the menu, see
     * {@code TransferServiceImpl.MENU_CD_VA}.
     */
    public static final String SRVC_VA = "GCM_VA_BILLING";

    /** Absent/blank means BNI; anything unknown is the caller's error, not a default. */
    public static TransferType fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return BNI;
        }
        return switch (wire.trim().toUpperCase()) {
            case "BNI" -> BNI;
            case "LLG", "SKN", "KLIRING" -> LLG;
            case "RTGS" -> RTGS;
            case "ONLINE", "RTOL" -> ONLINE;
            case "VA", "VIRTUAL_ACCOUNT" -> VA;
            case "BIFAST", "BI-FAST", "BI_FAST" -> BIFAST;
            default -> throw new IllegalArgumentException("Jenis transfer tidak dikenal: " + wire);
        };
    }

    /** The type a stored task belongs to, off its resolved service code. */
    public static TransferType fromServiceCode(String srvcCd) {
        if (SRVC_DOM_LLG.equals(srvcCd)) {
            return LLG;
        }
        if (SRVC_DOM_RTGS.equals(srvcCd)) {
            return RTGS;
        }
        if (SRVC_DOM_ONLINE.equals(srvcCd)) {
            return ONLINE;
        }
        if (SRVC_VA.equals(srvcCd)) {
            return VA;
        }
        if (SRVC_DOM_BIFAST.equals(srvcCd)) {
            return BIFAST;
        }
        return BNI;
    }

    /** A Transfer ke Bank Lain product: the beneficiary lives at another bank. */
    public boolean isDomestic() {
        return this == LLG || this == RTGS || this == ONLINE || this == BIFAST;
    }

    /** BI-Fast (P7): a domestic product with its own two-call wire (inquiry-transfer, credit-transfer). */
    public boolean isBiFast() {
        return this == BIFAST;
    }

    /** Transfer ke Virtual Account: neither in-house nor domestic - its own wire and booking table. */
    public boolean isVirtualAccount() {
        return this == VA;
    }
}
