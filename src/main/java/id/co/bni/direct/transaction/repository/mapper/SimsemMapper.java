package id.co.bni.direct.transaction.repository.mapper;

import java.util.List;

import id.co.bni.direct.transaction.entity.SimsemRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * The legacy {@code SIMSEM_ACCOUNT} pool (read only, house rule: point at the legacy
 * table, never copy it) plus the in-flight load count that drives least-in-flight
 * selection. Statements live in {@code resources/mapper/SimsemMapper.xml}; every method
 * here must have a matching statement id there ({@code MapperStatementsTest}).
 */
@Mapper
public interface SimsemMapper {

    /**
     * The registered simsem accounts for one service code and currency. Every row is
     * treated as active (the table carries no status column); ordered by ACCOUNT_NO so
     * selection is deterministic when loads tie.
     */
    List<SimsemRows.SimsemAccount> findActiveSimsem(@Param("srvcCd") String srvcCd,
                                                    @Param("ccy") String ccy);

    /**
     * How many tasks are currently mid-two-leg (TWO_LEG_STATE = 'LEG1_DONE') per simsem
     * account - the load the selection strategy minimizes. Accounts with no in-flight
     * task do not appear here; the pool service defaults them to zero.
     */
    List<SimsemRows.SimsemLoad> countInFlightBySimsem();
}
