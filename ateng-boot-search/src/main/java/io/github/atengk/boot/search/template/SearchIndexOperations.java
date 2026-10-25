package io.github.atengk.boot.search.template;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.DeleteIndexResponse;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateResponse;
import co.elastic.clients.elasticsearch.indices.PutMappingResponse;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesResponse;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.exception.SearchException;
import io.github.atengk.boot.search.util.SearchMetadataHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.ResponseException;
import org.springframework.util.StringUtils;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        try {
            BooleanResponse response = getClient().indices().exists(b -> b.index(index));
            return response.value();
        } catch (Exception e) {
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Failed to check existence for index [" + index + "]: " + e.getMessage(), e);
        }
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
        try {
            BooleanResponse response = getClient().indices().existsAlias(b -> b.name(alias));
            return response.value();
        } catch (Exception e) {
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Failed to check existence for alias [" + alias + "]: " + e.getMessage(), e);
        }
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
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Failed to create index [" + index + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 删除指定索引
     *
     * @param index 目标索引名
     * @return 是否成功删除（若索引不存在返回 false）
     */
    public boolean delete(String index) {
        Objects.requireNonNull(index, "index must not be null");
        try {
            DeleteIndexResponse response = getClient().indices().delete(b -> b.index(index));
            log.info("Deleted index [{}] acknowledged: {}", index, response.acknowledged());
            return response.acknowledged();
        } catch (Exception e) {
            if (extractStatusCode(e) == 404) {
                log.debug("Index [{}] does not exist, deletion returns false", index);
                return false;
            }
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Failed to delete index [" + index + "]: " + e.getMessage(), e);
        }
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
        try {
            PutMappingResponse response = getClient().indices()
                    .putMapping(b -> b.index(index).withJson(new StringReader(mappingJson)));
            log.info("Updated mapping for index [{}] acknowledged: {}", index, response.acknowledged());
            return response.acknowledged();
        } catch (Exception e) {
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Failed to put mapping for index [" + index + "]: " + e.getMessage(), e);
        }
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
        try {
            GetMappingResponse response = getClient().indices().getMapping(b -> b.index(index));
            if (response.result() != null && response.result().containsKey(index)) {
                return response.result().get(index).mappings();
            }
            return null;
        } catch (Exception e) {
            if (extractStatusCode(e) == 404) {
                return null;
            }
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Failed to get mapping for index [" + index + "]: " + e.getMessage(), e);
        }
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
        try {
            PutIndexTemplateResponse response = getClient().indices()
                    .putIndexTemplate(b -> b.name(templateName).withJson(new StringReader(templateJson)));
            log.info("Put index template [{}] acknowledged: {}", templateName, response.acknowledged());
            return response.acknowledged();
        } catch (Exception e) {
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Failed to put index template [" + templateName + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 检查指定的索引模板是否存在
     *
     * @param templateName 模板名称
     * @return true 表示存在
     */
    public boolean existsIndexTemplate(String templateName) {
        Objects.requireNonNull(templateName, "templateName must not be null");
        try {
            BooleanResponse response = getClient().indices().existsIndexTemplate(b -> b.name(templateName));
            return response.value();
        } catch (Exception e) {
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Failed to check existence for index template [" + templateName + "]: " + e.getMessage(), e);
        }
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
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Failed to swap alias [" + aliasName + "] from [" + oldIndex + "] to [" + newIndex + "]: " + e.getMessage(), e);
        }
    }

    private static int extractStatusCode(Throwable t) {
        if (t == null) {
            return -1;
        }
        if (t instanceof ElasticsearchException ee) {
            return ee.status();
        }
        if (t instanceof ResponseException re) {
            return re.getResponse().getStatusLine().getStatusCode();
        }
        return extractStatusCode(t.getCause());
    }
}
