package id.co.bni.direct.transaction.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Times every statement MyBatis sends to Oracle.
 *
 * <p>Micrometer instruments the Hikari pool but stops at the connection: {@code
 * hikaricp.connections.pending} says the pool is exhausted without saying which query
 * exhausted it. This fills that gap with one timer per mapper statement, which is the
 * same granularity as the SQL in {@code resources/mapper/*.xml} - the Dapper calls this
 * service was ported from had no equivalent at all.
 *
 * <p>Wrapping the {@code Executor} rather than the {@code StatementHandler} is deliberate:
 * the executor sees one invocation per mapper method, so a statement is counted once
 * whether or not MyBatis reuses a prepared statement underneath.
 *
 * <p>The {@code statement} tag is the mapper id with the package stripped
 * ({@code ApplicationMapper.findByApplicationNo}). Mapper ids are a fixed set defined at
 * startup, so the tag cannot explode the way a raw SQL string or a bind value would.
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class MyBatisMetricsInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(MyBatisMetricsInterceptor.class);

    static final String TIMER_NAME = "mybatis.statement";

    private final MeterRegistry registry;

    public MyBatisMetricsInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        Timer.Sample sample = Timer.start(registry);
        String exception = "none";
        try {
            return invocation.proceed();
        } catch (Throwable t) {
            // proceed() reflects into the executor, so a failed statement arrives wrapped in
            // an InvocationTargetException; tagging that would label every Oracle error the
            // same. Plugin.invoke unwraps it anyway, so rethrowing the cause changes nothing
            // downstream. Simple name only: an ORA-xxxxx message carries bind values.
            Throwable cause = ExceptionUtil.unwrapThrowable(t);
            exception = cause.getClass().getSimpleName();
            throw cause;
        } finally {
            long elapsedNanos = sample.stop(Timer.builder(TIMER_NAME)
                    .description("Time a MyBatis mapper statement spent in Oracle")
                    .tag("statement", shortId(statement.getId()))
                    .tag("command", statement.getSqlCommandType().name())
                    .tag("exception", exception)
                    .tag("outcome", "none".equals(exception) ? "SUCCESS" : "FAILURE")
                    .register(registry));
            // The timer aggregates across all traffic, so it says which statement is slow
            // but never which request made it slow. This line inherits correlationId from
            // the MDC and closes that gap. DEBUG because it is one line per statement.
            if (log.isDebugEnabled()) {
                log.debug("mybatis {} {} took {} ms ({})", statement.getSqlCommandType(),
                        shortId(statement.getId()), elapsedNanos / 1_000_000L, exception);
            }
        }
    }

    /** {@code id.co...mapper.ApplicationMapper.findById} -> {@code ApplicationMapper.findById}. */
    private static String shortId(String mappedStatementId) {
        int lastDot = mappedStatementId.lastIndexOf('.');
        if (lastDot < 0) {
            return mappedStatementId;
        }
        int classDot = mappedStatementId.lastIndexOf('.', lastDot - 1);
        return classDot < 0 ? mappedStatementId : mappedStatementId.substring(classDot + 1);
    }
}
