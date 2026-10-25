package io.github.atengk.boot.datasource.guard;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.dynamic.datasource.processor.DsProcessor;
import com.baomidou.dynamic.datasource.support.DataSourceClassResolver;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.StringUtils;

/**
 * 具备假切源刚性拦截防卫的 @DS 注解切面方法拦截器
 * <p>
 * 增强 Baomidou 原生 DynamicDataSourceAnnotationInterceptor，在压栈切源之前，
 * 调用 DynamicDatasourceGuard 识别事务归属，彻底拦截 Spring 单连接事务假切源。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class AtengDynamicDataSourceAnnotationInterceptor implements MethodInterceptor {

    private final DataSourceClassResolver dataSourceClassResolver;
    private final DsProcessor dsProcessor;
    private final DynamicDatasourceGuard guard;

    public AtengDynamicDataSourceAnnotationInterceptor(Boolean graceDestroy,
                                                       DsProcessor dsProcessor,
                                                       DynamicDatasourceGuard guard) {
        this.dataSourceClassResolver = new DataSourceClassResolver(graceDestroy != null && graceDestroy);
        this.dsProcessor = dsProcessor;
        this.guard = guard;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        String dsKey = determineDatasourceKey(invocation);
        if (StringUtils.hasText(dsKey) && guard != null) {
            Object target = invocation.getThis();
            Class<?> targetClass = target != null ? AopUtils.getTargetClass(target) : invocation.getMethod().getDeclaringClass();
            guard.validateSwitch(dsKey, invocation.getMethod(), targetClass);
        }

        DynamicDataSourceContextHolder.push(dsKey);
        try {
            return invocation.proceed();
        } finally {
            DynamicDataSourceContextHolder.poll();
        }
    }

    private String determineDatasourceKey(MethodInvocation invocation) {
        String key = dataSourceClassResolver.findKey(invocation.getMethod(), invocation.getThis(), DS.class);
        return (!StringUtils.hasText(key) || !key.startsWith("#"))
                ? key
                : dsProcessor.determineDatasource(invocation, key);
    }
}
