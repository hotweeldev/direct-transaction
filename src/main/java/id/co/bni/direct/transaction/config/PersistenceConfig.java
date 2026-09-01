package id.co.bni.direct.transaction.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * MyBatis wiring. Connections come from the single HikariCP {@code DataSource} in
 * {@code application.yml}, and {@code @Transactional} on a service method covers every
 * statement it issues.
 */
@Configuration
@EnableTransactionManagement
@MapperScan("id.co.bni.direct.transaction.repository.mapper")
public class PersistenceConfig {

    /**
     * mybatis-spring-boot-starter picks up every {@code Interceptor} bean and adds it to
     * the MyBatis {@code Configuration}, so declaring it is the whole registration.
     */
    @Bean
    public MyBatisMetricsInterceptor myBatisMetricsInterceptor(MeterRegistry registry) {
        return new MyBatisMetricsInterceptor(registry);
    }
}
