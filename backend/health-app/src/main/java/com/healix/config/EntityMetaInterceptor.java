package com.healix.config;

import com.healix.common.domain.BaseEntity;
import com.healix.common.domain.EntityMeta;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;

/**
 * INSERT 兜底：业务 id 使用 {@link com.healix.common.util.SnowflakeId#nextBizId()}（32 位）。
 */
@Component
@Intercepts({
    @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class EntityMetaInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        if (ms.getSqlCommandType() == SqlCommandType.INSERT) {
            fill(invocation.getArgs()[1]);
        }
        return invocation.proceed();
    }

    private void fill(Object parameter) {
        if (parameter == null) {
            return;
        }
        if (parameter instanceof BaseEntity entity) {
            EntityMeta.onCreate(entity);
            return;
        }
        if (parameter instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value instanceof BaseEntity entity) {
                    EntityMeta.onCreate(entity);
                } else if (value instanceof Collection<?> col) {
                    for (Object item : col) {
                        if (item instanceof BaseEntity entity) {
                            EntityMeta.onCreate(entity);
                        }
                    }
                }
            }
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // no-op
    }
}
