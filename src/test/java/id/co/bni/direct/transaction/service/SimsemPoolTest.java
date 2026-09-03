package id.co.bni.direct.transaction.service;

import java.util.List;

import id.co.bni.direct.transaction.entity.SimsemRows.SimsemAccount;
import id.co.bni.direct.transaction.entity.SimsemRows.SimsemLoad;
import id.co.bni.direct.transaction.repository.mapper.SimsemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The least-in-flight selection over a mocked SIMSEM_ACCOUNT / TRX_TASK load. */
class SimsemPoolTest {

    private static final String SRVC = "GCM_FTR_DOM_LLG";

    private SimsemMapper simsemMapper;
    private SimsemPool pool;

    @BeforeEach
    void setUp() {
        simsemMapper = mock(SimsemMapper.class);
        pool = new SimsemPool(simsemMapper);
    }

    private static SimsemAccount account(String no) {
        return new SimsemAccount("ID-" + no, SRVC, "LLG", no, "IDR");
    }

    @Test
    void anEmptyPoolAnswersNullWithoutReadingTheLoad() {
        when(simsemMapper.findActiveSimsem(SRVC, "IDR")).thenReturn(List.of());

        assertThat(pool.select(SRVC, "IDR")).isNull();
        verify(simsemMapper, never()).countInFlightBySimsem();
    }

    @Test
    void theAccountWithTheFewestTasksMidFlightWins() {
        when(simsemMapper.findActiveSimsem(SRVC, "IDR"))
                .thenReturn(List.of(account("9990001"), account("9990002"), account("9990003")));
        when(simsemMapper.countInFlightBySimsem()).thenReturn(List.of(
                new SimsemLoad("9990001", 3),
                new SimsemLoad("9990002", 1),
                new SimsemLoad("9990003", 2)));

        assertThat(pool.select(SRVC, "IDR").accountNo()).isEqualTo("9990002");
    }

    @Test
    void anAccountWithNoInFlightRowCountsAsZeroAndTiesBreakOnAccountNo() {
        when(simsemMapper.findActiveSimsem(SRVC, "IDR"))
                .thenReturn(List.of(account("9990001"), account("9990002"), account("9990003")));
        // 9990001 carries load; the other two have no row (zero) and tie.
        when(simsemMapper.countInFlightBySimsem()).thenReturn(List.of(
                new SimsemLoad("9990001", 1)));

        assertThat(pool.select(SRVC, "IDR").accountNo()).isEqualTo("9990002");
    }

    @Test
    void loadOnAccountsOutsideThePoolIsIgnored() {
        when(simsemMapper.findActiveSimsem(SRVC, "IDR")).thenReturn(List.of(account("9990001")));
        when(simsemMapper.countInFlightBySimsem()).thenReturn(List.of(
                new SimsemLoad("8880001", 9)));

        assertThat(pool.select(SRVC, "IDR").accountNo()).isEqualTo("9990001");
        assertThat(SimsemPool.accountNo(null)).isNull();
    }
}
