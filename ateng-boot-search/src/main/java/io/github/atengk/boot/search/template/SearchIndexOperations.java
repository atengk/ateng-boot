package io.github.atengk.boot.search.template;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.cluster.DeleteComponentTemplateResponse;
import co.elastic.clients.elasticsearch.cluster.PutComponentTemplateResponse;
import co.elastic.clients.elasticsearch.core.ReindexResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.DeleteIndexResponse;
import co.elastic.clients.elasticsearch.indices.DeleteIndexTemplateResponse;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.GetIndexTemplateResponse;
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsResponse;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateResponse;
import co.elastic.clients.elasticsearch.indices.PutIndicesSettingsResponse;
import co.elastic.clients.elasticsearch.indices.PutMappingResponse;
import co.elastic.clients.elasticsearch.indices.RefreshResponse;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesResponse;
import co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases;
import co.elastic.clients.elasticsearch.indices.get_index_template.IndexTemplateItem;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import co.elastic.clients.elasticsearch.tasks.CancelResponse;
import co.elastic.clients.elasticsearch.tasks.GetTasksResponse;
import co.elastic.clients.elasticsearch.tasks.TaskInfo;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.exception.SearchException;
import io.github.atengk.boot.search.model.SearchIndexSettings;
import io.github.atengk.boot.search.model.SearchIndexTemplate;
import io.github.atengk.boot.search.model.SearchTaskStatus;
import io.github.atengk.boot.search.query.SearchQuery;
import io.github.atengk.boot.search.util.SearchExceptionClassifier;
import io.github.atengk.boot.search.util.SearchMetadataHelper;
import io.github.atengk.boot.search.util.SearchTenantHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.ResponseException;
import org.springframework.util.StringUtils;

import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 搜索引擎结构面 (DDL) 专职运维门面
 * <p>
 * 专职管理索引创建、存在性检查、删除、Mapping 映射维护、时序索引模板以及生产原子别名热切换 (swapAlias)。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Slf4j
@RequiredArgsConstructor
public class SearchIndexOperations {

    private final SearchTemplate searchTemplate;

    private ElasticsearchClient getClient() {
        return searchTemplate.getClient();
    }

    /**
     * 判断指定索引名或别名是否存在
     *
     * @param index 索引名或别名
     * @return true 表示存在
     */
    public boolean exists(String index) {
        Objects.requireNonNull(index, "index must not be null");
        return searchTemplate.executeObserved("indices.exists", index, null, null, null, false, () -> {
            try {
                BooleanResponse response = getClient().indices().exists(b -> b.index(index));
                return response.value();
            } catch (Exception e) {
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to check existence for index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 判断实体类注解声明的索引是否存在
     *
     * @param clazz 实体类类型
     * @return true 表示存在
     */
    public boolean exists(Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return exists(index);
    }

    /**
     * 判断指定的别名是否存在
     *
     * @param alias 待检查的别名
     * @return true 表示存在
     */
    public boolean existsAlias(String alias) {
        Objects.requireNonNull(alias, "alias must not be null");
        return searchTemplate.executeObserved("indices.existsAlias", alias, null, null, null, false, () -> {
            try {
                BooleanResponse response = getClient().indices().existsAlias(b -> b.name(alias));
                return response.value();
            } catch (Exception e) {
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to check existence for alias [" + alias + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 创建索引（默认空配置）
     *
     * @param index 目标索引名
     * @return 是否创建成功
     */
    public boolean create(String index) {
        return create(index, null);
    }

    /**
     * 根据实体类注解解析索引名并创建索引
     *
     * @param clazz 实体类类型
     * @return 是否创建成功
     */
    public boolean create(Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return create(index, null);
    }

    /**
     * 创建索引并指定 Mapping 结构（支持 JSON 文本或 DSL）
     *
     * @param index              目标索引名
     * @param mappingJsonOrDsl   Mapping JSON 结构定义字符串（可为空）
     * @return 是否创建成功
     */
    public boolean create(String index, String mappingJsonOrDsl) {
        Objects.requireNonNull(index, "index must not be null");
        return searchTemplate.executeObserved("indices.create", index, null, null, null, false, () -> {
            try {
                CreateIndexResponse response = getClient().indices().create(b -> {
                    b.index(index);
                    if (StringUtils.hasText(mappingJsonOrDsl)) {
                        b.withJson(new StringReader(mappingJsonOrDsl));
                    }
                    return b;
                });
                log.info("Created index [{}] acknowledged: {}", index, response.acknowledged());
                return response.acknowledged();
            } catch (Exception e) {
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to create index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 删除指定索引
     *
     * @param index 目标索引名
     * @return 是否成功删除（若索引不存在返回 false）
     */
    public boolean delete(String index) {
        Objects.requireNonNull(index, "index must not be null");
        return searchTemplate.executeObserved("indices.delete", index, null, null, null, false, () -> {
            try {
                DeleteIndexResponse response = getClient().indices().delete(b -> b.index(index));
                log.info("Deleted index [{}] acknowledged: {}", index, response.acknowledged());
                return response.acknowledged();
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    log.debug("Index [{}] does not exist, deletion returns false", index);
                    return false;
                }
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to delete index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 根据实体类删除对应的索引
     *
     * @param clazz 实体类类型
     * @return 是否成功删除
     */
    public boolean delete(Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return delete(index);
    }

    /**
     * 更新指定索引的 Mapping 映射
     *
     * @param index       目标索引名
     * @param mappingJson Mapping JSON 字符串
     * @return 是否成功确认更新
     */
    public boolean putMapping(String index, String mappingJson) {
        Objects.requireNonNull(index, "index must not be null");
        Objects.requireNonNull(mappingJson, "mappingJson must not be null");
        return searchTemplate.executeObserved("indices.putMapping", index, null, null, null, false, () -> {
            try {
                PutMappingResponse response = getClient().indices()
                        .putMapping(b -> b.index(index).withJson(new StringReader(mappingJson)));
                log.info("Updated mapping for index [{}] acknowledged: {}", index, response.acknowledged());
                return response.acknowledged();
            } catch (Exception e) {
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to put mapping for index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 根据实体类更新对应的 Mapping 映射
     *
     * @param clazz       实体类类型
     * @param mappingJson Mapping JSON 字符串
     * @return 是否成功确认更新
     */
    public boolean putMapping(Class<?> clazz, String mappingJson) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return putMapping(index, mappingJson);
    }

    /**
     * 获取指定索引的 Mapping 结构
     *
     * @param index 目标索引名
     * @return TypeMapping 对象（若索引不存在则返回 null）
     */
    public TypeMapping getMapping(String index) {
        Objects.requireNonNull(index, "index must not be null");
        return searchTemplate.executeObserved("indices.getMapping", index, null, null, null, false, () -> {
            try {
                GetMappingResponse response = getClient().indices().getMapping(b -> b.index(index));
                if (response.result() != null && response.result().containsKey(index)) {
                    return response.result().get(index).mappings();
                }
                return null;
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    return null;
                }
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to get mapping for index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 创建或更新可组合/时序索引模板 (Index Template)
     *
     * @param templateName 模板名称
     * @param templateJson 模板定义 JSON 字符串
     * @return 是否成功确认
     */
    public boolean putIndexTemplate(String templateName, String templateJson) {
        Objects.requireNonNull(templateName, "templateName must not be null");
        Objects.requireNonNull(templateJson, "templateJson must not be null");
        return searchTemplate.executeObserved("indices.putIndexTemplate", templateName, null, null, null, false, () -> {
            try {
                PutIndexTemplateResponse response = getClient().indices()
                        .putIndexTemplate(b -> b.name(templateName).withJson(new StringReader(templateJson)));
                log.info("Put index template [{}] acknowledged: {}", templateName, response.acknowledged());
                return response.acknowledged();
            } catch (Exception e) {
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to put index template [" + templateName + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 检查指定的索引模板是否存在
     *
     * @param templateName 模板名称
     * @return true 表示存在
     */
    public boolean existsIndexTemplate(String templateName) {
        Objects.requireNonNull(templateName, "templateName must not be null");
        return searchTemplate.executeObserved("indices.existsIndexTemplate", templateName, null, null, null, false, () -> {
            try {
                BooleanResponse response = getClient().indices().existsIndexTemplate(b -> b.name(templateName));
                return response.value();
            } catch (Exception e) {
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to check existence for index template [" + templateName + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 删除指定的索引模板 (Index Template)
     *
     * @param templateName 模板名称
     * @return 是否成功删除（若模板不存在返回 false）
     */
    public boolean deleteIndexTemplate(String templateName) {
        Objects.requireNonNull(templateName, "templateName must not be null");
        return searchTemplate.executeObserved("indices.deleteIndexTemplate", templateName, null, null, null, false, () -> {
            try {
                DeleteIndexTemplateResponse response = getClient().indices()
                        .deleteIndexTemplate(b -> b.name(templateName));
                log.info("Deleted index template [{}] acknowledged: {}", templateName, response.acknowledged());
                return response.acknowledged();
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    log.debug("Index template [{}] does not exist, deletion returns false", templateName);
                    return false;
                }
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to delete index template [" + templateName + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 生产原子别名热切换 (Atomic Alias Swap)
     * <p>
     * 在底层单次 {@code _aliases} 请求中原子执行 {@code remove} 旧索引绑定 + {@code add} 新索引绑定，
     * 彻底消除别名切换期间的无索引空窗期，保证上层查询零感知与数据零丢失。
     *
     * @param aliasName 别名名称
     * @param oldIndex  原绑定的旧索引名（若为 null 或空字符串则仅执行 add 新增绑定）
     * @param newIndex  新绑定的目标索引名
     * @return 是否成功完成原子切换
     */
    public boolean swapAlias(String aliasName, String oldIndex, String newIndex) {
        Objects.requireNonNull(aliasName, "aliasName must not be null");
        Objects.requireNonNull(newIndex, "newIndex must not be null");
        return searchTemplate.executeObserved("indices.swapAlias", newIndex, null, null, null, false, () -> {
            try {
                List<Action> actions = new ArrayList<>();
                if (StringUtils.hasText(oldIndex)) {
                    actions.add(Action.of(a -> a.remove(r -> r.index(oldIndex).alias(aliasName))));
                }
                actions.add(Action.of(a -> a.add(ad -> ad.index(newIndex).alias(aliasName))));

                UpdateAliasesResponse response = getClient().indices().updateAliases(b -> b.actions(actions));
                log.info("Swapped alias [{}] from old index [{}] to new index [{}], acknowledged: {}",
                        aliasName, oldIndex, newIndex, response.acknowledged());
                return response.acknowledged();
            } catch (Exception e) {
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to swap alias [" + aliasName + "] from [" + oldIndex + "] to [" + newIndex + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 显式刷新指定索引（Lucene Segment 物理持久化与即时可读）
     * <p>
     * 触发索引的刷新操作，确保在此之前执行的写入、更新或删除操作立即对后续检索可见。
     *
     * @param index 目标索引名
     * @return 刷新是否成功（分片失败数为 0）
     */
    public boolean refresh(String index) {
        Objects.requireNonNull(index, "index must not be null");
        return searchTemplate.executeObserved("indices.refresh", index, null, null, null, false, () -> {
            try {
                RefreshResponse response = getClient().indices().refresh(b -> b.index(index));
                boolean success = response.shards() == null || response.shards().failed() == null || response.shards().failed().intValue() == 0;
                log.info("Refreshed index [{}], successful shards: {}, failed shards: {}",
                        index,
                        response.shards() != null ? response.shards().successful() : null,
                        response.shards() != null ? response.shards().failed() : 0);
                return success;
            } catch (Exception e) {
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to refresh index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 根据实体类显式刷新对应的索引
     *
     * @param clazz 实体类类型
     * @return 刷新是否成功
     */
    public boolean refresh(Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return refresh(index);
    }

    /**
     * 在 Elasticsearch 服务端将源索引全量数据重索引至目标索引
     * <p>
     * 与 {@link #swapAlias} 配合形成生产零停机数据迁移闭环。
     *
     * @param sourceIndex 源索引名
     * @param targetIndex 目标索引名
     * @return 是否重索引成功（无失败批次）
     */
    public boolean reindex(String sourceIndex, String targetIndex) {
        return reindex(sourceIndex, targetIndex, null);
    }

    /**
     * 在 Elasticsearch 服务端将源索引数据按条件过滤重索引至目标索引
     *
     * @param sourceIndex 源索引名
     * @param targetIndex 目标索引名
     * @param filterQuery 过滤条件规格（可为空，为空时执行全量复制）
     * @return 是否重索引成功（无失败批次）
     */
    public boolean reindex(String sourceIndex, String targetIndex, SearchQuery filterQuery) {
        ReindexResponse response = executeReindex(sourceIndex, targetIndex, filterQuery, true);
        boolean noFailures = response.failures() == null || response.failures().isEmpty();
        boolean notTimedOut = !Boolean.TRUE.equals(response.timedOut());
        boolean success = noFailures && notTimedOut;
        log.info("Reindexed from [{}] to [{}], total: {}, created: {}, updated: {}, failures: {}, timedOut: {}",
                sourceIndex, targetIndex, response.total(), response.created(), response.updated(),
                response.failures() != null ? response.failures().size() : 0, response.timedOut());
        return success;
    }

    /**
     * 在服务端异步发起全量数据重索引任务，立即返回 TaskId 供后续进度轮询或取消治理
     *
     * @param sourceIndex 源索引名
     * @param targetIndex 目标索引名
     * @return 异步任务 ID（格式如 {@code nodeId:taskId}）
     */
    public String reindexAsync(String sourceIndex, String targetIndex) {
        return reindexAsync(sourceIndex, targetIndex, null);
    }

    /**
     * 在服务端异步发起按条件过滤的数据重索引任务，立即返回 TaskId
     *
     * @param sourceIndex 源索引名
     * @param targetIndex 目标索引名
     * @param filterQuery 过滤条件规格（可为 null）
     * @return 异步任务 ID（格式如 {@code nodeId:taskId}）
     */
    public String reindexAsync(String sourceIndex, String targetIndex, SearchQuery filterQuery) {
        ReindexResponse response = executeReindex(sourceIndex, targetIndex, filterQuery, false);
        log.info("Submitted async reindex from [{}] to [{}], taskId: {}", sourceIndex, targetIndex, response.task());
        return response.task();
    }

    private ReindexResponse executeReindex(String sourceIndex, String targetIndex,
                                           SearchQuery filterQuery, boolean waitForCompletion) {
        if (!StringUtils.hasText(sourceIndex)) {
            throw new IllegalArgumentException("sourceIndex must not be blank");
        }
        if (!StringUtils.hasText(targetIndex)) {
            throw new IllegalArgumentException("targetIndex must not be blank");
        }
        if (sourceIndex.trim().equals(targetIndex.trim())) {
            throw new IllegalArgumentException("sourceIndex and targetIndex must not be the same: [" + sourceIndex + "]");
        }
        SearchTenantHelper.rejectCrossTenantQueryFilter(
                filterQuery != null ? filterQuery.getQuery() : null,
                searchTemplate.getProperties(),
                SearchTenantHelper.resolveObjectMapper(searchTemplate.getSearchEngineClient())
        );
        Query effectiveSourceQuery = searchTemplate.wrapTenantFilterIfNecessary(
                filterQuery != null ? filterQuery.getQuery() : null, null);
        String actionName = waitForCompletion ? "indices.reindex" : "indices.reindexAsync";
        return searchTemplate.executeObserved(actionName, targetIndex, null, null, filterQuery, false, () -> {
            try {
                return getClient().reindex(b -> {
                    b.source(s -> {
                        s.index(sourceIndex);
                        if (effectiveSourceQuery != null) {
                            s.query(effectiveSourceQuery);
                        }
                        return s;
                    });
                    b.dest(d -> d.index(targetIndex));
                    b.conflicts(Conflicts.Proceed);
                    b.waitForCompletion(waitForCompletion);
                    return b;
                });
            } catch (Exception e) {
                if (e instanceof SearchException se) {
                    throw se;
                }
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to reindex from [" + sourceIndex + "] to [" + targetIndex + "]: " + e.getMessage(), e);
            }
        });
    }

    // ==================== Component Template & Index Template 运维 ====================

    /**
     * 创建或更新组件模板 (Component Template)
     *
     * @param templateName 组件模板名称
     * @param templateJson 组件模板 JSON（包含 {@code template} 对象，如 settings / mappings）
     * @return 是否成功确认
     */
    public boolean putComponentTemplate(String templateName, String templateJson) {
        if (!StringUtils.hasText(templateName)) {
            throw new IllegalArgumentException("templateName must not be blank");
        }
        if (!StringUtils.hasText(templateJson)) {
            throw new IllegalArgumentException("templateJson must not be blank");
        }
        return searchTemplate.executeObserved("cluster.putComponentTemplate", templateName, null, null, null, false, () -> {
            try {
                PutComponentTemplateResponse response = getClient().cluster()
                        .putComponentTemplate(b -> b.withJson(new StringReader(templateJson)).name(templateName));
                return response.acknowledged();
            } catch (Exception e) {
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to put component template [" + templateName + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 检查指定的组件模板是否存在
     *
     * @param templateName 组件模板名称
     * @return true 表示存在
     */
    public boolean existsComponentTemplate(String templateName) {
        if (!StringUtils.hasText(templateName)) {
            throw new IllegalArgumentException("templateName must not be blank");
        }
        return searchTemplate.executeObserved("cluster.existsComponentTemplate", templateName, null, null, null, false, () -> {
            try {
                BooleanResponse response = getClient().cluster().existsComponentTemplate(b -> b.name(templateName));
                return response.value();
            } catch (Exception e) {
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to check component template existence [" + templateName + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 删除指定的组件模板 (Component Template)
     *
     * @param templateName 组件模板名称
     * @return 是否成功删除（不存在时返回 false）
     */
    public boolean deleteComponentTemplate(String templateName) {
        if (!StringUtils.hasText(templateName)) {
            throw new IllegalArgumentException("templateName must not be blank");
        }
        return searchTemplate.executeObserved("cluster.deleteComponentTemplate", templateName, null, null, null, false, () -> {
            try {
                DeleteComponentTemplateResponse response = getClient().cluster()
                        .deleteComponentTemplate(b -> b.name(templateName));
                return response.acknowledged();
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    return false;
                }
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to delete component template [" + templateName + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 查询指定索引模板 (Index Template) 的强类型脱耦定义
     *
     * @param templateName 索引模板名称
     * @return {@link SearchIndexTemplate} 视图模型（不存在时返回 null）
     */
    public SearchIndexTemplate getIndexTemplate(String templateName) {
        if (!StringUtils.hasText(templateName)) {
            throw new IllegalArgumentException("templateName must not be blank");
        }
        return searchTemplate.executeObserved("indices.getIndexTemplate", templateName, null, null, null, false, () -> {
            try {
                GetIndexTemplateResponse response = getClient().indices().getIndexTemplate(b -> b.name(templateName));
                if (response.indexTemplates() == null || response.indexTemplates().isEmpty()) {
                    return null;
                }
                IndexTemplateItem item = response.indexTemplates().get(0);
                co.elastic.clients.elasticsearch.indices.IndexTemplate tpl = item.indexTemplate();
                return SearchIndexTemplate.builder()
                        .name(item.name())
                        .indexPatterns(tpl != null && tpl.indexPatterns() != null ? tpl.indexPatterns() : Collections.emptyList())
                        .composedOf(tpl != null && tpl.composedOf() != null ? tpl.composedOf() : Collections.emptyList())
                        .priority(tpl != null ? tpl.priority() : null)
                        .version(tpl != null ? tpl.version() : null)
                        .build();
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    return null;
                }
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to get index template [" + templateName + "]: " + e.getMessage(), e);
            }
        });
    }

    // ==================== Settings 动态调优与 Alias 精细治理 ====================

    /**
     * 获取指定索引的运行期 Settings 强类型视图模型
     *
     * @param index 目标索引名
     * @return {@link SearchIndexSettings} 视图模型（索引不存在时返回 null）
     */
    public SearchIndexSettings getSettings(String index) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("index must not be blank");
        }
        return searchTemplate.executeObserved("indices.getSettings", index, null, null, null, false, () -> {
            try {
                GetIndicesSettingsResponse response = getClient().indices().getSettings(b -> b.index(index));
                IndexState state = response.get(index);
                if (state == null && response.result() != null && !response.result().isEmpty()) {
                    state = response.result().values().iterator().next();
                }
                IndexSettings root = state != null ? state.settings() : null;
                if (root == null) {
                    return null;
                }
                IndexSettings effective = root.index() != null ? root.index() : root;
                String refreshInterval = null;
                if (effective.refreshInterval() != null) {
                    refreshInterval = effective.refreshInterval().isTime()
                            ? effective.refreshInterval().time()
                            : String.valueOf(effective.refreshInterval()._get());
                }
                return SearchIndexSettings.builder()
                        .numberOfShards(effective.numberOfShards())
                        .numberOfReplicas(effective.numberOfReplicas())
                        .refreshInterval(refreshInterval)
                        .maxResultWindow(effective.maxResultWindow())
                        .build();
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    return null;
                }
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to get settings for index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 动态修改指定索引的运行期 Settings（例如调整 {@code refresh_interval} 或 {@code number_of_replicas}）
     *
     * @param index        目标索引名
     * @param settingsJson Settings JSON 字符串（支持带或不含外层 {@code settings} 包装）
     * @return 是否确认生效
     */
    public boolean putSettings(String index, String settingsJson) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("index must not be blank");
        }
        if (!StringUtils.hasText(settingsJson)) {
            throw new IllegalArgumentException("settingsJson must not be blank");
        }
        return searchTemplate.executeObserved("indices.putSettings", index, null, null, null, false, () -> {
            try {
                boolean hasSettingsWrapper = hasTopLevelSettingsKey(settingsJson);
                PutIndicesSettingsResponse response = getClient().indices().putSettings(b -> {
                    if (hasSettingsWrapper) {
                        return b.withJson(new StringReader(settingsJson)).index(index);
                    }
                    return b.index(index).settings(s -> s.withJson(new StringReader(settingsJson)));
                });
                return response.acknowledged();
            } catch (Exception e) {
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to put settings for index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    private boolean hasTopLevelSettingsKey(String settingsJson) {
        try {
            ObjectMapper mapper = SearchTenantHelper.resolveObjectMapper(searchTemplate.getSearchEngineClient());
            JsonNode root = mapper.readTree(settingsJson);
            return root != null && root.isObject() && root.has("settings") && root.get("settings").isObject();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 为索引绑定别名
     *
     * @param index 物理索引名
     * @param alias 别名名称
     * @return 是否成功
     */
    public boolean addAlias(String index, String alias) {
        return addAlias(index, alias, false);
    }

    /**
     * 为索引绑定别名并可声明是否作为写入主索引 ({@code is_write_index})
     *
     * @param index        物理索引名
     * @param alias        别名名称
     * @param isWriteIndex 是否设为该别名的唯一写入主索引
     * @return 是否成功
     */
    public boolean addAlias(String index, String alias, boolean isWriteIndex) {
        if (!StringUtils.hasText(index) || !StringUtils.hasText(alias)) {
            throw new IllegalArgumentException("index and alias must not be blank");
        }
        return searchTemplate.executeObserved("indices.addAlias", index, null, null, null, false, () -> {
            try {
                UpdateAliasesResponse response = getClient().indices().updateAliases(b -> b.actions(
                        Action.of(a -> a.add(ad -> {
                            ad.index(index).alias(alias);
                            if (isWriteIndex) {
                                ad.isWriteIndex(true);
                            }
                            return ad;
                        }))
                ));
                return response.acknowledged();
            } catch (Exception e) {
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to add alias [" + alias + "] to index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 从指定索引解绑别名
     *
     * @param index 物理索引名
     * @param alias 别名名称
     * @return 是否成功
     */
    public boolean removeAlias(String index, String alias) {
        if (!StringUtils.hasText(index) || !StringUtils.hasText(alias)) {
            throw new IllegalArgumentException("index and alias must not be blank");
        }
        return searchTemplate.executeObserved("indices.removeAlias", index, null, null, null, false, () -> {
            try {
                UpdateAliasesResponse response = getClient().indices().updateAliases(b -> b.actions(
                        Action.of(a -> a.remove(r -> r.index(index).alias(alias)))
                ));
                return response.acknowledged();
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    return false;
                }
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to remove alias [" + alias + "] from index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 查询指定索引关联的全部别名集合
     *
     * @param index 目标索引名
     * @return 别名名称集合（无别名或索引不存在时保底返回空集合）
     */
    public Set<String> getAliases(String index) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("index must not be blank");
        }
        return searchTemplate.executeObserved("indices.getAliases", index, null, null, null, false, () -> {
            try {
                GetAliasResponse response = getClient().indices().getAlias(b -> b.index(index));
                if (response.result() == null || response.result().isEmpty()) {
                    return Collections.emptySet();
                }
                Set<String> aliases = new LinkedHashSet<>();
                for (IndexAliases indexAliases : response.result().values()) {
                    if (indexAliases != null && indexAliases.aliases() != null) {
                        aliases.addAll(indexAliases.aliases().keySet());
                    }
                }
                return aliases;
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    return Collections.emptySet();
                }
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to get aliases for index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    // ==================== 异步 Task 状态追踪、阻塞等待与安全取消 ====================

    /**
     * 查询指定异步后台任务的即时状态与进度指标
     *
     * @param taskId 任务唯一标识（格式如 {@code nodeId:taskId}）
     * @return 强类型任务状态模型（任务已不存在或过期清理时返回 null）
     */
    public SearchTaskStatus getTaskStatus(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return searchTemplate.executeObserved("tasks.get", taskId, null, null, null, false, () -> {
            try {
                GetTasksResponse response = getClient().tasks().get(b -> b.taskId(taskId));
                return toSearchTaskStatus(taskId, response);
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    return null;
                }
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to get task status for [" + taskId + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 轮询等待异步后台任务完成直至进入终态或超时
     *
     * @param taskId       任务唯一标识
     * @param timeout      最大等待超时时间（需大于 0）
     * @param pollInterval 轮询间隔（可为 null 或正数，null/0 默认采用 200ms）
     * @return 完成或被取消后的任务最终状态快照
     */
    public SearchTaskStatus waitForTask(String taskId, Duration timeout, Duration pollInterval) {
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        if (pollInterval != null && pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must not be negative: " + pollInterval);
        }
        long pollMs = (pollInterval == null || pollInterval.isZero()) ? 200L : Math.max(1L, pollInterval.toMillis());
        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        while (true) {
            SearchTaskStatus status = getTaskStatus(taskId);
            if (status == null) {
                throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR, "Task not found: [" + taskId + "]");
            }
            if (status.isCompleted() || status.isCancelled()) {
                return status;
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0) {
                throw new SearchException(SearchResultCode.SEARCH_TIMEOUT_ERROR,
                        "Timed out waiting for task [" + taskId + "] after " + timeout);
            }
            try {
                Thread.sleep(Math.min(pollMs, remainingMs));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                        "Interrupted while waiting for task [" + taskId + "]", ie);
            }
        }
    }

    /**
     * 请求取消正在执行的异步后台任务
     *
     * @param taskId 任务唯一标识
     * @return true 表示取消请求已受理且无节点/任务级取消失败（任务不存在时返回 false）
     */
    public boolean cancelTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return searchTemplate.executeObserved("tasks.cancel", taskId, null, null, null, false, () -> {
            try {
                CancelResponse response = getClient().tasks().cancel(b -> b.taskId(taskId));
                boolean noNodeFailures = response.nodeFailures() == null || response.nodeFailures().isEmpty();
                boolean noTaskFailures = response.taskFailures() == null || response.taskFailures().isEmpty();
                return noNodeFailures && noTaskFailures;
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    return false;
                }
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to cancel task [" + taskId + "]: " + e.getMessage(), e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private SearchTaskStatus toSearchTaskStatus(String taskId, GetTasksResponse response) {
        TaskInfo task = response.task();
        long total = 0L;
        long created = 0L;
        long updated = 0L;
        long deleted = 0L;
        long versionConflicts = 0L;
        if (task != null && task.status() != null) {
            Map<String, Object> statusMap = task.status().to(Map.class);
            if (statusMap != null) {
                total = asLong(statusMap.get("total"));
                created = asLong(statusMap.get("created"));
                updated = asLong(statusMap.get("updated"));
                deleted = asLong(statusMap.get("deleted"));
                versionConflicts = asLong(statusMap.get("version_conflicts"));
            }
        }
        List<String> failures = Collections.emptyList();
        if (response.response() != null) {
            Map<String, Object> bodyMap = response.response().to(Map.class);
            if (bodyMap != null && bodyMap.get("failures") instanceof List<?> rawFailures && !rawFailures.isEmpty()) {
                failures = rawFailures.stream().map(String::valueOf).toList();
            }
        }
        String errorReason = response.error() != null ? response.error().reason() : null;
        return SearchTaskStatus.builder()
                .taskId(taskId)
                .completed(response.completed())
                .cancelled(task != null && Boolean.TRUE.equals(task.cancelled()))
                .description(task != null ? task.description() : null)
                .runningTimeNanos(task != null ? task.runningTimeInNanos() : 0L)
                .total(total)
                .created(created)
                .updated(updated)
                .deleted(deleted)
                .versionConflicts(versionConflicts)
                .failures(failures)
                .errorReason(errorReason)
                .build();
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }
}
