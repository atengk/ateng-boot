package io.github.atengk.boot.mybatisplus.tenant;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import io.github.atengk.boot.mybatisplus.annotation.IgnoreTenant;
import io.github.atengk.boot.mybatisplus.context.TenantContextHolder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 增强型行级多租户拦截器
 * 继承 MyBatis-Plus 原生 TenantLineInnerInterceptor，无缝集成 @IgnoreTenant 注解与 TenantContextHolder 编程式短路忽略
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class AtengTenantLineInnerInterceptor extends TenantLineInnerInterceptor {

    /**
     * MappedStatement ID 到是否忽略多租户的元数据高速缓存（O(1) 纳秒级读取，杜绝运行时反射损耗）
     */
    private final ConcurrentHashMap<String, Boolean> ignoreCache = new ConcurrentHashMap<>();

    public AtengTenantLineInnerInterceptor() {
        super();
    }

    public AtengTenantLineInnerInterceptor(TenantLineHandler tenantLineHandler) {
        super(tenantLineHandler);
    }

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
        if (shouldIgnore(ms)) {
            return;
        }
        super.beforeQuery(executor, ms, parameter, rowBounds, resultHandler, boundSql);
    }

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        PluginUtils.MPStatementHandler mpSh = PluginUtils.mpStatementHandler(sh);
        MappedStatement ms = mpSh.mappedStatement();
        if (shouldIgnore(ms)) {
            return;
        }
        super.beforePrepare(sh, connection, transactionTimeout);
    }

    /**
     * 判断当前 MappedStatement 是否跳过行级多租户 SQL 改写
     *
     * @param ms MappedStatement 实例
     * @return true 表示跳过租户拦截；false 表示正常执行行级租户改写
     */
    protected boolean shouldIgnore(MappedStatement ms) {
        // 1. 判断当前线程是否处于编程式 runWithoutTenant 闭包环境中
        if (TenantContextHolder.isIgnoreTenant()) {
            return true;
        }

        if (ms == null || ms.getId() == null) {
            return false;
        }

        // 2. 检查缓存或解析 Mapper 类与方法上标注的 @IgnoreTenant 注解
        return ignoreCache.computeIfAbsent(ms.getId(), this::resolveIgnoreAnnotation);
    }

    /**
     * 反射解析 MappedStatement 对应的 Mapper 接口类与方法是否标注了 @IgnoreTenant 注解
     *
     * @param mappedStatementId MyBatis 映射语句唯一标识（如 io.github.atengk.boot.sample.mapper.UserMapper.selectList）
     * @return true 表示标注了 @IgnoreTenant 注解
     */
    private boolean resolveIgnoreAnnotation(String mappedStatementId) {
        int lastDotIndex = mappedStatementId.lastIndexOf('.');
        if (lastDotIndex <= 0) {
            return false;
        }

        String className = mappedStatementId.substring(0, lastDotIndex);
        String methodName = mappedStatementId.substring(lastDotIndex + 1);

        try {
            Class<?> mapperClass = Class.forName(className);

            // 1. 检查 Mapper 接口级别是否标注 @IgnoreTenant
            if (mapperClass.isAnnotationPresent(IgnoreTenant.class)) {
                return true;
            }

            // 2. 检查具体 Mapper 方法级别是否标注 @IgnoreTenant
            for (Method method : mapperClass.getMethods()) {
                if (method.getName().equals(methodName) && method.isAnnotationPresent(IgnoreTenant.class)) {
                    return true;
                }
            }
        } catch (ClassNotFoundException ignored) {
            // 兼容动态代理或特殊 Synthetic 语句，未找到物理类时默认不忽略
        }

        return false;
    }
}
