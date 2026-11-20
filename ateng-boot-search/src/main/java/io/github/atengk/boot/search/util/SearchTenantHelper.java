package io.github.atengk.boot.search.util;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.enums.TenantIsolationMode;
import io.github.atengk.boot.search.exception.SearchException;
import io.github.atengk.boot.search.properties.AtengSearchProperties;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多租户写入注入、篡改拦截与归属校验协同辅助器
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Slf4j
public final class SearchTenantHelper {

    private static final ObjectMapper FALLBACK_OBJECT_MAPPER = createFallbackObjectMapper();

    private SearchTenantHelper() {
    }

    private static ObjectMapper createFallbackObjectMapper() {
        ObjectMapper mapper = JsonUtil.createLongToStringObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
        return mapper;
    }

    /**
     * 判断当前操作是否应启用多租户治理
     *
     * @param properties  搜索配置属性
     * @param entityClass 实体类类型（可为 null）
     * @return true 表示存在有效租户标识且未触发任何豁免
     */
    public static boolean isTenantEnforcementActive(AtengSearchProperties properties, Class<?> entityClass) {
        return resolveActiveTenantId(properties, entityClass) != null;
    }

    /**
     * 单次读取并解析当前生效的租户标识（若未开启多租户、已豁免或当前无租户则返回 null）
     *
     * @param properties  搜索配置属性
     * @param entityClass 实体类类型（可为 null）
     * @return 去除首尾空白后的生效租户标识，未生效返回 null
     */
    public static String resolveActiveTenantId(AtengSearchProperties properties, Class<?> entityClass) {
        if (properties == null || !properties.getMultiTenancy().isEnabled()) {
            return null;
        }
        if (TenantContextHolder.isIgnoreTenant() || SearchMetadataHelper.hasIgnoreTenant(entityClass)) {
            return null;
        }
        String rawTenantId = TenantContextHolder.getTenantId();
        return StringUtils.hasText(rawTenantId) ? rawTenantId.trim() : null;
    }

    /**
     * 判断是否启用严格隔离模式 ({@link TenantIsolationMode#STRICT})
     *
     * @param properties 搜索配置属性
     * @return true 表示严格模式
     */
    public static boolean isStrictMode(AtengSearchProperties properties) {
        return properties == null || properties.getMultiTenancy().getIsolationMode() != TenantIsolationMode.FILTER_ONLY;
    }

    /**
     * 解析当前生效的租户列名
     *
     * @param properties 搜索配置属性
     * @return 租户列名
     */
    public static String resolveTenantColumn(AtengSearchProperties properties) {
        if (properties != null && StringUtils.hasText(properties.getMultiTenancy().getColumn())) {
            return properties.getMultiTenancy().getColumn().trim();
        }
        return "tenant_id";
    }

    /**
     * 获取与底层传输通道一致的 {@link ObjectMapper} 实例
     *
     * @param client 搜索引擎客户端 SPI
     * @return ObjectMapper 实例
     */
    public static ObjectMapper resolveObjectMapper(SearchEngineClient client) {
        if (client != null) {
            try {
                RestClientTransport transport = client.getNativeClient(RestClientTransport.class);
                if (transport != null && transport.jsonpMapper() instanceof JacksonJsonpMapper jacksonMapper) {
                    return jacksonMapper.objectMapper();
                }
            } catch (Exception ignored) {
                // 非 RestClientTransport 或自定义 Mock 实现回退使用默认 long-to-string ObjectMapper
            }
        }
        return FALLBACK_OBJECT_MAPPER;
    }

    /**
     * 在写入/更新前校验跨租户篡改并安全注入当前租户列值（兼容 POJO、Map 与不可变 Record）
     *
     * @param document     待写入或局部更新的文档对象
     * @param entityClass  实体类类型（Map 场景可传 null）
     * @param properties   搜索配置属性
     * @param objectMapper Jackson 序列化器
     * @return 校验与注入后的出站载荷对象
     */
    public static Object preparePayloadForWrite(Object document,
                                                Class<?> entityClass,
                                                AtengSearchProperties properties,
                                                ObjectMapper objectMapper) {
        if (document == null) {
            return null;
        }
        String currentTenantId = resolveActiveTenantId(properties, entityClass);
        if (currentTenantId == null) {
            return document;
        }
        String tenantColumn = resolveTenantColumn(properties);

        if (document instanceof Map<?, ?> map) {
            Object existing = map.get(tenantColumn);
            if (existing != null) {
                String existingStr = String.valueOf(existing).trim();
                if (StringUtils.hasText(existingStr)) {
                    if (!currentTenantId.equals(existingStr)) {
                        throw new SearchException(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED,
                                "Cross-tenant write tampering blocked: current tenant [" + currentTenantId
                                        + "] cannot write document owned by [" + existingStr + "]");
                    }
                    return document;
                }
            }
            Map<Object, Object> enrichedMap = new LinkedHashMap<>(map);
            enrichedMap.put(tenantColumn, currentTenantId);
            return enrichedMap;
        }

        ObjectMapper mapper = objectMapper != null ? objectMapper : FALLBACK_OBJECT_MAPPER;
        JsonNode tree = mapper.valueToTree(document);
        if (!(tree instanceof ObjectNode objectNode)) {
            return document;
        }
        JsonNode tenantNode = objectNode.get(tenantColumn);
        if (tenantNode != null && !tenantNode.isNull()) {
            String existingStr = tenantNode.asText("").trim();
            if (StringUtils.hasText(existingStr)) {
                if (!currentTenantId.equals(existingStr)) {
                    throw new SearchException(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED,
                            "Cross-tenant write tampering blocked: current tenant [" + currentTenantId
                                    + "] cannot write document owned by [" + existingStr + "]");
                }
                return document;
            }
        }
        objectNode.put(tenantColumn, currentTenantId);
        return objectNode;
    }

    /**
     * 从反序列化后的 JSON 对象树中提取租户列文本值
     *
     * @param sourceNode   文档 _source 节点
     * @param tenantColumn 租户列名
     * @return 租户值；缺失或为 null/空白时返回 null
     */
    public static String extractTenantValue(ObjectNode sourceNode, String tenantColumn) {
        if (sourceNode == null || !StringUtils.hasText(tenantColumn)) {
            return null;
        }
        JsonNode tenantNode = sourceNode.get(tenantColumn);
        if (tenantNode == null || tenantNode.isNull()) {
            return null;
        }
        String value = tenantNode.asText("").trim();
        return StringUtils.hasText(value) ? value : null;
    }

    /**
     * 校验目标文档是否归属当前租户
     *
     * @param sourceNode      文档 _source 节点
     * @param tenantColumn    租户列名
     * @param currentTenantId 当前线程租户 ID
     * @return true 表示文档存在且租户归属一致
     */
    public static boolean isOwnedByTenant(ObjectNode sourceNode, String tenantColumn, String currentTenantId) {
        if (sourceNode == null || !StringUtils.hasText(currentTenantId)) {
            return false;
        }
        String docTenant = extractTenantValue(sourceNode, tenantColumn);
        return docTenant != null && currentTenantId.trim().equals(docTenant);
    }

    /**
     * 将原始 JSON 节点转换回目标实体类型（兼容 Map 与 POJO/Record）
     *
     * @param sourceNode   文档 JSON 节点
     * @param clazz        目标类型
     * @param objectMapper Jackson 序列化器
     * @param <T>          目标泛型
     * @return 反序列化实体对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T convertSourceNode(ObjectNode sourceNode, Class<T> clazz, ObjectMapper objectMapper) {
        if (sourceNode == null || clazz == null) {
            return null;
        }
        if (clazz == ObjectNode.class) {
            return (T) sourceNode;
        }
        ObjectMapper mapper = objectMapper != null ? objectMapper : FALLBACK_OBJECT_MAPPER;
        try {
            if (Map.class.isAssignableFrom(clazz)) {
                return mapper.convertValue(sourceNode, clazz);
            }
            return mapper.treeToValue(sourceNode, clazz);
        } catch (Exception e) {
            throw new SearchException(SearchResultCode.SEARCH_DOCUMENT_GET_ERROR,
                    "Failed to deserialize document source into [" + clazz.getSimpleName() + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 校验条件查询 DSL 中是否显式篡改了其他租户列的过滤条件（用于 STRICT 模式下的 reindex 等跨索引防御）
     *
     * @param query      待检查询 DSL（可为 null）
     * @param properties 搜索配置属性
     * @param mapper     Jackson 序列化器
     */
    public static void rejectCrossTenantQueryFilter(Query query,
                                                    AtengSearchProperties properties,
                                                    ObjectMapper mapper) {
        rejectCrossTenantQueryFilter(query, null, properties, mapper);
    }

    /**
     * 校验条件查询 DSL 中是否显式篡改了其他租户列的过滤条件（支持按实体类跳过 @IgnoreTenant）
     *
     * @param query       待检查询 DSL（可为 null）
     * @param entityClass 目标实体类（可为 null，标注 @IgnoreTenant 时跳过校验）
     * @param properties  搜索配置属性
     * @param mapper      Jackson 序列化器
     */
    public static void rejectCrossTenantQueryFilter(Query query,
                                                    Class<?> entityClass,
                                                    AtengSearchProperties properties,
                                                    ObjectMapper mapper) {
        if (query == null || !isStrictMode(properties)) {
            return;
        }
        String currentTenantId = resolveActiveTenantId(properties, entityClass);
        if (currentTenantId == null) {
            return;
        }
        String tenantColumn = resolveTenantColumn(properties);
        ObjectMapper effectiveMapper = mapper != null ? mapper : FALLBACK_OBJECT_MAPPER;
        try {
            StringBuilder sb = new StringBuilder();
            JsonpUtils.toString(query, new JacksonJsonpMapper(effectiveMapper), sb);
            JsonNode tree = effectiveMapper.readTree(sb.toString());
            inspectNodeForCrossTenantFilter(tree, tenantColumn, currentTenantId);
        } catch (SearchException se) {
            throw se;
        } catch (Exception e) {
            log.debug("Failed to pre-inspect query filter DSL for cross-tenant references; fallback filter remains enforced: {}",
                    e.getMessage(), e);
        }
    }

    private static void inspectNodeForCrossTenantFilter(JsonNode node, String tenantColumn, String currentTenantId) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (tenantColumn.equals(entry.getKey())) {
                    validateTenantFilterTarget(entry.getValue(), tenantColumn, currentTenantId);
                } else {
                    inspectNodeForCrossTenantFilter(entry.getValue(), tenantColumn, currentTenantId);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                inspectNodeForCrossTenantFilter(child, tenantColumn, currentTenantId);
            }
        }
    }

    private static void validateTenantFilterTarget(JsonNode target, String tenantColumn, String currentTenantId) {
        if (target == null || target.isNull()) {
            return;
        }
        if (target.isValueNode()) {
            String val = target.asText("").trim();
            if (StringUtils.hasText(val) && !currentTenantId.equals(val)) {
                throw new SearchException(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED,
                        "Cross-tenant query filter blocked for field [" + tenantColumn + "] and tenant [" + currentTenantId + "]");
            }
            return;
        }
        if (target.isArray()) {
            for (JsonNode item : target) {
                validateTenantFilterTarget(item, tenantColumn, currentTenantId);
            }
            return;
        }
        if (target.isObject()) {
            JsonNode valueNode = target.get("value");
            if (valueNode != null) {
                validateTenantFilterTarget(valueNode, tenantColumn, currentTenantId);
            }
            JsonNode valuesNode = target.get("values");
            if (valuesNode != null) {
                validateTenantFilterTarget(valuesNode, tenantColumn, currentTenantId);
            }
            JsonNode queryNode = target.get("query");
            if (queryNode != null) {
                validateTenantFilterTarget(queryNode, tenantColumn, currentTenantId);
            }
        }
    }
}
