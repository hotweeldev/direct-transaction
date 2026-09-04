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
        // P7: the BI-Fast shape - domestic block (participant BIC, fee) plus the V10
        // nested block bound through #{bifast.*}; every earlier shape binds NULLs there.
        var bifast = new TrxTaskRows.TaskInsert("T5", "CORP1", "MNU", "GCM_FTR_DOM_BIFAST", "REF5",
                "PENDING_APPROVAL", 1, "111", "9876543210", "NAME", java.math.BigDecimal.ONE,
                "IDR", null, null, null, "IMMEDIATE", "CU1", "MAKER", "N", "tester",
                new TrxTaskRows.DomesticInsert("DB2", "BMRIIDJA", "BANK", "BMRIIDJA", null, null, null,
                        null, null, null, null, null, null, null, new java.math.BigDecimal("2500")),
                null, null,
                new TrxTaskRows.BiFastInsert("01", "23231453124123", "01", "SVGS", "01", "0300",
                        "2026-09-04", null, null));
        // V11: the VA shape with the frozen bill block, the 25th top-level property.
        var virtualAccountBill = new TrxTaskRows.TaskInsert("T6", "CORP1", "MNU", "GCM_VA_BILLING", "REF6",
                "PENDING_APPROVAL", 1, "111", "8241002201234567", "PT TOKOPEDIA",
                java.math.BigDecimal.ONE, "IDR", null, null, null, "IMMEDIATE", "CU1", "MAKER",
                "N", "tester",
                new TrxTaskRows.DomesticInsert(null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, new java.math.BigDecimal("2500")),
                null, null, null, "{\"trxType\":\"o\",\"feeAmount\":2500}");
        for (Object param : new Object[]{inHouse, domestic, cross, virtualAccount, bifast, virtualAccountBill}) {
            var boundSql = statement.getBoundSql(param);
            var handler = configuration.newParameterHandler(statement, param, boundSql);
            // Throws if any #{...} path (nested or not) cannot be resolved on the records.
            handler.setParameters(org.mockito.Mockito.mock(java.sql.PreparedStatement.class));
        }
    }

    /** insertBaseFtDom binds the P7 identifiers when present and NULLs on every older shape. */
    @Test
    void insertBaseFtDomBindsTheBiFastColumnsPresentOrNull() throws Exception {
        var statement = configuration.getMappedStatement(
                TransferMapper.class.getName() + ".insertBaseFtDom");
        var llg = new id.co.bni.direct.transaction.entity.TransferRows.BaseFtDomInsert(
                "F1", "cls", "MNU", "SRVC", "REF", "TRX", "111", "222", "NAME",
                java.math.BigDecimal.TEN, "DB1", null, null, null, "1", "1", "1", "CENAIDJA",
                "CU1", "CU9");
        var bifast = new id.co.bni.direct.transaction.entity.TransferRows.BaseFtDomInsert(
                "F2", "cls", "MNU", "SRVC", "REF", "TRX", "111", "222", "NAME",
                java.math.BigDecimal.TEN, "DB2", null, null, null, null, null, null, "BMRIIDJA",
                "CU1", "CU9", null, null, "01", "01", null, null, "T-1", "E-1");
        for (Object param : new Object[]{llg, bifast}) {
            var boundSql = statement.getBoundSql(param);
            var handler = configuration.newParameterHandler(statement, param, boundSql);
            handler.setParameters(org.mockito.Mockito.mock(java.sql.PreparedStatement.class));
        }
    }

    /** insertVirtualAccountFt binds the V11 bill labels when present and the defaults otherwise. */
    @Test
    void insertVirtualAccountFtBindsTheBillLabelsPresentOrDefault() throws Exception {
        var statement = configuration.getMappedStatement(
                TransferMapper.class.getName() + ".insertVirtualAccountFt");
        var defaults = new id.co.bni.direct.transaction.entity.TransferRows.VaFtInsert(
                "V1", "CORP1", "8241002201234567", "IDR", new java.math.BigDecimal("153000"),
                "PT TOKOPEDIA", new java.math.BigDecimal("150000"), "Rp150000",
                new java.math.BigDecimal("3000"), "Rp3000", "111", "REF", "TRX", "remark",
                "CU1", "CU9");
        var withBill = new id.co.bni.direct.transaction.entity.TransferRows.VaFtInsert(
                "V2", "CORP1", "8320211228147123", "IDR", new java.math.BigDecimal("152500"),
                "test66666", new java.math.BigDecimal("150000"), "OPEN PAYMENT",
                new java.math.BigDecimal("2500"), "Rp2500", "111", "REF", "TRX", "remark",
                "CU1", "CU9", "No.VA", "Nama", "o", "Minimum Bayar", "Biaya admin",
                "1000665901", "Periode", null, null, "2026-09", null, null, "1496387780");
        for (Object param : new Object[]{defaults, withBill}) {
            var boundSql = statement.getBoundSql(param);
            var handler = configuration.newParameterHandler(statement, param, boundSql);
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
