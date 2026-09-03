package id.co.bni.direct.transaction.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import id.co.bni.direct.transaction.entity.SimsemRows.SimsemAccount;
import id.co.bni.direct.transaction.entity.SimsemRows.SimsemLoad;
import id.co.bni.direct.transaction.repository.mapper.SimsemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The simsem (holding) account pool for cross-currency Transfer ke Bank Lain (P6, tasks
 * 6.1 / 6.2). Reads {@code SIMSEM_ACCOUNT} - a legacy DIRECT table whose rows are seeded
 * per environment by direct DML, never by a migration - and picks ONE account for a task.
 *
 * <p><b>Why a pool at all.</b> The legacy application ran every cross-currency Bank Lain
 * transfer for the whole country through a single simsem account. The new system rotates
 * across several so no one account's balance/throughput is a bottleneck.
 *
 * <p><b>Selection strategy: least-in-flight.</b> Of the accounts registered for the
 * service code and currency, the one carrying the fewest tasks currently mid-two-leg
 * (leg 1 credited, leg 2 not yet terminal - {@code TWO_LEG_STATE = 'LEG1_DONE'}) is
 * chosen; ties break deterministically on ACCOUNT_NO (the list is ordered, so the same
 * account keeps winning until its load rises above a peer's). Least-in-flight rather than
 * round-robin because what matters is not spreading requests evenly but keeping the money
 * that is transiently parked in any one simsem account low - an account with a stuck or
 * UNKNOWN leg carries a non-zero in-flight count and is naturally avoided until it clears.
 *
 * <p><b>Active flag.</b> {@code SIMSEM_ACCOUNT} carries no status column, so every
 * registered row is treated as available; the pool is managed by inserting or removing
 * rows. If a soft-disable is ever needed it belongs as a column on this own table (a
 * future V-migration), not as environment data.
 */
@Service
public class SimsemPool {

    private static final Logger log = LoggerFactory.getLogger(SimsemPool.class);

    private final SimsemMapper simsemMapper;

    public SimsemPool(SimsemMapper simsemMapper) {
        this.simsemMapper = simsemMapper;
    }

    /**
     * The chosen simsem account for a service code and currency, or {@code null} when the
     * pool is empty for that pair (the caller must fail the transfer rather than route it
     * nowhere - money must never be sent to an unregistered holding account).
     */
    public SimsemAccount select(String srvcCd, String ccy) {
        List<SimsemAccount> accounts = simsemMapper.findActiveSimsem(srvcCd, ccy);
        if (accounts.isEmpty()) {
            log.warn("Simsem pool empty for srvcCd={} ccy={}: cross-currency Bank Lain "
                    + "transfer cannot be routed.", srvcCd, ccy);
            return null;
        }
        Map<String, Integer> load = simsemMapper.countInFlightBySimsem().stream()
                .collect(Collectors.toMap(SimsemLoad::accountNo, SimsemLoad::inFlight,
                        (a, b) -> a));
        SimsemAccount chosen = accounts.stream()
                .min(Comparator
                        .comparingInt((SimsemAccount a) -> load.getOrDefault(a.accountNo(), 0))
                        .thenComparing(SimsemAccount::accountNo))
                .orElse(accounts.get(0));
        log.info("Simsem selected {} for srvcCd={} ccy={} (in-flight={} of {} registered)",
                chosen.accountNo(), srvcCd, ccy,
                load.getOrDefault(chosen.accountNo(), 0), accounts.size());
        return chosen;
    }

    /** Helper for callers that only need the account number. */
    public static String accountNo(SimsemAccount account) {
        return account == null ? null : account.accountNo();
    }
}
