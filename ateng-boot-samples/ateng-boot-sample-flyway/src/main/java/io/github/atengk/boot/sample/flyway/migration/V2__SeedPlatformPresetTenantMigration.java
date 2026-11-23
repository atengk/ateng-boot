package io.github.atengk.boot.sample.flyway.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Spring Bean 驱动的 Flyway JavaMigration 示例
 * <p>
 * 由 {@code AtengFlywayAutoConfiguration} 自动发现并注入单库、静态独立库与动态租户库迁移链路，
 * 按目标库表结构特征幂等补齐预置业务初始化数据。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Component
public class V2__SeedPlatformPresetTenantMigration extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (tableExists(connection, "sys_tenant_registry")) {
            seedMasterTenantRegistry(connection);
        } else if (tableExists(connection, "biz_audit_log")) {
            seedAuditLog(connection);
        } else if (tableExists(connection, "biz_tenant_order")) {
            seedTenantOnboardingOrder(connection);
        }
    }

    private void seedMasterTenantRegistry(Connection connection) throws SQLException {
        if (rowExists(connection, "SELECT COUNT(*) FROM sys_tenant_registry WHERE id = ?", 1000L)) {
            return;
        }
        String sql = "INSERT INTO sys_tenant_registry "
                + "(id, tenant_code, tenant_name, ds_key, plan_tier, source_ds, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, 1000L);
            ps.setString(2, "1000");
            ps.setString(3, "平台旗舰示范租户");
            ps.setString(4, "master");
            ps.setString(5, "FLAGSHIP");
            ps.setString(6, "java-migration");
            ps.setString(7, "ACTIVE");
            ps.executeUpdate();
        }
    }

    private void seedAuditLog(Connection connection) throws SQLException {
        if (rowExists(connection, "SELECT COUNT(*) FROM biz_audit_log WHERE id = ?", 2L)) {
            return;
        }
        String sql = "INSERT INTO biz_audit_log "
                + "(id, source_ds, audit_scope, action_name, operator_name, detail) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, 2L);
            ps.setString(2, "log_db");
            ps.setString(3, "platform-audit");
            ps.setString(4, "JAVA_MIGRATION_SEED");
            ps.setString(5, "spring-bean");
            ps.setString(6, "Spring Bean JavaMigration 自动执行审计留痕");
            ps.executeUpdate();
        }
    }

    private void seedTenantOnboardingOrder(Connection connection) throws SQLException {
        if (rowExists(connection, "SELECT COUNT(*) FROM biz_tenant_order WHERE id = ?", 2L)) {
            return;
        }
        String sql = "INSERT INTO biz_tenant_order "
                + "(id, order_no, tenant_id, source_ds, product_name, amount, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, 2L);
            ps.setString(2, "ORD-JAVA-WELCOME");
            ps.setString(3, "welcome");
            ps.setString(4, "java-migration");
            ps.setString(5, "Spring JavaMigration 新租户开通体验包");
            ps.setBigDecimal(6, BigDecimal.ZERO);
            ps.setString(7, "ACTIVE");
            ps.executeUpdate();
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        try (ResultSet rs = connection.getMetaData().getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (tableName.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean rowExists(Connection connection, String countSql, long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(countSql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }
}
