package io.github.atengk.boot.flyway.routing;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.Locale;

/**
 * 多数据源目标路由共享辅助器（主从过滤、安全路径段清洗与常规数据源代理递归解包）
 *
 * @author Ateng
 * @since 2026-11-17
 */
public final class TargetRoutingSupport {

    private TargetRoutingSupport() {
    }

    /**
     * 判断目标数据源标识是否属于只读从库或配置黑名单排除项
     *
     * @param dsKey      数据源标识
     * @param properties ateng-boot-flyway 配置属性
     * @return true 表示必须永久剔除
     */
    public static boolean isExcludedOrSlave(String dsKey, AtengFlywayProperties properties) {
        if (!StringUtils.hasText(dsKey)) {
            return true;
        }
        String normalized = dsKey.trim().toLowerCase(Locale.ROOT);
        AtengFlywayProperties.TargetRoutingProperties routing = properties != null ? properties.getRouting() : null;
        if ((routing == null || routing.isSkipSlave()) && isSlaveNaming(normalized)) {
            return true;
        }
        if (routing != null && !CollectionUtils.isEmpty(routing.getExcludedDataSources())) {
            for (String excluded : routing.getExcludedDataSources()) {
                if (StringUtils.hasText(excluded)
                        && normalized.equals(excluded.trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断数据源或分组名称是否符合从库命名约定（{@code slave}、{@code slave_*}、{@code *_slave}）
     *
     * @param name 数据源或分组名称
     * @return true 表示命中从库命名约定
     */
    public static boolean isSlaveNaming(String name) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return "slave".equals(normalized)
                || normalized.endsWith("_slave")
                || normalized.startsWith("slave_");
    }

    /**
     * 判断分组名称是否符合只读/从库分组命名约定
     *
     * @param groupName 分组名称
     * @return true 表示只读或从库分组
     */
    public static boolean isReadOnlyGroupNaming(String groupName) {
        if (!StringUtils.hasText(groupName)) {
            return false;
        }
        String normalized = groupName.trim().toLowerCase(Locale.ROOT);
        return isSlaveNaming(normalized)
                || normalized.contains("readonly")
                || normalized.contains("read_only")
                || normalized.contains("read-only")
                || normalized.endsWith("_ro")
                || normalized.startsWith("ro_");
    }

    /**
     * 校验并返回安全的脚本目录路径片段，严禁包含路径穿越或跨层分隔符
     *
     * @param targetKey 目标数据源标识
     * @return 安全目录名
     */
    public static String sanitizePathSegment(String targetKey) {
        if (!StringUtils.hasText(targetKey)) {
            throw new FlywayException(FlywayResultCode.CONFIG_INVALID, "目标数据源标识不能为空");
        }
        String trimmed = targetKey.trim();
        if (trimmed.contains("..") || trimmed.contains("/") || trimmed.contains("\\")) {
            throw new FlywayException(FlywayResultCode.CONFIG_INVALID,
                    "目标数据源标识包含非法路径穿越字符: [" + trimmed + "]");
        }
        String sanitized = FileUtil.cleanFilename(trimmed);
        if (!StringUtils.hasText(sanitized) || !sanitized.equals(trimmed)) {
            throw new FlywayException(FlywayResultCode.CONFIG_INVALID,
                    "目标数据源标识包含非法字符或不可作为安全目录名: [" + trimmed + "]");
        }
        return sanitized;
    }

    /**
     * 尝试解包标准 Spring/JDBC 代理数据源（不强依赖动态数据源类库）
     *
     * @param dataSource 原始数据源
     * @return 解包后的物理数据源
     */
    public static DataSource unwrapBasicProxy(DataSource dataSource) {
        DataSource current = dataSource;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (current instanceof org.springframework.jdbc.datasource.DelegatingDataSource delegating) {
                DataSource target = delegating.getTargetDataSource();
                if (target != null && target != current) {
                    current = target;
                    continue;
                }
            }
            try {
                if (current.isWrapperFor(DataSource.class)) {
                    DataSource unwrapped = current.unwrap(DataSource.class);
                    if (unwrapped != null && unwrapped != current) {
                        current = unwrapped;
                        continue;
                    }
                }
            } catch (Exception ignored) {
                // 部分驱动或连接池（如 HikariDataSource）自身即最底层实体，跳过继续使用当前引用
            }
            break;
        }
        return current;
    }
}
