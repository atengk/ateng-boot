package io.github.atengk.boot.mybatisplus.guard;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.properties.MybatisPlusProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 多数据源协同诊断探针与上下文守卫组件
 * 针对业务引入第三方动态切源（如 dynamic-datasource 或自定义路由数据源）场景，
 * 恪守零臃肿与不重造多数据源轮子的原则，通过轻量启动期探针（{@link SmartInitializingSingleton}）
 * 自动感知多数据源环境并输出持久层多租户、数据权限与事务协同健康建议；
 * 并提供 {@code validateContextIsolated()} 静态断言方法供切源切面或事务传播边界进行上下文防卫，
 * 从根本上防御跨数据源切换时发生多租户污染、数据权限穿透与活跃事务假切源等严重事故。
 *
 * @author Ateng
 * @since 2026-10-18
 */
public class DynamicDatasourceGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DynamicDatasourceGuard.class);

    /**
     * 当前运行环境是否探测到多数据源或动态路由数据源特征
     */
    private static volatile boolean multiDataSourceDetected = false;

    /**
     * 当前线程显式上下文污染标记及原因
     */
    private static final ThreadLocal<String> TAINTED_REASON = new ThreadLocal<>();

    private final ApplicationContext applicationContext;
    private final MybatisPlusProperties properties;

    /**
     * 默认无参构造器（供纯静态工具或脱机测试场景使用）
     */
    public DynamicDatasourceGuard() {
        this(null, null);
    }

    /**
     * 基于 Spring 上下文的便利构造器
     *
     * @param applicationContext Spring 应用上下文
     */
    public DynamicDatasourceGuard(ApplicationContext applicationContext) {
        this(applicationContext, null);
    }

    /**
     * 基于 Spring 上下文与持久层配置属性全量构造
     *
     * @param applicationContext Spring 应用上下文
     * @param properties         持久层模块配置属性
     */
    public DynamicDatasourceGuard(ApplicationContext applicationContext, MybatisPlusProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties;
    }

    /**
     * 判断当前运行环境是否探测到多数据源
     *
     * @return true 表示存在多个 DataSource 或动态路由数据源；false 表示单数据源或未初始化
     */
    public static boolean isMultiDataSourceDetected() {
        return multiDataSourceDetected;
    }

    /**
     * 动态设置多数据源探测状态（供测试或外部切源框架回调）
     *
     * @param detected 是否存在多数据源
     */
    public static void setMultiDataSourceDetected(boolean detected) {
        multiDataSourceDetected = detected;
    }

    /**
     * 将当前线程标记为上下文已受污染（用于主动阻断或单测模拟）
     *
     * @param reason 污染原因说明
     */
    public static void markContextTainted(String reason) {
        TAINTED_REASON.set(reason != null ? reason : "显式标记为上下文受污染状态");
    }

    /**
     * 清理当前线程的上下文污染标记
     */
    public static void clearTaintedContext() {
        TAINTED_REASON.remove();
    }

    /**
     * 判断当前线程是否被标记为上下文受污染
     *
     * @return true 表示当前线程上下文处于受污染状态
     */
    public static boolean isContextTainted() {
        return TAINTED_REASON.get() != null;
    }

    /**
     * 校验当前线程在切源或跨数据源操作时的上下文隔离性（无期望租户入参）
     * 防卫活跃事务假切源、数据权限未清理残留与显式污染状态
     *
     * @throws IllegalStateException 当检测到活跃事务、数据权限未闭合残留或上下文被污染时抛出
     */
    public static void validateContextIsolated() {
        validateContextIsolated(null);
    }

    /**
     * 校验当前线程在切源或跨数据源操作时的上下文隔离性（带目标期望租户入参）
     *
     * @param expectedTenantId 期望绑定的租户标识，若为 null 则跳过租户一致性比对
     * @throws IllegalStateException 当检测到活跃事务、数据权限未闭合残留、租户不匹配或上下文被污染时抛出
     */
    public static void validateContextIsolated(String expectedTenantId) {
        // 1. 显式上下文污染防卫
        String taintedReason = TAINTED_REASON.get();
        if (taintedReason != null) {
            throw new IllegalStateException("多数据源上下文污染异常: " + taintedReason);
        }

        // 2. 活跃事务假切源防卫
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("多数据源切换异常：当前线程处于活跃事务中（TransactionActive=true），"
                    + "物理连接已被当前事务持有绑定，跨数据源切换将无法生效，存在严重的跨库串读或写错库风险！");
        }

        // 3. 数据权限未闭合残留污染防卫
        if (DataPermissionContextHolder.isSuperAdmin() || DataPermissionContextHolder.isIgnore()) {
            throw new IllegalStateException("多数据源上下文污染异常：检测到数据权限上下文残留（isSuperAdmin="
                    + DataPermissionContextHolder.isSuperAdmin() + ", isIgnore="
                    + DataPermissionContextHolder.isIgnore() + "），跨数据源操作前必须严格清理上下文，防止权限跨库穿透！");
        }

        // 4. 租户上下文一致性校验
        if (expectedTenantId != null) {
            String currentTenantId = TenantContextHolder.getTenantId();
            if (!Objects.equals(expectedTenantId, currentTenantId)) {
                throw new IllegalStateException("多数据源租户上下文不一致：当前线程租户 ID 为 [" + currentTenantId
                        + "]，与目标期望租户 ID [" + expectedTenantId + "] 不匹配！");
            }
        }
    }

    /**
     * 启动期探针生命周期回调：在所有单例 Bean 初始化完成后自动执行多数据源自检
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (applicationContext == null) {
            return;
        }

        Map<String, DataSource> dataSourceBeans;
        try {
            dataSourceBeans = applicationContext.getBeansOfType(DataSource.class);
        } catch (Exception e) {
            log.warn("多数据源协同诊断探针获取 DataSource Bean 异常: {}", e.getMessage());
            return;
        }

        if (dataSourceBeans.isEmpty()) {
            log.debug("未检测到 DataSource 实例，跳过多数据源协同诊断");
            return;
        }

        boolean multiDetected = false;
        List<String> detectedReasons = new ArrayList<>();

        if (dataSourceBeans.size() > 1) {
            multiDetected = true;
            detectedReasons.add("检测到 Spring 容器中注册了 " + dataSourceBeans.size() + " 个 DataSource Bean: " + dataSourceBeans.keySet());
        } else {
            Map.Entry<String, DataSource> entry = dataSourceBeans.entrySet().iterator().next();
            DataSource ds = entry.getValue();
            if (isRoutingDataSource(ds)) {
                multiDetected = true;
                detectedReasons.add("检测到名为 [" + entry.getKey() + "] 的 DataSource 具备动态路由特征 (类型: " + ds.getClass().getName() + ")");
            }
        }

        multiDataSourceDetected = multiDetected;

        if (multiDetected) {
            log.info("====== [ateng-boot-mybatis-plus 多数据源协同诊断探针] ======");
            for (String reason : detectedReasons) {
                log.info("环境特征: {}", reason);
            }
            log.info("协同自检建议:");
            log.info("1. [行级多租户] 若启用多租户拦截器，请核查非业务库（如公共字典库、定时任务库、报表从库）是否已通过 ignore-tables 或 @IgnoreTenant 声明放行；");
            log.info("2. [事务切源禁忌] Spring 声明式事务在开启后会固定绑定物理连接，严禁在 @Transactional 事务方法内部执行切源操作，否则切源将静默失效；");
            log.info("3. [上下文隔离] 跨数据源访问前，建议调用 DynamicDatasourceGuard.validateContextIsolated() 校验当前线程事务与数据权限上下文纯净性。");
            log.info("==============================================================");
        } else {
            log.debug("多数据源协同诊断探针检测完成：当前为标准单数据源环境，各项持久层组件健康就绪。");
        }
    }

    /**
     * 判断数据源是否具备动态路由特征
     *
     * @param ds 数据源对象
     * @return true 表示为动态路由数据源
     */
    private boolean isRoutingDataSource(DataSource ds) {
        if (ds == null) {
            return false;
        }
        if (ds instanceof AbstractRoutingDataSource) {
            return true;
        }
        String className = ds.getClass().getName();
        return className.contains("RoutingDataSource") || className.contains("DynamicRouting");
    }
}
