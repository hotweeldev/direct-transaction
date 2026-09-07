package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;

/**
 * The daily transaction ceilings, reserved at submit instead of counted at release.
 *
 * <p>WHY RESERVATION. Until this existed, usage was incremented only when a transfer was
 * released, so ten pending transfers each validated against the same untouched ceiling and
 * all ten passed. The tenth one failed at release - after its whole approval chain had
 * signed it off. A reservation makes the ceiling mean what it says at the moment a maker
 * commits to spending it.
 *
 * <p>WHAT A LIMIT ACTUALLY IS, which is not what the code assumed for a long time. One row
 * is a ceiling for a (company, service, CURRENCY COMBINATION) - the combination being
 * LL / LF / FL / FS / FC, derived from the source account's currency and the transaction's.
 * It carries TWO ceilings: an amount, denominated in the row's own {@code CCY_CD}, and a
 * transaction COUNT with no currency at all. Both are per day. So an amount has to be
 * converted into the row's currency before it is compared or added, and a transfer has to
 * clear both ceilings, not one.
 *
 * <p>A ceiling of zero blocks the service. That reading was confirmed rather than inferred:
 * the alternative, treating zero as "unlimited", would open 977 company rows that the bank
 * set to zero on purpose.
 */
public interface LimitService {

    /** The five combinations of source-account currency and transaction currency. */
    enum CurrencyCombination {
        /** Both local. */
        LL,
        /** Source local, transaction foreign. */
        LF,
        /** Source foreign, transaction local. */
        FL,
        /** Both foreign, same currency. */
        FS,
        /** Both foreign, different currencies. */
        FC;

        /**
         * Which combination a transfer falls into. Null currencies are read as local: the
         * in-house path leaves the source currency unknown when it never had to look, and
         * that path is IDR-only anyway.
         */
        public static CurrencyCombination of(String sourceCcy, String trxCcy) {
            boolean sourceLocal = isLocal(sourceCcy);
            boolean trxLocal = isLocal(trxCcy);
            if (sourceLocal && trxLocal) {
                return LL;
            }
            if (sourceLocal) {
                return LF;
            }
            if (trxLocal) {
                return FL;
            }
            return normalise(sourceCcy).equals(normalise(trxCcy)) ? FS : FC;
        }

        private static boolean isLocal(String ccy) {
            return ccy == null || ccy.isBlank() || "IDR".equalsIgnoreCase(ccy.trim());
        }

        private static String normalise(String ccy) {
            return ccy == null ? "" : ccy.trim().toUpperCase();
        }
    }

    /**
     * What one task reserved, frozen on it so a release subtracts exactly what was added.
     *
     * <p>{@code amount} is in {@code ccyCd} - the denomination of the limit row, which is
     * not necessarily the transaction's own currency. {@code srvcCcyMtrxId} identifies the
     * limit line; {@code ccyMtrxCd} is its combination, kept because it is derived from the
     * source account's currency and that can be changed by an administrator while the task
     * waits for approval.
     */
    record Reservation(String srvcCcyMtrxId, String ccyMtrxCd, String ccyCd, BigDecimal amount) {

        /** Nothing was reserved: no ceiling row applies to this transfer. */
        public static Reservation none() {
            return new Reservation(null, null, null, null);
        }

        public boolean isEmpty() {
            return srvcCcyMtrxId == null || amount == null;
        }
    }

    /**
     * Check both daily ceilings and take the reservation, inside the caller's transaction.
     *
     * <p>Company and user-group rows are both consumed - they are the only two that carry
     * usage columns. The per-transaction ceilings ({@code BANK_TRX_LMT},
     * {@code AUTH_LMT_SCHEME.MAKE_LMT}) accumulate nothing and stay where they are, in the
     * submit pipeline.
     *
     * @param amount the debited total in {@code trxCcy}, charges included where the sender
     *               pays them
     * @throws id.co.bni.direct.transaction.exception.BusinessRuleException when a ceiling
     *         refuses the transfer, when the service is blocked by a zero ceiling, or when
     *         the amount cannot be expressed in the ceiling's currency
     */
    Reservation reserve(String companyId, String makerGroupId, String srvcCd,
                        String sourceCcy, String trxCcy, BigDecimal amount, String actor);

    /**
     * Give a reservation back, for a task that will never execute - rejected by an approver
     * or refused by core banking.
     *
     * <p>An {@code UNKNOWN} execution does NOT come here: the money may already have moved,
     * so the ceiling stays consumed until reconciliation says what happened. Releasing it
     * early would let the customer send the same amount twice.
     */
    void release(String companyId, String makerGroupId, String srvcCd, Reservation reservation);
}
