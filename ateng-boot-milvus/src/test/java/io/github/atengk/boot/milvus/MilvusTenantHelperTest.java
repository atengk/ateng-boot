package io.github.atengk.boot.milvus;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.milvus.annotation.MilvusCollection;
import io.github.atengk.boot.milvus.annotation.MilvusId;
import io.github.atengk.boot.milvus.annotation.MilvusPartitionKey;
import io.github.atengk.boot.milvus.annotation.MilvusVector;
import io.github.atengk.boot.milvus.model.MilvusEntityMetadata;
import io.github.atengk.boot.milvus.tenant.MilvusTenantHelper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MilvusTenantHelper 多租户安全隔离辅助器单测
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusTenantHelperTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @MilvusCollection(name = "tenant_test_col")
    static class StringTenantDoc {
        @MilvusId
        private Long id;

        @MilvusPartitionKey
        private String tenantId;

        @MilvusVector(dimension = 4)
        private List<Float> vector;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @MilvusCollection(name = "long_tenant_test_col")
    static class LongTenantDoc {
        @MilvusId
        private Long id;

        @MilvusPartitionKey
        private Long tenantId;

        @MilvusVector(dimension = 4)
        private List<Float> vector;
    }

    @Test
    @DisplayName("weaveTenantFilter - 无租户上下文时原样返回表达式")
    void testWeaveTenantFilterWithoutTenant() {
        String filter = "age > 18";
        String result = MilvusTenantHelper.weaveTenantFilter(filter, null, false);
        assertThat(result).isEqualTo(filter);

        assertThat(MilvusTenantHelper.weaveTenantFilter(null, null, false)).isNull();
    }

    @Test
    @DisplayName("weaveTenantFilter - 存在租户上下文时安全编织防御性括号表达式")
    void testWeaveTenantFilterWithTenant() {
        TenantContextHolder.setTenantId("tenant_001");

        // 1. 无原始表达式
        String emptyExpr = MilvusTenantHelper.weaveTenantFilter("", null, false);
        assertThat(emptyExpr).isEqualTo("tenant_id == 'tenant_001'");

        // 2. 有原始表达式
        String filter = "status == 1 or category in ['news', 'tech']";
        String woven = MilvusTenantHelper.weaveTenantFilter(filter, null, false);
        assertThat(woven).isEqualTo("(status == 1 or category in ['news', 'tech']) and (tenant_id == 'tenant_001')");

        // 3. 自定义租户字段名
        String customField = MilvusTenantHelper.weaveTenantFilter("score >= 90", "org_id", false);
        assertThat(customField).isEqualTo("(score >= 90) and (org_id == 'tenant_001')");
    }

    @Test
    @DisplayName("weaveTenantFilter - ignoreTenant 显式或隐式穿透")
    void testWeaveTenantFilterIgnore() {
        TenantContextHolder.setTenantId("tenant_001");

        // 显式忽略
        String filter = "category == 'ai'";
        String res1 = MilvusTenantHelper.weaveTenantFilter(filter, null, true);
        assertThat(res1).isEqualTo(filter);

        // 上下文设置忽略
        TenantContextHolder.setIgnoreTenant(true);
        String res2 = MilvusTenantHelper.weaveTenantFilter(filter, null, false);
        assertThat(res2).isEqualTo(filter);
    }

    @Test
    @DisplayName("fillRecordTenantField - 字典数据租户字段自动补全")
    void testFillRecordTenantField() {
        TenantContextHolder.setTenantId("tenant_002");

        Map<String, Object> record = new HashMap<>();
        record.put("title", "测试文档");

        MilvusTenantHelper.fillRecordTenantField(record);
        assertThat(record).containsEntry("tenant_id", "tenant_002");

        // 已存在租户字段则不覆盖
        Map<String, Object> existing = new HashMap<>();
        existing.put("tenant_id", "tenant_custom");
        MilvusTenantHelper.fillRecordTenantField(existing);
        assertThat(existing).containsEntry("tenant_id", "tenant_custom");
    }

    @Test
    @DisplayName("fillEntityTenantField - 实体对象租户字段自动补全 (String 与 Long 双模)")
    void testFillEntityTenantField() {
        TenantContextHolder.setTenantId("10086");

        // 1. String 类型租户字段
        StringTenantDoc stringDoc = StringTenantDoc.builder().id(1L).build();
        MilvusEntityMetadata stringMeta = MilvusEntityMetadata.of(StringTenantDoc.class);
        MilvusTenantHelper.fillEntityTenantField(stringDoc, stringMeta);
        assertThat(stringDoc.getTenantId()).isEqualTo("10086");

        // 2. Long 类型租户字段
        LongTenantDoc longDoc = LongTenantDoc.builder().id(2L).build();
        MilvusEntityMetadata longMeta = MilvusEntityMetadata.of(LongTenantDoc.class);
        MilvusTenantHelper.fillEntityTenantField(longDoc, longMeta);
        assertThat(longDoc.getTenantId()).isEqualTo(10086L);
    }
}
