package id.co.bni.direct.transaction.entity;

/** Row shapes for the legacy {@code SIMSEM_ACCOUNT} pool table (read-only here). */
public final class SimsemRows {

    private SimsemRows() {
    }

    /**
     * One registered simsem (holding) account. The legacy {@code SIMSEM_ACCOUNT} table
     * carries no active flag, so every registered row is treated as available - the pool
     * is managed by inserting/removing rows, not by a status column (documented in
     * {@code SimsemPool}). {@code srvcAlias} is a display label the legacy table keeps
     * alongside the service code; it is read but not used for routing.
     */
    public record SimsemAccount(
            String id,
            String srvcCd,
            String srvcAlias,
            String accountNo,
            String accountCcy) {
    }

    /**
     * How many two-leg tasks are currently mid-flight on one simsem account - the count
     * the least-in-flight selection strategy minimizes. "Mid-flight" is a task that has
     * completed leg 1 into this account but has not yet reached a terminal two-leg state
     * (TWO_LEG_STATE = 'LEG1_DONE').
     */
    public record SimsemLoad(String accountNo, int inFlight) {
    }
}
