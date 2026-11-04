package io.github.atengk.boot.milvus.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.milvus.exception.MilvusException;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Milvus 实体、字典与 Gson JsonObject 之间的双向自适应转换工具
 *
 * @author Ateng
 * @since 2026-11-04
 */
public final class MilvusEntityConverter {

    private MilvusEntityConverter() {
    }

    /**
     * 将键值字典 Map 转换为 MilvusClientV2 接受的 JsonObject
     *
     * @param map 原始字典
     * @return Gson JsonObject
     */
    public static JsonObject toJsonObject(Map<String, Object> map) {
        if (map == null) {
            return new JsonObject();
        }
        try {
            String json = JsonUtil.toJsonString(map);
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new MilvusException("将字典转换为 JsonObject 失败", e);
        }
    }

    /**
     * 批量将字典 Map 转换为 JsonObject 列表
     *
     * @param mapList 字典列表
     * @return JsonObject 列表
     */
    public static List<JsonObject> toJsonObjectList(List<Map<String, Object>> mapList) {
        if (mapList == null || mapList.isEmpty()) {
            return Collections.emptyList();
        }
        List<JsonObject> list = new ArrayList<>(mapList.size());
        for (Map<String, Object> map : mapList) {
            list.add(toJsonObject(map));
        }
        return list;
    }

    /**
     * 将业务实体 POJO 转换为 JsonObject
     *
     * @param entity 业务实体
     * @return Gson JsonObject
     */
    public static JsonObject entityToJsonObject(Object entity) {
        Assert.notNull(entity, "entity must not be null");
        try {
            String json = JsonUtil.toJsonString(entity);
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new MilvusException("将实体转换为 JsonObject 失败: " + entity.getClass().getName(), e);
        }
    }

    /**
     * 批量将业务实体列表转换为 JsonObject 列表
     *
     * @param entities 业务实体列表
     * @return JsonObject 列表
     */
    public static List<JsonObject> entitiesToJsonObjectList(List<?> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        List<JsonObject> list = new ArrayList<>(entities.size());
        for (Object entity : entities) {
            list.add(entityToJsonObject(entity));
        }
        return list;
    }

    /**
     * 将 Milvus 返回的原始字典 Map 转换为业务领域实体
     *
     * @param rawData     原始属性字典
     * @param entityClass 目标实体类型
     * @param <T>         实体泛型
     * @return 反序列化后的实体对象
     */
    public static <T> T mapToEntity(Map<String, Object> rawData, Class<T> entityClass) {
        if (rawData == null || rawData.isEmpty()) {
            return null;
        }
        try {
            return JsonUtil.convertValue(rawData, entityClass);
        } catch (Exception e) {
            throw new MilvusException("将 Milvus 数据行反序列化为实体 [" + entityClass.getName() + "] 失败", e);
        }
    }

    /**
     * 批量将原始字典 Map 列表转换为业务领域实体列表
     *
     * @param rawDataList 原始字典列表
     * @param entityClass 目标实体类型
     * @param <T>         实体泛型
     * @return 实体列表
     */
    public static <T> List<T> mapListToEntities(List<Map<String, Object>> rawDataList, Class<T> entityClass) {
        if (rawDataList == null || rawDataList.isEmpty()) {
            return Collections.emptyList();
        }
        List<T> list = new ArrayList<>(rawDataList.size());
        for (Map<String, Object> map : rawDataList) {
            T entity = mapToEntity(map, entityClass);
            if (entity != null) {
                list.add(entity);
            }
        }
        return list;
    }
}
