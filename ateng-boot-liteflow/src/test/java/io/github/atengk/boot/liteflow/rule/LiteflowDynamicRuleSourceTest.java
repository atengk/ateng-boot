package io.github.atengk.boot.liteflow.rule;

import com.yomahub.liteflow.core.NodeComponent;
import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.exception.LiteflowExecutionException;
import io.github.atengk.boot.liteflow.properties.AtengLiteflowProperties;
import io.github.atengk.boot.liteflow.rule.event.LiteflowRuleRefreshEvent;
import io.github.atengk.boot.liteflow.rule.event.LiteflowRuleRefreshListener;
import lombok.Data;
import org.h2.jdbcx.JdbcDataSource;
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
                    ('dbRuleChain1', '数据库订单规则1', 'THEN(stepA, stepB)', 1, 'DEFAULT', '初始数据库规则'),
                    ('dbRuleChainDisabled', '停用规则', 'THEN(stepC)', 0, 'DEFAULT', '已禁用的规则')
                    """);
        }
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
        assertThat(singleDef.getEl()).isEqualTo("THEN(stepA, stepB)");

        RuleSourceDefinition nonExist = jdbcRuleSourceProvider.loadRule("nonExistChain");
        assertThat(nonExist).isNull();
    }

    @Test
    @DisplayName("验证通过多源规则管理器全量刷新并直接执行数据库规则链")
    void testManagerRefreshAndExecuteDbRule() {
        ruleSourceManager.refreshAll();

        assertThat(liteflowTemplate.containChain("dbRuleChain1")).isTrue();

        TestRuleContext context = liteflowTemplate.execute("dbRuleChain1", TestRuleContext.class);
        assertThat(context.getTrace()).containsExactly("stepA", "stepB");
    }

    @Test
    @DisplayName("验证发布领域事件驱动单条规则链 EL 即时热生效")
    void testEventDrivenSingleChainReloadWithEl() {
        // 初始装载单条链
        liteflowTemplate.reloadChain("eventChain1", "THEN(stepA)");
        TestRuleContext ctx1 = liteflowTemplate.execute("eventChain1", TestRuleContext.class);
        assertThat(ctx1.getTrace()).containsExactly("stepA");

        // 发布领域事件热更新为 THEN(stepB, stepC)
        eventPublisher.publishEvent(LiteflowRuleRefreshEvent.refreshChain(this, "eventChain1", "THEN(stepB, stepC)"));

        // 即刻执行验证新规则生效
        TestRuleContext ctx2 = liteflowTemplate.execute("eventChain1", TestRuleContext.class);
        assertThat(ctx2.getTrace()).containsExactly("stepB", "stepC");
    }

    @Test
    @DisplayName("验证发布领域事件驱动按 ID 从 SPI 规则源重新拉取最新规则热生效")
    void testEventDrivenSingleChainReloadFromProvider() {
        inMemoryProvider.setRule("spiDynamicChain", "THEN(stepA)");
        ruleSourceManager.refreshAll();

        TestRuleContext ctx1 = liteflowTemplate.execute("spiDynamicChain", TestRuleContext.class);
        assertThat(ctx1.getTrace()).containsExactly("stepA");

        // 更新规则源中的 EL 定义
        inMemoryProvider.setRule("spiDynamicChain", "THEN(stepC)");

        // 仅通过 chainId 触发事件（不显式传 el，由监听器自动从 provider 回源）
        eventPublisher.publishEvent(LiteflowRuleRefreshEvent.refreshChain(this, "spiDynamicChain"));

        // 再次执行验证已变更为 stepC
        TestRuleContext ctx2 = liteflowTemplate.execute("spiDynamicChain", TestRuleContext.class);
        assertThat(ctx2.getTrace()).containsExactly("stepC");
    }

    @Test
    @DisplayName("验证发布领域事件全量热刷新全部规则源")
    void testEventDrivenAllRefresh() throws Exception {
        // 向数据库插入新规则
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO sys_liteflow_rule (rule_id, rule_name, el_expression, status, route_group, description)
                    VALUES ('dbRuleChain2', '数据库新规则2', 'THEN(stepA, stepC)', 1, 'DEFAULT', '动态插入规则')
                    """);
        }

        // 发布全量刷新事件
        eventPublisher.publishEvent(LiteflowRuleRefreshEvent.refreshAll(this));

        // 验证新插入的规则已自动加载并生效
        assertThat(liteflowTemplate.containChain("dbRuleChain2")).isTrue();
        TestRuleContext ctx = liteflowTemplate.execute("dbRuleChain2", TestRuleContext.class);
        assertThat(ctx.getTrace()).containsExactly("stepA", "stepC");
    }

    @Test
    @DisplayName("验证规则下线后通过全量刷新自动从内存执行器移除")
    void testDisableAndRemoveChain() {
        // 先注册启用
        inMemoryProvider.setRule("toBeDisabledChain", "THEN(stepA)", true);
        ruleSourceManager.refreshAll();
        assertThat(liteflowTemplate.containChain("toBeDisabledChain")).isTrue();

        // 将规则标记为停用
        inMemoryProvider.setRule("toBeDisabledChain", "THEN(stepA)", false);
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
                        THEN(stepB, stepA);
                    </chain>
                </flow>
                """;

        eventPublisher.publishEvent(LiteflowRuleRefreshEvent.refreshContent(this, xmlContent));

        assertThat(liteflowTemplate.containChain("eventRawXmlChain")).isTrue();
        TestRuleContext ctx = liteflowTemplate.execute("eventRawXmlChain", TestRuleContext.class);
        assertThat(ctx.getTrace()).containsExactly("stepB", "stepA");
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
    @DisplayName("验证 RuleSourceDefinition 领域模型契约自洽")
    void testRuleSourceDefinitionContracts() {
        RuleSourceDefinition def1 = new RuleSourceDefinition("chain1", "THEN(stepA)");
        RuleSourceDefinition def2 = new RuleSourceDefinition("chain1", "THEN(stepB)");
        RuleSourceDefinition def3 = new RuleSourceDefinition("chain2", "THEN(stepC)");

        assertThat(def1).isEqualTo(def2);
        assertThat(def1).isNotEqualTo(def3);
        assertThat(def1.hashCode()).isEqualTo(def2.hashCode());
        assertThat(def1.toString()).contains("chain1");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({StepA.class, StepB.class, StepC.class, InMemoryTestRuleSourceProvider.class})
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
        private List<String> trace = new ArrayList<>();
    }

    @Component("stepA")
    public static class StepA extends NodeComponent {
        @Override
        public void process() {
            TestRuleContext ctx = this.getContextBean(TestRuleContext.class);
            if (ctx != null) {
                ctx.getTrace().add("stepA");
            }
        }
    }

    @Component("stepB")
    public static class StepB extends NodeComponent {
        @Override
        public void process() {
            TestRuleContext ctx = this.getContextBean(TestRuleContext.class);
            if (ctx != null) {
                ctx.getTrace().add("stepB");
            }
        }
    }

    @Component("stepC")
    public static class StepC extends NodeComponent {
        @Override
        public void process() {
            TestRuleContext ctx = this.getContextBean(TestRuleContext.class);
            if (ctx != null) {
                ctx.getTrace().add("stepC");
            }
        }
    }
}
