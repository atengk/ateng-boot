package io.github.atengk.boot.sample.search.scenario;

import io.github.atengk.boot.search.query.SearchQuery;
import io.github.atengk.boot.search.query.SearchQueryBuilder;
import io.github.atengk.boot.search.template.SearchTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 生产零停机平滑热迁移与 DDL 生命周期运维场景组件
 * <p>
 * 涵盖索引模板建立/销毁、Mapping 更新、服务端平滑数据重索引、物理段刷新与单次请求原子别名热切换闭环。
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZeroDowntimeReindexScenario {

    private final SearchTemplate searchTemplate;

    /**
     * 创建目标索引并定义 Mapping 结构
     *
     * @param indexName   索引名称
     * @param mappingJson Mapping 定义 JSON
     * @return 是否创建成功
     */
    public boolean initIndexWithMapping(String indexName, String mappingJson) {
        return searchTemplate.indices().create(indexName, mappingJson);
    }

    /**
     * 创建或更新时序/组件索引模板 (Index Template)
     *
     * @param templateName 模板名称
     * @param templateJson 模板定义 JSON
     * @return 是否应用成功
     */
    public boolean applyIndexTemplate(String templateName, String templateJson) {
        return searchTemplate.indices().putIndexTemplate(templateName, templateJson);
    }

    /**
     * 删除指定的索引模板 (Index Template)
     *
     * @param templateName 模板名称
     * @return 是否删除成功
     */
    public boolean removeIndexTemplate(String templateName) {
        return searchTemplate.indices().deleteIndexTemplate(templateName);
    }

    /**
     * 服务端全量平滑复制数据重索引 (Reindex)
     *
     * @param sourceIndex 源索引名
     * @param targetIndex 目标索引名
     * @return 是否执行成功
     */
    public boolean reindexAll(String sourceIndex, String targetIndex) {
        return searchTemplate.indices().reindex(sourceIndex, targetIndex);
    }

    /**
     * 服务端带条件过滤增量重索引 (Reindex with filter)
     *
     * @param sourceIndex 源索引名
     * @param targetIndex 目标索引名
     * @param minTime     起始时间边界
     * @return 是否执行成功
     */
    public boolean reindexIncremental(String sourceIndex, String targetIndex, LocalDateTime minTime) {
        SearchQuery filterQuery = SearchQueryBuilder.create()
                .gte("createTime", minTime)
                .build();
        return searchTemplate.indices().reindex(sourceIndex, targetIndex, filterQuery);
    }

    /**
     * 显式刷新索引（使物理段写入即时可读）
     *
     * @param indexName 目标索引名
     * @return 是否刷新成功
     */
    public boolean refreshIndex(String indexName) {
        return searchTemplate.indices().refresh(indexName);
    }

    /**
     * 生产原子别名热切换 (swapAlias)
     * <p>
     * 在单次请求中原子执行解除旧索引绑定并挂载新索引，零查询空窗期与零数据丢失。
     *
     * @param aliasName 别名名称
     * @param oldIndex  旧索引名（可为空）
     * @param newIndex  新索引名
     * @return 是否切换成功
     */
    public boolean switchTrafficAlias(String aliasName, String oldIndex, String newIndex) {
        return searchTemplate.indices().swapAlias(aliasName, oldIndex, newIndex);
    }
}
