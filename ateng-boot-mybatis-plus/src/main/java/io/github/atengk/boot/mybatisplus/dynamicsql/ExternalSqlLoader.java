package io.github.atengk.boot.mybatisplus.dynamicsql;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 外部动态 SQL 加载器 SPI 契约接口
 * 负责从外部媒介（数据库配置表、分布式配置中心、远程 HTTP 或文件系统）拉取并提供动态 SQL 脚本定义
 *
 * @author Ateng
 * @since 2026-10-18
 */
public interface ExternalSqlLoader {

    /**
     * 全量加载所有外部动态 SQL 脚本定义
     *
     * @return 动态 SQL 定义列表（无匹配数据时返回空集合，严禁返回 null）
     */
    List<DynamicSqlDefinition> loadAll();

    /**
     * 根据全局唯一 StatementId 获取对应的动态 SQL 脚本定义
     * 默认通过全量扫描过滤获取，子类可根据外部媒介特性（如根据主键查询数据库）进行高性能覆写
     *
     * @param id 语句唯一标识
     * @return 动态 SQL 定义（可能为空）
     */
    default Optional<DynamicSqlDefinition> loadById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        List<DynamicSqlDefinition> all = loadAll();
        if (all == null || all.isEmpty()) {
            return Optional.empty();
        }
        return all.stream()
                .filter(def -> Objects.equals(def.getId(), id))
                .findFirst();
    }
}
