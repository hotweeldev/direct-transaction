package id.co.bni.direct.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Per-method constants for the domestic transfer types (P1). Two kinds of value live
 * here, both config-driven because neither has a reference table in the legacy DIRECT
 * schema (verified 2026-09-02 - no LOOKUP_VALUE/SYS_PARAM/COM_MT_PARAM row carries them):
 *
 * <ul>
 *   <li><b>Fees</b> - flat placeholder per method until the P4 charge engine. Defaults
 *       match the DEV {@code SYS_PARAM_TRF_SME_LLG} / {@code SYS_PARAM_TRF_SME_RTGS}
 *       display parameters (IDR 2,900 / IDR 30,000). The debited total is always
 *       {@code amount + fee}, and the limit ladder validates that total.</li>
 *   <li><b>TSA codes</b> - the SOA samples' constants: kliring {@code sandiTransaksi='50'},
 *       RTGS {@code kodeTSA='IFT00000'}; plus the RTGS intermediary branch default
 *       {@code '760'}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.transfer")
public class TransferTypeProperties {

    private final Method llg = new Method(new BigDecimal("2900"), "50");
    private final Method rtgs = new Method(new BigDecimal("30000"), "IFT00000");
    /** P2 ONLINE (RTOL / ATM Bersama): fee mirrors DEV SYS_PARAM_TRF_SME_ONLINE; no TSA code. */
    private final Method online = new Method(new BigDecimal("6500"), null);
    /**
     * P3 Transfer ke Virtual Account: flat fee placeholder until the P4 charge engine.
     * Zero by default - the legacy sample rows book "Biaya admin Rp0" and DEV SYS_PARAM
     * carries no VA fee row (SYS_PARAM_1006/1007 are the VA number formats, not a fee);
     * override per environment with TRANSFER_VA_FEE. No TSA code on the VA wire.
     */
    private final Method va = new Method(BigDecimal.ZERO, null);

    /** Sent as {@code intermediaryBranch}; mandatory on the RTGS operation. */
    private String intermediaryBranch = "760";

    /**
     * P6 reconciliation: the {@code transactionType} code passed through to core
     * banking's {@code DepTrxInquiry} (via direct-integration's /transfers/status) when
     * a two-leg task's leg 2 is looked for on the simsem account. The code list is not
     * in the spec extract this service was built from (direct-integration README confirm
     * item 24), so it is configuration, never a constant in code.
     */
    private String reconcileTransactionType = "01";

    public Method getLlg() {
        return llg;
    }

    public Method getRtgs() {
        return rtgs;
    }

    public Method getOnline() {
        return online;
    }

    public Method getVa() {
        return va;
    }

    public String getIntermediaryBranch() {
        return intermediaryBranch;
    }

    public void setIntermediaryBranch(String intermediaryBranch) {
        this.intermediaryBranch = intermediaryBranch;
    }

    public String getReconcileTransactionType() {
        return reconcileTransactionType;
    }

    public void setReconcileTransactionType(String reconcileTransactionType) {
        this.reconcileTransactionType = reconcileTransactionType;
    }

    public static class Method {

        private BigDecimal fee;
        private String tsaCode;

        Method(BigDecimal fee, String tsaCode) {
            this.fee = fee;
            this.tsaCode = tsaCode;
        }

        public BigDecimal getFee() {
            return fee;
        }

        public void setFee(BigDecimal fee) {
            this.fee = fee;
        }

        public String getTsaCode() {
            return tsaCode;
        }

        public void setTsaCode(String tsaCode) {
            this.tsaCode = tsaCode;
        }
    }
}
