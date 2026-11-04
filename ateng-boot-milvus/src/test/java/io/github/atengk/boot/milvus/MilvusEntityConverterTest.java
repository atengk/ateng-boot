package io.github.atengk.boot.milvus;

import com.google.gson.JsonObject;
import io.github.atengk.boot.milvus.annotation.MilvusCollection;
import io.github.atengk.boot.milvus.annotation.MilvusId;
import io.github.atengk.boot.milvus.annotation.MilvusVector;
import io.github.atengk.boot.milvus.model.MilvusEntityConverter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MilvusEntityConverter 实体转换器单测
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusEntityConverterTest {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @MilvusCollection(name = "converter_test_col")
    static class ConverterDemoDoc {
        @MilvusId
        private Long id;
        private String title;
        @MilvusVector(dimension = 3)
        private List<Float> vector;
    }

    @Test
    @DisplayName("toJsonObject & toJsonObjectList - Map 字典转 Gson JsonObject")
    void testMapToJsonObject() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", 100L);
        map.put("title", "测试标题");
        map.put("vector", List.of(0.1f, 0.2f, 0.3f));

        JsonObject jsonObject = MilvusEntityConverter.toJsonObject(map);
        assertThat(jsonObject).isNotNull();
        assertThat(jsonObject.get("id").getAsLong()).isEqualTo(100L);
        assertThat(jsonObject.get("title").getAsString()).isEqualTo("测试标题");

        List<JsonObject> list = MilvusEntityConverter.toJsonObjectList(List.of(map));
        assertThat(list).hasSize(1);
    }

    @Test
    @DisplayName("entityToJsonObject & entitiesToJsonObjectList - 业务 POJO 转 Gson JsonObject")
    void testEntityToJsonObject() {
        ConverterDemoDoc doc = ConverterDemoDoc.builder()
                .id(200L)
                .title("文档POJO")
                .vector(List.of(0.5f, 0.6f, 0.7f))
                .build();

        JsonObject jsonObject = MilvusEntityConverter.entityToJsonObject(doc);
        assertThat(jsonObject).isNotNull();
        assertThat(jsonObject.get("id").getAsLong()).isEqualTo(200L);
        assertThat(jsonObject.get("title").getAsString()).isEqualTo("文档POJO");

        List<JsonObject> list = MilvusEntityConverter.entitiesToJsonObjectList(List.of(doc));
        assertThat(list).hasSize(1);
    }

    @Test
    @DisplayName("mapToEntity & mapListToEntities - Map 字典反序列化为业务 POJO")
    void testMapToEntity() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", 300L);
        map.put("title", "反序列化测试");
        map.put("vector", List.of(0.8f, 0.9f, 1.0f));

        ConverterDemoDoc doc = MilvusEntityConverter.mapToEntity(map, ConverterDemoDoc.class);
        assertThat(doc).isNotNull();
        assertThat(doc.getId()).isEqualTo(300L);
        assertThat(doc.getTitle()).isEqualTo("反序列化测试");
        assertThat(doc.getVector()).containsExactly(0.8f, 0.9f, 1.0f);

        List<ConverterDemoDoc> list = MilvusEntityConverter.mapListToEntities(List.of(map), ConverterDemoDoc.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getId()).isEqualTo(300L);
    }
}
