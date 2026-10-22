package com.duozhi.outbound;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Opt-in timing only. No SQL text, bind values or customer data are logged.
 * Elapsed time includes JDBC borrowing; use wait stacks to separate it from SQL. */
@Component
@ConditionalOnProperty(name="JAVA_DIAL_PROFILE",havingValue="true")
@Intercepts({
    @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}),
    @Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class})
})
final class SqlTimingProfile implements Interceptor {
    @Override public Object intercept(Invocation invocation)throws Throwable {
        long start=System.nanoTime();
        try{return invocation.proceed();}
        finally{long elapsed=System.nanoTime()-start;if(elapsed>=50_000_000L)
            org.slf4j.LoggerFactory.getLogger(SqlTimingProfile.class).info("sql_profile statement={} elapsed_ms={}",((MappedStatement)invocation.getArgs()[0]).getId(),elapsed/1_000_000.0);}
    }
}
