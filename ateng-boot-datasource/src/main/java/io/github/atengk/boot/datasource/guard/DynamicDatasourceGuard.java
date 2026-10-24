package io.github.atengk.boot.datasource.guard;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.dynamic.datasource.tx.TransactionContext;
import io.github.atengk.boot.datasource.exception.DataSourceSwitchException;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 动态数据源事务切源安全守卫
 * <p>
 * 负责智能协同 Spring 单连接事务 (DataSourceTransactionManager) 与 Baomidou 多源事务协调器 (@DSTransactional)。
 * 严密防卫并拦截在 Spring 原生单连接事务中跨库切源的假切源风险，安全放行 Propagation.REQUIRES_NEW 独立事务与 @DSTransactional 多源事务。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class DynamicDatasourceGuard {

    private static final Logger log = LoggerFactory.getLogger(DynamicDatasourceGuard.class);

    /**
     * 绑定至 Spring 事务上下文的当前事务数据源标识资源 Key
     */
    public static final String TX_BOUND_DATASOURCE_RESOURCE_KEY = "ATENG_TX_BOUND_DATASOURCE_KEY";

    private final DatasourceProperties datasourceProperties;

    public DynamicDatasourceGuard(DatasourceProperties datasourceProperties) {
        this.datasourceProperties = datasourceProperties != null ? datasourceProperties : new DatasourceProperties();
    }

    /**
     * 校验当前执行上下文切源操作是否安全
     *
     * @param targetDsKey 目标数据源 Key
     */
    public void validateSwitch(String targetDsKey) {
        validateSwitch(targetDsKey, null, null);
    }

    /**
     * 校验当前执行上下文切源操作是否安全（携带方法反射信息）
     *
     * @param targetDsKey 目标数据源 Key
     * @param method      切源目标方法（用于提取 @Transactional 传播属性）
     */
    public void validateSwitch(String targetDsKey, Method method) {
        validateSwitch(targetDsKey, method, method != null ? method.getDeclaringClass() : null);
    }

    /**
     * 校验当前执行上下文切源操作是否安全（完整重载）
     *
     * @param targetDsKey 目标数据源 Key
     * @param method      切源目标方法
     * @param targetClass 切源目标类
     */
    public void validateSwitch(String targetDsKey, Method method, Class<?> targetClass) {
        if (!datasourceProperties.getGuard().isEnabled() || !StringUtils.hasText(targetDsKey)) {
            return;
        }

        // 1. 若当前处于 Baomidou @DSTransactional 本地多源事务协调器上下文中，放行切源
        if (isDSTransactionActive()) {
            log.debug("当前处于 @DSTransactional 多源协调事务 (XID=[{}])，放行数据源切换至: [{}]",
                    TransactionContext.getXID(), targetDsKey);
            return;
        }

        // 2. 检查当前是否存在处于活动状态的 Spring 单物理连接事务
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 无活动事务，安全放行
            return;
        }

        // 3. 当前处于活动 Spring 事务中：检查是否通过 Propagation.REQUIRES_NEW 声明开启独立新事务
        if (isRequiresNewTransaction(method, targetClass)) {
            log.debug("检测到切源方法声明了 @Transactional(propagation = Propagation.REQUIRES_NEW)，放行独立事务切源至: [{}]",
                    targetDsKey);
            return;
        }

        // 4. 获取当前 Spring 事务初始绑定或执行中的物理数据源 Key
        String currentTxDsKey = getCurrentTransactionDataSourceKey();

        // 5. 若目标数据源与当前 Spring 事务绑定的数据源相同，未发生跨库物理切换，安全放行
        if (Objects.equals(currentTxDsKey, targetDsKey)) {
            return;
        }

        // 6. 检测到跨库切换，判定为 Spring 事务假切源致命隐患
        String errorMessage = String.format(
                "检测到 Spring 单连接事务 (DataSourceTransactionManager) 假切源致命风险：" +
                "当前事务已绑定数据源 [%s]，尝试切源至 [%s] 将被 Spring 事务静默忽略并造成串库！" +
                "若需跨库独立执行，请在目标方法声明 @Transactional(propagation = Propagation.REQUIRES_NEW)，" +
                "或使用 @DSTransactional 本地多源事务协调器。",
                currentTxDsKey, targetDsKey
        );

        if (datasourceProperties.getGuard().isFailOnFalseSwitch()) {
            log.error(errorMessage);
            throw new DataSourceSwitchException(currentTxDsKey, targetDsKey, errorMessage);
        } else {
            log.warn("假切源拦截降级 (failOnFalseSwitch=false): {}", errorMessage);
        }
    }

    /**
     * 判断当前线程是否处于 Baomidou @DSTransactional 多数据源事务协调器上下文中
     *
     * @return true 表示处于 @DSTransactional 事务中；false 表示未激活
     */
    public boolean isDSTransactionActive() {
        return StringUtils.hasText(TransactionContext.getXID());
    }

    /**
     * 判断当前线程是否处于标准 Spring 事务 (DataSourceTransactionManager) 活跃上下文中
     *
     * @return true 表示处于 Spring 事务中；false 表示未激活
     */
    public boolean isSpringTransactionActive() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    /**
     * 获取当前处于活动状态的 Spring 事务绑定的数据源 Key
     * 若未绑定，则记录当前线程栈顶数据源（或默认主库）并注册自动解绑同步器
     *
     * @return 当前事务绑定的数据源 Key，若无活动事务则返回 null
     */
    public String getCurrentTransactionDataSourceKey() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return null;
        }

        // 优先从 Spring 事务资源绑定表中读取
        if (TransactionSynchronizationManager.hasResource(TX_BOUND_DATASOURCE_RESOURCE_KEY)) {
            Object resource = TransactionSynchronizationManager.getResource(TX_BOUND_DATASOURCE_RESOURCE_KEY);
            if (resource instanceof String boundKey && StringUtils.hasText(boundKey)) {
                return boundKey;
            }
        }

        // 若尚未记录，则取当前线程栈顶数据源，栈为空则取默认主库
        String currentKey = DynamicDataSourceContextHolder.peek();
        if (!StringUtils.hasText(currentKey)) {
            currentKey = resolveDefaultDataSourceKey();
        }

        // 若当前事务同步机制已激活，将初始数据源绑定至事务资源表，并在事务完成时解绑
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                TransactionSynchronizationManager.bindResource(TX_BOUND_DATASOURCE_RESOURCE_KEY, currentKey);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (TransactionSynchronizationManager.hasResource(TX_BOUND_DATASOURCE_RESOURCE_KEY)) {
                            TransactionSynchronizationManager.unbindResource(TX_BOUND_DATASOURCE_RESOURCE_KEY);
                        }
                    }
                });
            } catch (Throwable t) {
                log.debug("注册事务数据源解绑同步器异常: {}", t.getMessage());
            }
        }

        return currentKey;
    }

    /**
     * 判定目标方法或目标类是否声明了 Propagation.REQUIRES_NEW 独立事务
     */
    public boolean isRequiresNewTransaction(Method method, Class<?> targetClass) {
        if (method != null) {
            Transactional methodTx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
            if (methodTx != null) {
                return methodTx.propagation() == Propagation.REQUIRES_NEW;
            }
        }
        if (targetClass != null) {
            Transactional classTx = AnnotatedElementUtils.findMergedAnnotation(targetClass, Transactional.class);
            if (classTx != null) {
                return classTx.propagation() == Propagation.REQUIRES_NEW;
            }
        }
        return false;
    }

    /**
     * 解析默认主数据源 Key
     */
    private String resolveDefaultDataSourceKey() {
        if (datasourceProperties.getMultiTenancy() != null && StringUtils.hasText(datasourceProperties.getMultiTenancy().getDefaultDataSource())) {
            return datasourceProperties.getMultiTenancy().getDefaultDataSource();
        }
        if (datasourceProperties.getReadWriteSplit() != null && StringUtils.hasText(datasourceProperties.getReadWriteSplit().getMasterDataSource())) {
            return datasourceProperties.getReadWriteSplit().getMasterDataSource();
        }
        return "master";
    }
}
