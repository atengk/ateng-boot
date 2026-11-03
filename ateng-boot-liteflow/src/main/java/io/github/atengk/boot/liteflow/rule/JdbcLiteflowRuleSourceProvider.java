package io.github.atengk.boot.liteflow.rule;

import io.github.atengk.boot.liteflow.exception.LiteflowExecutionException;
import io.github.atengk.boot.liteflow.properties.AtengLiteflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 基于 JDBC 数据源的 LiteFlow 动态规则加载实现
 * 直接依托原生标准 {@link DataSource} 执行确定性 SQL 查询，支持无感知对接 MySQL、PostgreSQL、Oracle、H2 等主流关系型数据库，
 * 严格防范 SQL 注入并遵循确定性连接释放生命周期
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class JdbcLiteflowRuleSourceProvider implements LiteflowRuleSourceProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcLiteflowRuleSourceProvider.class);
    private static final Pattern SAFE_SQL_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_.]+$");

    private final DataSource dataSource;
    private final AtengLiteflowProperties.DatabaseRuleProperties properties;

    public JdbcLiteflowRuleSourceProvider(DataSource dataSource, AtengLiteflowProperties.DatabaseRuleProperties properties) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource 不能为空");
        this.properties = properties != null ? properties : new AtengLiteflowProperties.DatabaseRuleProperties();
        validateProperties(this.properties);
    }

    @Override
    public List<RuleSourceDefinition> loadRules() {
        String tableName = properties.getTableName();
        String ruleIdCol = properties.getRuleIdColumn();
        String ruleNameCol = properties.getRuleNameColumn();
        String elCol = properties.getElColumn();
        String statusCol = properties.getStatusColumn();
        String routeGroupCol = properties.getRouteGroupColumn();
        String descCol = properties.getDescriptionColumn();
        String routeGroup = properties.getRouteGroup();

        boolean filterByRoute = routeGroup != null && !routeGroup.isBlank();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(ruleIdCol).append(", ")
                .append(ruleNameCol).append(", ")
                .append(elCol).append(", ")
                .append(statusCol).append(", ")
                .append(routeGroupCol).append(", ")
                .append(descCol)
                .append(" FROM ").append(tableName)
                .append(" WHERE ").append(statusCol).append(" = 1");

        if (filterByRoute) {
            sql.append(" AND ").append(routeGroupCol).append(" = ?");
        }

        List<RuleSourceDefinition> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            if (filterByRoute) {
                ps.setString(1, routeGroup.trim());
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RuleSourceDefinition definition = mapRow(rs);
                    result.add(definition);
                }
            }
            log.info("ateng-boot-liteflow: 成功从数据库表 [{}] 加载了 [{}] 条有效动态规则", tableName, result.size());
            return result;
        } catch (SQLException e) {
            log.error("ateng-boot-liteflow: 从数据库表 [{}] 加载动态规则失败: {}", tableName, e.getMessage(), e);
            throw new LiteflowExecutionException("DB_RULE_SOURCE_ERROR", null,
                    "从数据库加载 LiteFlow 动态规则失败: " + e.getMessage(), e);
        }
    }

    @Override
    public RuleSourceDefinition loadRule(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            return null;
        }

        String tableName = properties.getTableName();
        String ruleIdCol = properties.getRuleIdColumn();
        String ruleNameCol = properties.getRuleNameColumn();
        String elCol = properties.getElColumn();
        String statusCol = properties.getStatusColumn();
        String routeGroupCol = properties.getRouteGroupColumn();
        String descCol = properties.getDescriptionColumn();
        String routeGroup = properties.getRouteGroup();

        boolean filterByRoute = routeGroup != null && !routeGroup.isBlank();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(ruleIdCol).append(", ")
                .append(ruleNameCol).append(", ")
                .append(elCol).append(", ")
                .append(statusCol).append(", ")
                .append(routeGroupCol).append(", ")
                .append(descCol)
                .append(" FROM ").append(tableName)
                .append(" WHERE ").append(ruleIdCol).append(" = ?");

        if (filterByRoute) {
            sql.append(" AND ").append(routeGroupCol).append(" = ?");
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            ps.setString(1, chainId.trim());
            if (filterByRoute) {
                ps.setString(2, routeGroup.trim());
            }

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
            return null;
        } catch (SQLException e) {
            log.error("ateng-boot-liteflow: 从数据库表 [{}] 加载规则 [{}] 失败: {}", tableName, chainId, e.getMessage(), e);
            throw new LiteflowExecutionException(chainId, null,
                    "从数据库加载规则 [" + chainId + "] 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getName() {
        return "JdbcLiteflowRuleSourceProvider";
    }

    @Override
    public int getOrder() {
        return 100;
    }

    private RuleSourceDefinition mapRow(ResultSet rs) throws SQLException {
        String chainId = rs.getString(1);
        String ruleName = rs.getString(2);
        String el = rs.getString(3);
        int status = rs.getInt(4);
        String routeGroup = rs.getString(5);
        String description = rs.getString(6);

        RuleSourceDefinition def = new RuleSourceDefinition();
        def.setChainId(chainId);
        def.setRuleName(ruleName);
        def.setEl(el);
        def.setEnabled(status == 1);
        def.setRouteGroup(routeGroup);
        def.setDescription(description);
        return def;
    }

    private void validateProperties(AtengLiteflowProperties.DatabaseRuleProperties props) {
        validateIdentifier("tableName", props.getTableName());
        validateIdentifier("ruleIdColumn", props.getRuleIdColumn());
        validateIdentifier("ruleNameColumn", props.getRuleNameColumn());
        validateIdentifier("elColumn", props.getElColumn());
        validateIdentifier("statusColumn", props.getStatusColumn());
        validateIdentifier("routeGroupColumn", props.getRouteGroupColumn());
        validateIdentifier("descriptionColumn", props.getDescriptionColumn());
    }

    private void validateIdentifier(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("数据库动态规则配置属性 [" + fieldName + "] 不能为空");
        }
        if (!SAFE_SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("数据库动态规则配置属性 [" + fieldName + "=" + value + "] 包含非法字符，防御潜在 SQL 注入");
        }
    }
}
