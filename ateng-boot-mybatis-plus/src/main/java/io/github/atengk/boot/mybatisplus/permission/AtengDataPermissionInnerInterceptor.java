package io.github.atengk.boot.mybatisplus.permission;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.enums.DataPermissionFailureStrategy;
import io.github.atengk.boot.mybatisplus.util.MybatisInterceptorUtil;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Table;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 增强型细粒度数据权限拦截器
 * 继承 MyBatis-Plus DataPermissionInterceptor，提供 @DataScope 声明式解析、O(1) 纳秒级元数据缓存、
 * 超管及编程式闭包秒级短路放行、OrExpression 优先级防卫与 Fail-Closed 可配失败策略
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class AtengDataPermissionInnerInterceptor extends DataPermissionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AtengDataPermissionInnerInterceptor.class);

    /**
     * MappedStatement ID 到 @DataScope 注解元数据的纳秒级缓存（杜绝高频反射损耗）
     */
    private final ConcurrentHashMap<String, Optional<DataScope>> scopeCache = new ConcurrentHashMap<>();

    /**
     * 自定义数据权限 SPI 处理器
     */
    private final DataPermissionHandler customHandler;

    /**
     * 数据权限 SPI 异常失败策略（默认 DENY_ALL 安全拒止）
     */
    private final DataPermissionFailureStrategy failureStrategy;

    public AtengDataPermissionInnerInterceptor() {
        this(new DefaultDataPermissionHandler(), DataPermissionFailureStrategy.DENY_ALL);
    }

    public AtengDataPermissionInnerInterceptor(DataPermissionHandler customHandler) {
        this(customHandler, DataPermissionFailureStrategy.DENY_ALL);
    }

    /**
     * 构造带显式失败策略的数据权限拦截器
     *
     * @param customHandler   自定义数据权限 SPI 处理器
     * @param failureStrategy 异常失败策略（为 null 时默认 {@link DataPermissionFailureStrategy#DENY_ALL}）
     */
    public AtengDataPermissionInnerInterceptor(DataPermissionHandler customHandler,
                                               DataPermissionFailureStrategy failureStrategy) {
        super();
        this.customHandler = customHandler != null ? customHandler : new DefaultDataPermissionHandler();
        this.failureStrategy = failureStrategy != null ? failureStrategy : DataPermissionFailureStrategy.DENY_ALL;
        // 桥接注册 MultiDataPermissionHandler 适配器，激活父类 AST 多表遍历处理
        super.setDataPermissionHandler(new MultiDataPermissionHandler() {
            @Override
            public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
                return AtengDataPermissionInnerInterceptor.this.buildTableExpression(table, where, mappedStatementId);
            }
        });
    }

    /**
     * 获取当前拦截器生效的异常失败策略
     *
     * @return 数据权限异常失败策略
     */
    public DataPermissionFailureStrategy getFailureStrategy() {
        return failureStrategy;
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

        // 3. 委派业务 SPI 处理器构造 JSqlParser AST 条件表达式，执行 OrExpression 优先级防卫与可配失败策略
        try {
            Expression sqlSegment = customHandler.getSqlSegment(table, where, whereSegment, dataScope);
            return protectExpressionPrecedence(sqlSegment);
        } catch (BusinessException ex) {
            // 业务 SPI 主动抛出的显式鉴权或校验异常必须原样透传，严禁降级吞咽
            throw ex;
        } catch (Exception ex) {
            return handleFailure(table, whereSegment, ex);
        }
    }

    /**
     * 递归保护数据权限 AST 表达式的运算符优先级
     * <p>
     * MyBatis-Plus {@code BaseMultiTableInnerInterceptor} 仅对既有 {@code where} 为 {@link OrExpression} 时自动加括号，
     * 不会对 {@link #buildTableExpression} 新返回的 {@link OrExpression} 加括号。
     * 本方法对顶层裸 {@link OrExpression} 以及 {@link AndExpression} / {@link NotExpression} 直接子分支内的裸 {@link OrExpression}
     * 自动包裹 {@link ParenthesedExpressionList}，防止 {@code AND} 绑定优先级高于 {@code OR} 引发多租户与软删隔离穿透；
     * 采用写时复制（Copy-on-Write）保证并发场景下不篡改业务处理器缓存的共享 AST 实例。
     * </p>
     *
     * @param expression 原始 AST 条件表达式
     * @return 优先级安全保护后的 AST 表达式
     */
    private Expression protectExpressionPrecedence(Expression expression) {
        return normalizePrecedence(expression, true);
    }

    private Expression normalizePrecedence(Expression expression, boolean wrapOr) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof ParenthesedExpressionList<?> parenthesed) {
            if (parenthesed.size() == 1) {
                Expression inner = parenthesed.get(0);
                Expression normalizedInner = normalizePrecedence(inner, false);
                if (normalizedInner != inner) {
                    return new ParenthesedExpressionList<>(normalizedInner);
                }
            }
            return expression;
        }
        if (expression instanceof OrExpression orExpr) {
            Expression left = normalizePrecedence(orExpr.getLeftExpression(), false);
            Expression right = normalizePrecedence(orExpr.getRightExpression(), false);
            OrExpression target = (left != orExpr.getLeftExpression() || right != orExpr.getRightExpression())
                    ? new OrExpression(left, right)
                    : orExpr;
            return wrapOr ? new ParenthesedExpressionList<>(target) : target;
        }
        if (expression instanceof AndExpression andExpr) {
            Expression left = normalizePrecedence(andExpr.getLeftExpression(), true);
            Expression right = normalizePrecedence(andExpr.getRightExpression(), true);
            if (left != andExpr.getLeftExpression() || right != andExpr.getRightExpression()) {
                return new AndExpression(left, right).withUseOperator(andExpr.isUseOperator());
            }
            return expression;
        }
        if (expression instanceof NotExpression notExpr) {
            Expression inner = normalizePrecedence(notExpr.getExpression(), true);
            if (inner != notExpr.getExpression()) {
                return new NotExpression(inner, notExpr.isExclamationMark());
            }
            return expression;
        }
        return expression;
    }

    /**
     * 按配置失败策略处理 {@link DataPermissionHandler} 非受控异常
     */
    private Expression handleFailure(Table table, String whereSegment, Exception ex) {
        String tableName = table != null ? table.getName() : "unknown";
        return switch (failureStrategy) {
            case THROW -> throw new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "数据权限 AST 条件生成失败: table=[" + tableName + "], ms=[" + whereSegment + "]",
                    ex
            );
            case FAIL_OPEN -> {
                log.error("数据权限 AST 条件生成异常，按 FAIL_OPEN 策略降级跳过当前表拦截: table=[{}], ms=[{}], error=[{}]",
                        tableName, whereSegment, ex.getMessage(), ex);
                yield null;
            }
            case DENY_ALL -> {
                log.error("数据权限 AST 条件生成异常，按 DENY_ALL 策略注入 1 = 0 安全拒止条件: table=[{}], ms=[{}], error=[{}]",
                        tableName, whereSegment, ex.getMessage(), ex);
                yield new EqualsTo(new LongValue(1), new LongValue(0));
            }
        };
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
     * 统一委托 MybatisInterceptorUtil 处理方法级优先与衍生后缀（_mpCount, $dynamicAdaptive）剥离
     *
     * @param mappedStatementId MyBatis 映射语句唯一标识（如 io.github.atengk.boot.sample.mapper.UserMapper.selectList）
     * @return 包含 @DataScope 的 Optional，未标注时返回 Optional.empty()
     */
    private Optional<DataScope> resolveDataScope(String mappedStatementId) {
        return MybatisInterceptorUtil.resolveMapperAnnotation(mappedStatementId, DataScope.class);
    }
}
