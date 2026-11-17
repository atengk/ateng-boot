package io.github.atengk.boot.flyway.spi;

import io.github.atengk.boot.flyway.enums.MigrationTargetKind;
import io.github.atengk.boot.flyway.model.MigrationTarget;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Flyway 迁移目标数据源路由器（SPI）
 * <p>
 * 负责发现并归类所有需要执行版本化迁移的物理数据源目标（含主库物理直连解包、
 * 显式纳管的静态多业务库，以及排除只读从库与未纳管辅助库）。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public interface FlywayTargetResolver {

    /**
     * 解析当前容器内全部受管控的迁移目标列表（按确定性顺序返回，主库优先）
     *
     * @return 受管控迁移目标列表（永不为 null）
     */
    List<MigrationTarget> resolveTargets();

    /**
     * 解析当前容器内全部受管控的 SaaS 物理租户迁移目标列表
     *
     * @return 物理租户迁移目标列表（永不为 null）
     */
    default List<MigrationTarget> resolveTenantTargets() {
        List<MigrationTarget> all = resolveTargets();
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }
        return all.stream()
                .filter(target -> target != null && target.getKind() == MigrationTargetKind.TENANT)
                .toList();
    }

    /**
     * 按目标数据源标识精确查找对应迁移目标描述符
     *
     * @param targetKey 目标数据源标识
     * @return 匹配的迁移目标；若未找到或未被纳管则返回 {@code null}
     */
    default MigrationTarget resolveTarget(String targetKey) {
        if (!StringUtils.hasText(targetKey)) {
            return null;
        }
        String normalized = targetKey.trim();
        for (MigrationTarget target : resolveTargets()) {
            if (target != null && Objects.equals(normalized, target.getTargetKey())) {
                return target;
            }
        }
        return null;
    }

    /**
     * 判断运行期动态挂载的数据源标识是否属于受控迁移范围
     *
     * @param dsKey 动态挂载数据源标识
     * @return true 表示命中租户路由或显式纳管静态目标，应触发自动迁移
     */
    default boolean isDynamicMountManaged(String dsKey) {
        return resolveTarget(dsKey) != null;
    }
}
