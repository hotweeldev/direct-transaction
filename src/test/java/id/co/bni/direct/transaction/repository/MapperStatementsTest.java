package id.co.bni.direct.transaction.repository;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import id.co.bni.direct.transaction.entity.TrxTaskRows;
import id.co.bni.direct.transaction.repository.mapper.ActivityMapper;
import id.co.bni.direct.transaction.repository.mapper.ExecutionOutboxMapper;
import id.co.bni.direct.transaction.repository.mapper.SimsemMapper;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parses every mapper XML and checks each declared mapper method has a statement behind it.
 *
 * <p>No Oracle connection is involved. This catches the failure that otherwise only shows
 * up at runtime: a method renamed in the interface and not in the XML, which Spring wires
 * happily and which throws on first call.
 */
class MapperStatementsTest {

    private static Configuration configuration;

    @BeforeAll
    static void loadMappers() throws Exception {
        try (InputStream config = Resources.getResourceAsStream("mybatis-test-config.xml")) {
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
            configuration = factory.getConfiguration();
        }
    }

    private static void assertEveryMethodHasAStatement(Class<?> mapperType) {
        List<String> missing = Arrays.stream(mapperType.getDeclaredMethods())
                .map(method -> mapperType.getName() + "." + method.getName())
                .filter(id -> !configuration.hasStatement(id))
                .toList();

        assertThat(missing).as("mapper methods with no statement").isEmpty();
    }

    @Test
    void everyTransferMapperMethodHasAStatement() {
        assertEveryMethodHasAStatement(TransferMapper.class);
    }

    @Test
    void everyTrxTaskMapperMethodHasAStatement() {
        assertEveryMethodHasAStatement(TrxTaskMapper.class);
    }

    /**
     * insertTask reads the V5 domestic block through a NESTED record property
     * ({@code #{domestic.benBnkCd}}), which must bind NULLs when the block is absent
     * (in-house tasks) and real values when present. This drives the actual
     * ParameterHandler over a mocked PreparedStatement - the one failure mode the
     * service-level tests (mocked mappers) can never see.
     */
    @Test
    void insertTaskBindsTheNestedDomesticBlockPresentOrNull() throws Exception {
        var statement = configuration.getMappedStatement(
                TrxTaskMapper.class.getName() + ".insertTask");
        var inHouse = new TrxTaskRows.TaskInsert("T1", "CORP1", "MNU", "SRVC", "REF1",
                "PENDING_APPROVAL", 1, "111", "222", "NAME", java.math.BigDecimal.ONE,
                "IDR", null, null, null, "IMMEDIATE", "CU1", "MAKER", "N", "tester");
        var domestic = new TrxTaskRows.TaskInsert("T2", "CORP1", "MNU", "SRVC", "REF2",
                "PENDING_APPROVAL", 1, "111", "222", "NAME", java.math.BigDecimal.ONE,
                "IDR", null, null, null, "IMMEDIATE", "CU1", "MAKER", "N", "tester",
                new TrxTaskRows.DomesticInsert("DB1", "0140397", "BANK", "CENAIDJA",
                        "a1", null, null, "0811", "12345", "01", "3171", "1", "1", "1",
                        java.math.BigDecimal.TEN));
        var cross = new TrxTaskRows.TaskInsert("T3", "CORP1", "MNU", "SRVC", "REF3",
                "PENDING_APPROVAL", 1, "111", "222", "NAME", java.math.BigDecimal.ONE,
                "USD", null, null, null, "IMMEDIATE", "CU1", "MAKER", "N", "tester",
                null,
                new TrxTaskRows.CrossInsert("USD", java.math.BigDecimal.TEN,
                        new java.math.BigDecimal("16500"), new java.math.BigDecimal("165000"),
                        "02", "DEP", "advisory", "INV", "001", "Invoice", null, null));
        // P3: the VA shape - fee-only domestic block plus the V9 inquiry id (the 23rd,
        // top-level property the statement binds directly).
        var virtualAccount = new TrxTaskRows.TaskInsert("T4", "CORP1", "MNU", "GCM_VA_BILLING", "REF4",
                "PENDING_APPROVAL", 1, "111", "8241002201234567", "PT TOKOPEDIA",
                java.math.BigDecimal.ONE, "IDR", null, null, null, "IMMEDIATE", "CU1", "MAKER",
                "N", "tester",
                new TrxTaskRows.DomesticInsert(null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, java.math.BigDecimal.ZERO),
                null, "INQ-1");
        for (Object param : new Object[]{inHouse, domestic, cross, virtualAccount}) {
            var boundSql = statement.getBoundSql(param);
            var handler = configuration.newParameterHandler(statement, param, boundSql);
            // Throws if any #{...} path (nested or not) cannot be resolved on the records.
            handler.setParameters(org.mockito.Mockito.mock(java.sql.PreparedStatement.class));
        }
    }

    @Test
    void everyExecutionOutboxMapperMethodHasAStatement() {
        assertEveryMethodHasAStatement(ExecutionOutboxMapper.class);
    }

    @Test
    void everyActivityMapperMethodHasAStatement() {
        assertEveryMethodHasAStatement(ActivityMapper.class);
    }

    @Test
    void everySimsemMapperMethodHasAStatement() {
        assertEveryMethodHasAStatement(SimsemMapper.class);
    }
}
