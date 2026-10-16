package io.github.atengk.boot.mybatisplus.permission;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 增强型细粒度数据权限拦截器
 * 继承 MyBatis-Plus DataPermissionInterceptor，提供 @DataScope 声明式解析、O(1) 纳秒级元数据缓存、超管及编程式闭包秒级短路放行
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class AtengDataPermissionInnerInterceptor extends DataPermissionInterceptor {

    /**
     * MappedStatement ID 到 @DataScope 注解元数据的纳秒级缓存（杜绝高频反射损耗）
     */
    private final ConcurrentHashMap<String, Optional<DataScope>> scopeCache = new ConcurrentHashMap<>();

    /**
     * 自定义数据权限 SPI 处理器
     */
    private final DataPermissionHandler customHandler;

    public AtengDataPermissionInnerInterceptor() {
        this(new DefaultDataPermissionHandler());
    }

    public AtengDataPermissionInnerInterceptor(DataPermissionHandler customHandler) {
        super();
        this.customHandler = customHandler != null ? customHandler : new DefaultDataPermissionHandler();
        // 桥接注册 MultiDataPermissionHandler 适配器，激活父类 AST 多表遍历处理
        super.setDataPermissionHandler(new MultiDataPermissionHandler() {
            @Override
            public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
                return AtengDataPermissionInnerInterceptor.this.buildTableExpression(table, where, mappedStatementId);
            }
        });
    }

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
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

    @Override
    public Expression buildTableExpression(Table table, Expression where, String whereSegment) {
        if (customHandler == null || whereSegment == null) {
            return null;
        }

        // 1. 获取当前 MappedStatement 对应的 @DataScope 注解元数据
        Optional<DataScope> optionalDataScope = scopeCache.computeIfAbsent(whereSegment, this::resolveDataScope);
        if (optionalDataScope.isEmpty()) {
            return null;
        }

        DataScope dataScope = optionalDataScope.get();
        if (dataScope.ignore()) {
            return null;
        }

        // 2. 表别名防御性校验：若显式指定了 alias，则仅对匹配该别名的表进行条件追加
        String targetAlias = dataScope.alias();
        if (targetAlias != null && !targetAlias.isBlank()) {
            String currentAlias = table.getAlias() != null ? table.getAlias().getName() : table.getName();
            if (!targetAlias.equalsIgnoreCase(currentAlias)) {
                return null;
            }
        }

        // 3. 委派业务 SPI 处理器构造 JSqlParser AST 条件表达式
        return customHandler.getSqlSegment(table, where, whereSegment, dataScope);
    }

    /**
     * 判断当前 MappedStatement 是否跳过数据权限拦截
     *
     * @param ms MappedStatement 实例
     * @return true 表示跳过权限拦截；false 表示执行正常权限拦截与 AST 改写
     */
    protected boolean shouldIgnore(MappedStatement ms) {
        // 1. 编程式 runWithoutPermission 闭包状态
        if (DataPermissionContextHolder.isIgnore()) {
            return true;
        }

        // 2. 超级管理员身份秒级放行
        if (DataPermissionContextHolder.isSuperAdmin()) {
            return true;
        }

        if (ms == null || ms.getId() == null) {
            return true;
        }

        // 3. 检查 Mapper 接口或方法是否标注了 @DataScope
        Optional<DataScope> optionalDataScope = scopeCache.computeIfAbsent(ms.getId(), this::resolveDataScope);
        if (optionalDataScope.isEmpty()) {
            return true;
        }

        return optionalDataScope.get().ignore();
    }

    /**
     * 反射解析 MappedStatement 对应的 Mapper 接口类与方法上的 @DataScope 注解
     *
     * @param mappedStatementId MyBatis 映射语句唯一标识（如 io.github.atengk.boot.sample.mapper.UserMapper.selectList）
     * @return 包含 @DataScope 的 Optional，未标注时返回 Optional.empty()
     */
    private Optional<DataScope> resolveDataScope(String mappedStatementId) {
        int lastDotIndex = mappedStatementId.lastIndexOf('.');
        if (lastDotIndex <= 0) {
            return Optional.empty();
        }

        String className = mappedStatementId.substring(0, lastDotIndex);
        String methodName = mappedStatementId.substring(lastDotIndex + 1);

        // 如果是 MyBatis-Plus 分页自动生成的 count 语句（如 selectPage_mpCount），剥离后缀还原原方法名
        if (methodName.endsWith("_mpCount")) {
            methodName = methodName.substring(0, methodName.length() - "_mpCount".length());
        }

        try {
            Class<?> mapperClass = Class.forName(className);

            // 1. 优先匹配具体方法上的 @DataScope 注解
            for (Method method : mapperClass.getMethods()) {
                if (method.getName().equals(methodName) && method.isAnnotationPresent(DataScope.class)) {
                    return Optional.of(method.getAnnotation(DataScope.class));
                }
            }

            // 2. 其次匹配 Mapper 接口类级别的 @DataScope 注解
            if (mapperClass.isAnnotationPresent(DataScope.class)) {
                return Optional.of(mapperClass.getAnnotation(DataScope.class));
            }
        } catch (ClassNotFoundException ignored) {
            // 兼容动态代理或运行时 Synthetic 语句，未找到物理类时默认不拦截
        }

        return Optional.empty();
    }
}
