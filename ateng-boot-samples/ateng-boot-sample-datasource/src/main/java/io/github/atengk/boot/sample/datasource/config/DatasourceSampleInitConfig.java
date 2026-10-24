package io.github.atengk.boot.sample.datasource.config;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 样例数据库初始化配置类
 * <p>
 * 在应用启动后，向所有已配置的 H2 内存数据库初始化订单表结构。
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DatasourceSampleInitConfig implements CommandLineRunner {

    private final DataSource dataSource;
    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;

    @Override
    public void run(String... args) {
        if (!(dataSource instanceof DynamicRoutingDataSource dynamicRoutingDataSource)) {
            return;
        }

        String ddl = """
                CREATE TABLE IF NOT EXISTS t_order (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    order_no VARCHAR(64) NOT NULL,
                    tenant_id VARCHAR(64),
                    product_name VARCHAR(128) NOT NULL,
                    amount DECIMAL(10,2) NOT NULL,
                    status VARCHAR(32) NOT NULL
                );
                """;

        dynamicRoutingDataSource.getDataSources().forEach((key, ds) -> {
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute(ddl);
                log.info("[DatasourceSampleInit] 成功向数据源 [{}] 初始化 t_order 表结构", key);
            } catch (Exception e) {
                log.error("[DatasourceSampleInit] 初始化数据源 [{}] 失败: {}", key, e.getMessage(), e);
            }
        });
    }

    /**
     * 为新动态挂载的数据源初始化表结构的工具方法
     *
     * @param dsKey 数据源键名
     */
    public void initTableForDataSource(String dsKey) {
        String ddl = """
                CREATE TABLE IF NOT EXISTS t_order (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    order_no VARCHAR(64) NOT NULL,
                    tenant_id VARCHAR(64),
                    product_name VARCHAR(128) NOT NULL,
                    amount DECIMAL(10,2) NOT NULL,
                    status VARCHAR(32) NOT NULL
                );
                """;
        dynamicDatasourceTemplate.runInDataSource(dsKey, () -> {
            try {
                if (dataSource instanceof DynamicRoutingDataSource dynamicRoutingDataSource) {
                    DataSource ds = dynamicRoutingDataSource.getDataSource(dsKey);
                    if (ds != null) {
                        try (Connection conn = ds.getConnection();
                             Statement stmt = conn.createStatement()) {
                            stmt.execute(ddl);
                            log.info("[DatasourceSampleInit] 动态初始化数据源 [{}] t_order 表结构完成", dsKey);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[DatasourceSampleInit] 动态初始化数据源 [{}] 失败: {}", dsKey, e.getMessage(), e);
            }
        });
    }
}
