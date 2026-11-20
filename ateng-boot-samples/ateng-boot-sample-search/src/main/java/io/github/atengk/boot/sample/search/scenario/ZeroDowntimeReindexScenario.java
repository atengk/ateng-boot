package io.github.atengk.boot.sample.search.scenario;

import io.github.atengk.boot.search.model.SearchIndexSettings;
import io.github.atengk.boot.search.model.SearchTaskStatus;
import io.github.atengk.boot.search.query.SearchQuery;
import io.github.atengk.boot.search.query.SearchQueryBuilder;
import io.github.atengk.boot.search.template.SearchTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

    /**
     * 创建或更新组件模板 (Component Template)
     *
     * @param templateName 组件模板名称
     * @param templateJson 组件模板 JSON
     * @return 是否应用成功
     */
    public boolean applyComponentTemplate(String templateName, String templateJson) {
        return searchTemplate.indices().putComponentTemplate(templateName, templateJson);
    }

    /**
     * 检查组件模板是否存在
     *
     * @param templateName 组件模板名称
     * @return 是否存在
     */
    public boolean existsComponentTemplate(String templateName) {
        return searchTemplate.indices().existsComponentTemplate(templateName);
    }

    /**
     * 删除组件模板
     *
     * @param templateName 组件模板名称
     * @return 是否删除成功
     */
    public boolean removeComponentTemplate(String templateName) {
        return searchTemplate.indices().deleteComponentTemplate(templateName);
    }

    /**
     * 查询索引核心运行期 Settings 摘要（零 ES 原生类泄漏）
     *
     * @param indexName 目标索引名称
     * @return 运行期 Settings 摘要字典（索引不存在时保底返回空 Map）
     */
    public Map<String, Object> getIndexSettingsSummary(String indexName) {
        SearchIndexSettings settings = searchTemplate.indices().getSettings(indexName);
        if (settings == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        if (settings.getNumberOfShards() != null) {
            summary.put("numberOfShards", settings.getNumberOfShards());
        }
        if (settings.getNumberOfReplicas() != null) {
            summary.put("numberOfReplicas", settings.getNumberOfReplicas());
        }
        if (settings.getRefreshInterval() != null) {
            summary.put("refreshInterval", settings.getRefreshInterval());
        }
        return summary;
    }

    /**
     * 动态调整索引运行期 Settings
     *
     * @param indexName    目标索引名称
     * @param settingsJson Settings 配置 JSON
     * @return 是否确认生效
     */
    public boolean updateIndexSettings(String indexName, String settingsJson) {
        return searchTemplate.indices().putSettings(indexName, settingsJson);
    }

    /**
     * 为索引精细挂载别名（支持指定 {@code is_write_index} 主写索引标记）
     *
     * @param indexName    物理索引名
     * @param aliasName    别名名称
     * @param isWriteIndex 是否为主写索引
     * @return 是否成功
     */
    public boolean addAlias(String indexName, String aliasName, boolean isWriteIndex) {
        return searchTemplate.indices().addAlias(indexName, aliasName, isWriteIndex);
    }

    /**
     * 解绑索引别名
     *
     * @param indexName 物理索引名
     * @param aliasName 别名名称
     * @return 是否成功
     */
    public boolean removeAlias(String indexName, String aliasName) {
        return searchTemplate.indices().removeAlias(indexName, aliasName);
    }

    /**
     * 查询索引当前关联的所有别名集合
     *
     * @param indexName 目标索引名称
     * @return 别名名称集合
     */
    public Set<String> getAliases(String indexName) {
        return searchTemplate.indices().getAliases(indexName);
    }

    /**
     * 服务端异步发起全量 Reindex 任务并立即返回 TaskId
     *
     * @param sourceIndex 源索引名
     * @param targetIndex 目标索引名
     * @return 异步任务标识 TaskId
     */
    public String reindexAsync(String sourceIndex, String targetIndex) {
        return searchTemplate.indices().reindexAsync(sourceIndex, targetIndex);
    }

    /**
     * 查询异步后台任务即时进度
     *
     * @param taskId 任务唯一标识
     * @return 强类型任务状态模型（不存在时返回 null）
     */
    public SearchTaskStatus getTaskStatus(String taskId) {
        return searchTemplate.indices().getTaskStatus(taskId);
    }

    /**
     * 轮询等待异步后台任务进入终态
     *
     * @param taskId  任务唯一标识
     * @param timeout 最大等待超时时间
     * @return 终态任务状态快照
     */
    public SearchTaskStatus waitForTask(String taskId, Duration timeout) {
        return searchTemplate.indices().waitForTask(taskId, timeout, Duration.ofMillis(100));
    }

    /**
     * 取消执行中的异步后台任务
     *
     * @param taskId 任务唯一标识
     * @return 是否受理取消成功
     */
    public boolean cancelTask(String taskId) {
        return searchTemplate.indices().cancelTask(taskId);
    }
}
