package io.github.atengk.boot.liteflow.rule;

import com.yomahub.liteflow.core.NodeBooleanComponent;
import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.flow.FlowBus;
import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.exception.LiteflowExecutionException;
import io.github.atengk.boot.liteflow.properties.AtengLiteflowProperties;
import io.github.atengk.boot.liteflow.route.LiteflowRouteResult;
import io.github.atengk.boot.liteflow.rule.event.LiteflowRuleRefreshEvent;
import io.github.atengk.boot.liteflow.rule.event.LiteflowRuleRefreshListener;
import lombok.Data;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LiteFlow 多源动态规则 SPI、数据库加载器与 Spring 领域事件热更总线单元测试
 * 验证 SPI 多源规则加载、JDBC 数据库加载、领域事件驱动平滑热更新与下线防卫
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest(classes = LiteflowDynamicRuleSourceTest.TestConfig.class, properties = {
        "liteflow.rule-source=classpath:liteflow/dynamic-flow.el.xml",
        "liteflow.enable=true",
        "ateng.boot.liteflow.dynamic-rule.enabled=true",
        "ateng.boot.liteflow.dynamic-rule.auto-load-on-startup=true",
        "ateng.boot.liteflow.dynamic-rule.database.enabled=true",
        "ateng.boot.liteflow.dynamic-rule.database.route-group=DEFAULT"
})
@DisplayName("多源动态规则 SPI、数据库加载器与领域事件热更新单元测试")
class LiteflowDynamicRuleSourceTest {

    @Autowired
    private LiteflowTemplate liteflowTemplate;

    @Autowired
    private LiteflowRuleSourceManager ruleSourceManager;

    @Autowired
    private JdbcLiteflowRuleSourceProvider jdbcRuleSourceProvider;

    @Autowired
    private InMemoryTestRuleSourceProvider inMemoryProvider;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUpDatabase() throws Exception {
        // 重置并初始化 H2 内存数据库表
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS sys_liteflow_rule");
            stmt.execute("""
                    CREATE TABLE sys_liteflow_rule (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        rule_id VARCHAR(128) NOT NULL UNIQUE,
                        rule_name VARCHAR(255) NOT NULL,
                        el_expression TEXT NOT NULL,
                        status TINYINT NOT NULL DEFAULT 1,
                        route_group VARCHAR(64) NOT NULL DEFAULT 'DEFAULT',
                        description VARCHAR(500) DEFAULT NULL,
                        created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            stmt.execute("""
                    INSERT INTO sys_liteflow_rule (rule_id, rule_name, el_expression, status, route_group, description)
                    VALUES
                    ('dbRuleChain1', '数据库订单规则1', 'THEN(dynStepA, dynStepB)', 1, 'DEFAULT', '初始数据库规则'),
                    ('dbRuleChainDisabled', '停用规则', 'THEN(dynStepC)', 0, 'DEFAULT', '已禁用的规则')
                    """);
        }
    }

    @AfterEach
    void tearDown() {
        FlowBus.removeChain("dbRuleChain1", "dbRuleChain2", "dbRuleChainDisabled",
                "eventChain1", "spiDynamicChain", "toBeDisabledChain", "eventRawXmlChain");
        inMemoryProvider.clear();
    }

    @Test
    @DisplayName("验证 JDBC 数据库规则提供者直接查询及单条检索")
    void testJdbcRuleSourceProviderDirectly() {
        List<RuleSourceDefinition> definitions = jdbcRuleSourceProvider.loadRules();

        assertThat(definitions).isNotEmpty();
        assertThat(definitions).anyMatch(d -> "dbRuleChain1".equals(d.getChainId()) && d.isEnabled());
        assertThat(definitions).noneMatch(d -> "dbRuleChainDisabled".equals(d.getChainId())); // loadRules 只加载启用的状态 1

        RuleSourceDefinition singleDef = jdbcRuleSourceProvider.loadRule("dbRuleChain1");
        assertThat(singleDef).isNotNull();
        assertThat(singleDef.getRuleName()).isEqualTo("数据库订单规则1");
        assertThat(singleDef.getEl()).isEqualTo("THEN(dynStepA, dynStepB)");

        RuleSourceDefinition nonExist = jdbcRuleSourceProvider.loadRule("nonExistChain");
        assertThat(nonExist).isNull();
    }

    @Test
    @DisplayName("验证通过多源规则管理器全量刷新并直接执行数据库规则链")
    void testManagerRefreshAndExecuteDbRule() {
        ruleSourceManager.refreshAll();

        assertThat(liteflowTemplate.containChain("dbRuleChain1")).isTrue();

        TestRuleContext context = liteflowTemplate.execute("dbRuleChain1", TestRuleContext.class);
        assertThat(context.getTrace()).containsExactly("dynStepA", "dynStepB");
    }

    @Test
    @DisplayName("验证发布领域事件驱动单条规则链 EL 即时热生效")
    void testEventDrivenSingleChainReloadWithEl() {
        // 初始装载单条链
        liteflowTemplate.reloadChain("eventChain1", "THEN(dynStepA)");
        TestRuleContext ctx1 = liteflowTemplate.execute("eventChain1", TestRuleContext.class);
        assertThat(ctx1.getTrace()).containsExactly("dynStepA");

        // 发布领域事件热更新为 THEN(dynStepB, dynStepC)
        eventPublisher.publishEvent(LiteflowRuleRefreshEvent.refreshChain(this, "eventChain1", "THEN(dynStepB, dynStepC)"));

        // 即刻执行验证新规则生效
        TestRuleContext ctx2 = liteflowTemplate.execute("eventChain1", TestRuleContext.class);
        assertThat(ctx2.getTrace()).containsExactly("dynStepB", "dynStepC");
    }

    @Test
    @DisplayName("验证发布领域事件驱动按 ID 从 SPI 规则源重新拉取最新规则热生效")
    void testEventDrivenSingleChainReloadFromProvider() {
        inMemoryProvider.setRule("spiDynamicChain", "THEN(dynStepA)");
        ruleSourceManager.refreshAll();

        TestRuleContext ctx1 = liteflowTemplate.execute("spiDynamicChain", TestRuleContext.class);
        assertThat(ctx1.getTrace()).containsExactly("dynStepA");

        // 更新规则源中的 EL 定义
        inMemoryProvider.setRule("spiDynamicChain", "THEN(dynStepC)");

        // 仅通过 chainId 触发事件（不显式传 el，由监听器自动从 provider 回源）
        eventPublisher.publishEvent(LiteflowRuleRefreshEvent.refreshChain(this, "spiDynamicChain"));

        // 再次执行验证已变更为 dynStepC
        TestRuleContext ctx2 = liteflowTemplate.execute("spiDynamicChain", TestRuleContext.class);
        assertThat(ctx2.getTrace()).containsExactly("dynStepC");
    }

    @Test
    @DisplayName("验证发布领域事件全量热刷新全部规则源")
    void testEventDrivenAllRefresh() throws Exception {
        // 向数据库插入新规则
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO sys_liteflow_rule (rule_id, rule_name, el_expression, status, route_group, description)
                    VALUES ('dbRuleChain2', '数据库新规则2', 'THEN(dynStepA, dynStepC)', 1, 'DEFAULT', '动态插入规则')
                    """);
        }

        // 发布全量刷新事件
        eventPublisher.publishEvent(LiteflowRuleRefreshEvent.refreshAll(this));

        // 验证新插入的规则已自动加载并生效
        assertThat(liteflowTemplate.containChain("dbRuleChain2")).isTrue();
        TestRuleContext ctx = liteflowTemplate.execute("dbRuleChain2", TestRuleContext.class);
        assertThat(ctx.getTrace()).containsExactly("dynStepA", "dynStepC");
    }

    @Test
    @DisplayName("验证规则下线后通过全量刷新自动从内存执行器移除")
    void testDisableAndRemoveChain() {
        // 先注册启用
        inMemoryProvider.setRule("toBeDisabledChain", "THEN(dynStepA)", true);
        ruleSourceManager.refreshAll();
        assertThat(liteflowTemplate.containChain("toBeDisabledChain")).isTrue();

        // 将规则标记为停用
        inMemoryProvider.setRule("toBeDisabledChain", "THEN(dynStepA)", false);
        ruleSourceManager.refreshAll();

        // 验证已从内存移除
        assertThat(liteflowTemplate.containChain("toBeDisabledChain")).isFalse();

        // 执行已被移除的流程链应抛出受管业务异常
        assertThatThrownBy(() -> liteflowTemplate.execute("toBeDisabledChain", TestRuleContext.class))
                .isInstanceOf(LiteflowExecutionException.class);
    }

    @Test
    @DisplayName("验证发布领域事件热更新全量原始 XML 规则定义")
    void testEventDrivenRawContentReload() {
        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <flow>
                    <chain name="eventRawXmlChain">
                        THEN(dynStepB, dynStepA);
                    </chain>
                </flow>
                """;

        eventPublisher.publishEvent(LiteflowRuleRefreshEvent.refreshContent(this, xmlContent));

        assertThat(liteflowTemplate.containChain("eventRawXmlChain")).isTrue();
        TestRuleContext ctx = liteflowTemplate.execute("eventRawXmlChain", TestRuleContext.class);
        assertThat(ctx.getTrace()).containsExactly("dynStepB", "dynStepA");
    }

    @Test
    @DisplayName("验证 SQL 标识符校验防御潜在 SQL 注入")
    void testJdbcProviderSqlInjectionDefense() {
        AtengLiteflowProperties.DatabaseRuleProperties evilProps = new AtengLiteflowProperties.DatabaseRuleProperties();
        evilProps.setTableName("sys_rule; DROP TABLE users; --");

        assertThatThrownBy(() -> new JdbcLiteflowRuleSourceProvider(dataSource, evilProps))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("包含非法字符");
    }

    @Test
    @DisplayName("验证动态规则历史快照自动捕获与一键回退 (rollbackChain)")
    void testChainHistorySnapshotsAndRollback() {
        String chainId = "rollbackTestChain";
        // 1. 首次加载 (v1)
        liteflowTemplate.reloadChain(chainId, "THEN(dynStepA);");
        assertThat(liteflowTemplate.getChainHistorySnapshots(chainId)).isEmpty();

        // 2. 第二次更新 (v2)
        liteflowTemplate.reloadChain(chainId, "THEN(dynStepB);");
        assertThat(liteflowTemplate.getChainHistorySnapshots(chainId)).containsExactly("THEN(dynStepA);");

        // 3. 第三次更新 (v3)
        liteflowTemplate.reloadChain(chainId, "THEN(dynStepC);");
        assertThat(liteflowTemplate.getChainHistorySnapshots(chainId))
                .containsExactly("THEN(dynStepB);", "THEN(dynStepA);");

        // 验证当前生效为 v3 (dynStepC)
        TestRuleContext ctx3 = liteflowTemplate.execute(chainId, TestRuleContext.class);
        assertThat(ctx3.getTrace()).containsExactly("dynStepC");

        // 4. 一键回退至上一版本 (v2)
        boolean rollback1 = liteflowTemplate.rollbackChain(chainId);
        assertThat(rollback1).isTrue();
        assertThat(liteflowTemplate.getChainHistorySnapshots(chainId)).containsExactly("THEN(dynStepA);");

        TestRuleContext ctx2 = liteflowTemplate.execute(chainId, TestRuleContext.class);
        assertThat(ctx2.getTrace()).containsExactly("dynStepB");

        // 5. 再次回退至初始版本 (v1)
        boolean rollback2 = ruleSourceManager.rollbackChain(chainId);
        assertThat(rollback2).isTrue();
        assertThat(ruleSourceManager.getChainHistorySnapshots(chainId)).isEmpty();

        TestRuleContext ctx1 = liteflowTemplate.execute(chainId, TestRuleContext.class);
        assertThat(ctx1.getTrace()).containsExactly("dynStepA");

        // 6. 无历史快照时安全降级返回 false
        boolean rollback3 = liteflowTemplate.rollbackChain(chainId);
        assertThat(rollback3).isFalse();
    }

    @Test
    @DisplayName("验证动态规则历史快照上限截断 (maxHistorySnapshots=5 防御内存膨胀)")
    void testSnapshotRetentionBoundLimit() {
        String chainId = "boundedChain";
        // 连续更新 7 个不同版本 (v1 至 v7)
        liteflowTemplate.reloadChain(chainId, "THEN(dynStepA);");
        liteflowTemplate.reloadChain(chainId, "THEN(dynStepB);");
        liteflowTemplate.reloadChain(chainId, "THEN(dynStepC);");
        liteflowTemplate.reloadChain(chainId, "THEN(dynStepA, dynStepB);");
        liteflowTemplate.reloadChain(chainId, "THEN(dynStepB, dynStepA);");
        liteflowTemplate.reloadChain(chainId, "THEN(dynStepA, dynStepC);");
        liteflowTemplate.reloadChain(chainId, "THEN(dynStepB, dynStepC);");

        // 默认上限 maxHistorySnapshots = 5，历史快照最多保留 5 个
        List<String> snapshots = liteflowTemplate.getChainHistorySnapshots(chainId);
        assertThat(snapshots).hasSize(5);
        // 最新的上一次更新为 "THEN(dynStepA, dynStepC);"
        assertThat(snapshots.get(0)).isEqualTo("THEN(dynStepA, dynStepC);");
    }

    @Test
    @DisplayName("验证回退不存在规则链或空链时的健壮性防卫")
    void testRollbackSafeDegradationOnNonExistentChain() {
        assertThat(liteflowTemplate.rollbackChain("nonExistentChain")).isFalse();
        assertThat(liteflowTemplate.getChainHistorySnapshots("nonExistentChain")).isEmpty();
        assertThat(liteflowTemplate.rollbackChain(null)).isFalse();
        assertThat(liteflowTemplate.getChainHistorySnapshots(null)).isEmpty();
    }

    @Test
    @DisplayName("验证 removeChain 下线规则链时确定性清理历史快照与活跃映射，防御内存泄漏")
    void testRemoveChainCleansHistorySnapshots() {
        String chainId = "chainToRemove";
        liteflowTemplate.reloadChain(chainId, "THEN(dynStepA);");
        liteflowTemplate.reloadChain(chainId, "THEN(dynStepB);");
        assertThat(liteflowTemplate.getChainHistorySnapshots(chainId)).containsExactly("THEN(dynStepA);");

        boolean removed = liteflowTemplate.removeChain(chainId);
        assertThat(removed).isTrue();
        assertThat(liteflowTemplate.containChain(chainId)).isFalse();
        assertThat(liteflowTemplate.getChainHistorySnapshots(chainId)).isEmpty();
    }

    @Test
    @DisplayName("验证 EL 语法错误或重载失败时绝不提前记录脏快照")
    void testReloadChainFailureDoesNotRecordFalseSnapshot() {
        String chainId = "preserveSnapshotChain";
        liteflowTemplate.reloadChain(chainId, "THEN(dynStepA);");
        assertThat(liteflowTemplate.getChainHistorySnapshots(chainId)).isEmpty();

        // 尝试用无效语法更新
        assertThatThrownBy(() -> liteflowTemplate.reloadChain(chainId, "INVALID_EL("))
                .isInstanceOf(LiteflowExecutionException.class);

        // 历史快照必须依旧为空，原链 dynStepA 保持生效且不产生重复脏快照
        assertThat(liteflowTemplate.getChainHistorySnapshots(chainId)).isEmpty();
        TestRuleContext ctx = liteflowTemplate.execute(chainId, TestRuleContext.class);
        assertThat(ctx.getTrace()).containsExactly("dynStepA");
    }

    @Test
    @DisplayName("验证决策路由链历史版本回退完整保留 route 条件与命名空间元数据")
    void testRollbackRouteChainPreservesRouteCondition() {
        String chainId = "rollbackRouteChain";
        String ns = "TEST_ROLLBACK_ROUTE_NS";

        // 1. 初始化版本 1：routeCondTrue 条件 -> dynStepA
        liteflowTemplate.reloadRouteChain(chainId, ns, "routeCondTrue", "THEN(dynStepA);");
        TestRuleContext ctx1 = new TestRuleContext();
        liteflowTemplate.executeRoute(ns, ctx1);
        assertThat(ctx1.getTrace()).containsExactly("dynStepA");

        // 2. 更新为版本 2：routeCondFalse 条件 -> dynStepB (条件为假，不命中)
        liteflowTemplate.reloadRouteChain(chainId, ns, "routeCondFalse", "THEN(dynStepB);");
        TestRuleContext ctx2 = new TestRuleContext();
        liteflowTemplate.executeRoute(ns, ctx2);
        assertThat(ctx2.getTrace()).isEmpty();

        // 3. 执行一键回退至版本 1
        boolean rolledBack = liteflowTemplate.rollbackChain(chainId);
        assertThat(rolledBack).isTrue();

        // 4. 强断言：回滚后 routeCondTrue 条件依然完整生效（若退化为普通链则无法匹配路由命名空间）
        TestRuleContext ctxRolledBack = new TestRuleContext();
        liteflowTemplate.executeRoute(ns, ctxRolledBack);
        assertThat(ctxRolledBack.getTrace()).containsExactly("dynStepA");

        // 清理现场
        liteflowTemplate.removeChain(chainId);
    }

    @Test
    @DisplayName("验证 RuleSourceDefinition 领域模型契约自洽")
    void testRuleSourceDefinitionContracts() {
        RuleSourceDefinition def1 = new RuleSourceDefinition("chain1", "THEN(dynStepA)");
        RuleSourceDefinition def2 = new RuleSourceDefinition("chain1", "THEN(dynStepB)");
        RuleSourceDefinition def3 = new RuleSourceDefinition("chain2", "THEN(dynStepC)");

        assertThat(def1).isEqualTo(def2);
        assertThat(def1).isNotEqualTo(def3);
        assertThat(def1.hashCode()).isEqualTo(def2.hashCode());
        assertThat(def1.toString()).contains("chain1");

        RuleSourceDefinition defRoute = new RuleSourceDefinition("routeChain1", "路由规则1", "THEN(dynStepA)", true, "PROMO", "描述", "routeCondTrue");
        assertThat(defRoute.getRouteEl()).isEqualTo("routeCondTrue");
        assertThat(defRoute.getRouteGroup()).isEqualTo("PROMO");
        assertThat(defRoute.toString()).contains("routeCondTrue");
    }

    @Test
    @DisplayName("验证决策路由表（Route Chain）全链路编排与共享上下文多规则聚合")
    void testExecuteRouteMultiRuleContextAggregation() {
        String ns = "TEST_ROUTE_NS";
        // 注册 3 条决策路由链：
        // 1. routeChainA: 条件恒为 true -> 执行 dynStepA
        // 2. routeChainB: 条件依赖 context.routeFlag -> 执行 dynStepB
        // 3. routeChainC: 条件恒为 false -> 执行 dynStepC
        liteflowTemplate.reloadRouteChain("routeChainA", ns, "routeCondTrue", "THEN(dynStepA);");
        liteflowTemplate.reloadRouteChain("routeChainB", ns, "routeCondContext", "THEN(dynStepB);");
        liteflowTemplate.reloadRouteChain("routeChainC", ns, "routeCondFalse", "THEN(dynStepC);");

        // 场景 1: routeFlag = true，chainA 与 chainB 均命中，共享 context 依次聚合变更
        TestRuleContext ctxTrue = new TestRuleContext();
        ctxTrue.setRouteFlag(true);

        LiteflowRouteResult<TestRuleContext> result = liteflowTemplate.executeRouteWithTrace(ns, ctxTrue);
        assertThat(result).isNotNull();
        assertThat(result.hasMatched()).isTrue();
        assertThat(result.getMatchedCount()).isEqualTo(2);
        assertThat(result.getMatchedChainIds()).containsExactlyInAnyOrder("routeChainA", "routeChainB");
        assertThat(result.getContext().getTrace()).containsExactlyInAnyOrder("dynStepA", "dynStepB");
        assertThat(result.getTraces()).hasSize(2);
        assertThat(result.getTotalDurationMs()).isGreaterThanOrEqualTo(0);

        // 场景 2: routeFlag = false，仅 chainA 命中
        TestRuleContext ctxFalse = new TestRuleContext();
        ctxFalse.setRouteFlag(false);

        TestRuleContext returnedCtx = liteflowTemplate.executeRoute(ns, ctxFalse);
        assertThat(returnedCtx).isSameAs(ctxFalse);
        assertThat(returnedCtx.getTrace()).containsExactly("dynStepA");

        // 场景 3: 空命名空间或无任何路由命中，保底静默返回原始未修改上下文
        TestRuleContext ctxEmptyNs = new TestRuleContext();
        TestRuleContext resEmpty = liteflowTemplate.executeRoute("NON_EXISTENT_NS", ctxEmptyNs);
        assertThat(resEmpty).isSameAs(ctxEmptyNs);
        assertThat(resEmpty.getTrace()).isEmpty();

        LiteflowRouteResult<TestRuleContext> resultEmpty = liteflowTemplate.executeRouteWithTrace("NON_EXISTENT_NS", ctxEmptyNs);
        assertThat(resultEmpty.hasMatched()).isFalse();
        assertThat(resultEmpty.getMatchedCount()).isEqualTo(0);
        assertThat(resultEmpty.getMatchedChainIds()).isEmpty();
        assertThat(resultEmpty.getTraces()).isEmpty();
    }

    @Test
    @DisplayName("验证 JDBC 动态规则加载器支持 6 列兼容模式与 7 列决策路由扩展模式")
    void testJdbcRuleSourceProviderCompatibilityAndRouteExtension() throws Exception {
        // 1. 验证默认 6 列兼容模式：routeEl 为 null
        List<RuleSourceDefinition> defaultRules = jdbcRuleSourceProvider.loadRules();
        assertThat(defaultRules).isNotEmpty();
        for (RuleSourceDefinition def : defaultRules) {
            assertThat(def.getRouteEl()).isNull();
        }

        // 2. 动态向 H2 添加第 7 列 route_el
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE sys_liteflow_rule ADD COLUMN route_el VARCHAR(255) DEFAULT NULL");
            stmt.execute("UPDATE sys_liteflow_rule SET route_el = 'routeCondTrue' WHERE rule_id = 'dbRuleChain1'");
        }

        // 3. 配置启用第 7 列 routeElColumn
        AtengLiteflowProperties.DatabaseRuleProperties props7 = new AtengLiteflowProperties.DatabaseRuleProperties();
        props7.setRouteElColumn("route_el");
        JdbcLiteflowRuleSourceProvider provider7 = new JdbcLiteflowRuleSourceProvider(dataSource, props7);

        List<RuleSourceDefinition> rules7 = provider7.loadRules();
        assertThat(rules7).isNotEmpty();
        RuleSourceDefinition dbRule1 = rules7.stream()
                .filter(r -> "dbRuleChain1".equals(r.getChainId()))
                .findFirst()
                .orElseThrow();
        assertThat(dbRule1.getRouteEl()).isEqualTo("routeCondTrue");

        RuleSourceDefinition singleRule = provider7.loadRule("dbRuleChain1");
        assertThat(singleRule).isNotNull();
        assertThat(singleRule.getRouteEl()).isEqualTo("routeCondTrue");
    }

    @Test
    @DisplayName("验证 LiteflowRuleSourceManager 严格遵循 dynamicRule.failFast 属性")
    void testRuleSourceManagerFailFastProperty() {
        LiteflowRuleSourceProvider failingProvider = new LiteflowRuleSourceProvider() {
            @Override
            public String getName() {
                return "FAILING_PROVIDER";
            }

            @Override
            public List<RuleSourceDefinition> loadRules() {
                throw new RuntimeException("模拟底层规则加载超时或不可用");
            }

            @Override
            public RuleSourceDefinition loadRule(String chainId) {
                throw new RuntimeException("模拟单条规则加载失败");
            }
        };

        // 1. failFast = true（默认）时，必须抛出 IllegalStateException 快速失败
        AtengLiteflowProperties failFastProps = new AtengLiteflowProperties();
        failFastProps.getDynamicRule().setFailFast(true);
        LiteflowRuleSourceManager failFastMgr = new LiteflowRuleSourceManager(
                List.of(failingProvider), liteflowTemplate, failFastProps);

        assertThatThrownBy(failFastMgr::loadAllRules)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("从规则源 [FAILING_PROVIDER] 加载规则列表失败");

        assertThatThrownBy(() -> failFastMgr.loadRule("anyChain"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("从规则源 [FAILING_PROVIDER] 查询规则 [anyChain] 失败");

        // 2. failFast = false 时，记录日志并优雅跳过，返回空集合或 null
        AtengLiteflowProperties permissiveProps = new AtengLiteflowProperties();
        permissiveProps.getDynamicRule().setFailFast(false);
        LiteflowRuleSourceManager permissiveMgr = new LiteflowRuleSourceManager(
                List.of(failingProvider), liteflowTemplate, permissiveProps);

        List<RuleSourceDefinition> rules = permissiveMgr.loadAllRules();
        assertThat(rules).isEmpty();
        assertThat(permissiveMgr.loadRule("anyChain")).isNull();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({StepA.class, StepB.class, StepC.class, RouteCondTrue.class, RouteCondFalse.class, RouteCondContext.class, InMemoryTestRuleSourceProvider.class})
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:liteflow_dynamic_rule_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
            ds.setUser("sa");
            ds.setPassword("");
            return ds;
        }
    }

    @Component
    public static class InMemoryTestRuleSourceProvider implements LiteflowRuleSourceProvider {
        private final List<RuleSourceDefinition> list = new CopyOnWriteArrayList<>();

        public void setRule(String chainId, String el) {
            setRule(chainId, el, true);
        }

        public void setRule(String chainId, String el, boolean enabled) {
            list.removeIf(r -> chainId.equals(r.getChainId()));
            list.add(new RuleSourceDefinition(chainId, el, "内存规则-" + chainId, enabled));
        }

        public void clear() {
            list.clear();
        }

        @Override
        public List<RuleSourceDefinition> loadRules() {
            return new ArrayList<>(list);
        }

        @Override
        public int getOrder() {
            return 10;
        }
    }

    @Data
    public static class TestRuleContext {
        private boolean routeFlag;
        private List<String> trace = new ArrayList<>();
    }

    @Component("routeCondTrue")
    public static class RouteCondTrue extends NodeBooleanComponent {
        @Override
        public boolean processBoolean() {
            return true;
        }
    }

    @Component("routeCondFalse")
    public static class RouteCondFalse extends NodeBooleanComponent {
        @Override
        public boolean processBoolean() {
            return false;
        }
    }

    @Component("routeCondContext")
    public static class RouteCondContext extends NodeBooleanComponent {
        @Override
        public boolean processBoolean() {
            TestRuleContext ctx = this.getContextBean(TestRuleContext.class);
            return ctx != null && ctx.isRouteFlag();
        }
    }

    @Component("dynStepA")
    public static class StepA extends NodeComponent {
        @Override
        public void process() {
            TestRuleContext ctx = this.getContextBean(TestRuleContext.class);
            if (ctx != null) {
                ctx.getTrace().add("dynStepA");
            }
        }
    }

    @Component("dynStepB")
    public static class StepB extends NodeComponent {
        @Override
        public void process() {
            TestRuleContext ctx = this.getContextBean(TestRuleContext.class);
            if (ctx != null) {
                ctx.getTrace().add("dynStepB");
            }
        }
    }

    @Component("dynStepC")
    public static class StepC extends NodeComponent {
        @Override
        public void process() {
            TestRuleContext ctx = this.getContextBean(TestRuleContext.class);
            if (ctx != null) {
                ctx.getTrace().add("dynStepC");
            }
        }
    }
}
