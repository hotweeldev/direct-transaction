package id.co.bni.direct.transaction.repository;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import id.co.bni.direct.transaction.repository.mapper.ActivityMapper;
import id.co.bni.direct.transaction.repository.mapper.ExecutionOutboxMapper;
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

    @Test
    void everyExecutionOutboxMapperMethodHasAStatement() {
        assertEveryMethodHasAStatement(ExecutionOutboxMapper.class);
    }

    @Test
    void everyActivityMapperMethodHasAStatement() {
        assertEveryMethodHasAStatement(ActivityMapper.class);
    }
}
