package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.atengk.boot.core.util.JsonUtil;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 持久层正交能力接口与双轨实体继承阶梯单元测试
 * 覆盖 6 个正交契约接口、BaseGeneric* 泛型阶梯与 BaseIdEntity ~ BaseFullEntity 64 位雪花阶梯
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("持久层正交能力接口与双轨实体阶梯体系测试")
class BaseEntityTest {

    @Getter
    @Setter
    static class CustomStringEntity extends BaseGenericEntity<String> {
        @TableId(type = IdType.INPUT)
        private String id;
        private String name;
    }

    @Getter
    @Setter
    static class CustomGenericTimeEntity extends BaseGenericTimeEntity<String> {
        @TableId(type = IdType.ASSIGN_UUID)
        private String id;
        private String tag;
    }

    @Getter
    @Setter
    static class CustomGenericAuditEntity extends BaseGenericAuditEntity<String> {
        @TableId(type = IdType.ASSIGN_UUID)
        private String id;
        private String title;
    }

    @Getter
    @Setter
    static class CustomGenericLogicEntity extends BaseGenericLogicEntity<String> implements TenantAware<Long> {
        @TableId(type = IdType.ASSIGN_UUID)
        private String id;
        private Long tenantId;
        private String code;
    }

    @Getter
    @Setter
    static class CustomGenericFullEntity extends BaseGenericFullEntity<String> {
        @TableId(type = IdType.ASSIGN_UUID)
        private String id;
        private String orderNo;
    }

    @Getter
    @Setter
    static class TestIdOnlyEntity extends BaseIdEntity {
        private String remark;
    }

    @Getter
    @Setter
    static class TestTimeEntity extends BaseTimeEntity {
        private String dictCode;
    }

    @Getter
    @Setter
    static class TestBusinessEntity extends BaseEntity {
        private String title;
    }

    @Getter
    @Setter
    static class TestLogicEntity extends BaseLogicEntity {
        private String deptName;
    }

    @Getter
    @Setter
    static class TestFullEntity extends BaseFullEntity {
        private String username;
    }

    @Test
    @DisplayName("1. 正交能力接口契约：Auditable 继承 TimeAuditable，TenantAware 支持 Long 与 String")
    void shouldDefineOrthogonalContractInterfaces() {
        assertTrue(TimeAuditable.class.isAssignableFrom(Auditable.class));

        CustomGenericLogicEntity longTenantEntity = new CustomGenericLogicEntity();
        longTenantEntity.setTenantId(10001L);
        TenantAware<Long> longTenantAware = longTenantEntity;
        assertEquals(10001L, longTenantAware.getTenantId());

        Identifiable<String> identifiable = longTenantEntity;
        identifiable.setId("str-pk-001");
        assertEquals("str-pk-001", longTenantEntity.getId());

        LogicDeletable<String> logicDeletable = longTenantEntity;
        logicDeletable.setDeleted("0");
        assertEquals("0", longTenantEntity.getDeleted());
    }

    @Test
    @DisplayName("2. 泛型内核阶梯：BaseGenericEntity ~ BaseGenericFullEntity 完整继承链与注解校验")
    void shouldDefineGenericEntityLadderCorrectly() throws NoSuchFieldException {
        assertTrue(Identifiable.class.isAssignableFrom(BaseGenericEntity.class));
        assertTrue(BaseGenericEntity.class.isAssignableFrom(BaseGenericTimeEntity.class));
        assertTrue(TimeAuditable.class.isAssignableFrom(BaseGenericTimeEntity.class));
        assertTrue(BaseGenericTimeEntity.class.isAssignableFrom(BaseGenericAuditEntity.class));
        assertTrue(Auditable.class.isAssignableFrom(BaseGenericAuditEntity.class));
        assertTrue(BaseGenericAuditEntity.class.isAssignableFrom(BaseGenericLogicEntity.class));
        assertTrue(LogicDeletable.class.isAssignableFrom(BaseGenericLogicEntity.class));
        assertTrue(BaseGenericLogicEntity.class.isAssignableFrom(BaseGenericFullEntity.class));
        assertTrue(Versioned.class.isAssignableFrom(BaseGenericFullEntity.class));

        // BaseGenericEntity 不固定声明 id 字段，由子类决定 @TableId 策略
        assertThrows(NoSuchFieldException.class, () -> BaseGenericEntity.class.getDeclaredField("id"));
        // BaseGenericLogicEntity 不强绑 version
        assertThrows(NoSuchFieldException.class, () -> BaseGenericLogicEntity.class.getDeclaredField("version"));

        Field deletedField = BaseGenericLogicEntity.class.getDeclaredField("deleted");
        TableLogic tableLogic = deletedField.getAnnotation(TableLogic.class);
        assertNotNull(tableLogic);
        assertEquals("0", tableLogic.value());
        assertEquals("id", tableLogic.delval());
        assertNotNull(deletedField.getAnnotation(JsonIgnore.class));

        Field versionField = BaseGenericFullEntity.class.getDeclaredField("version");
        assertNotNull(versionField.getAnnotation(Version.class));
        assertEquals(Integer.class, versionField.getType());

        CustomGenericFullEntity entity = new CustomGenericFullEntity();
        entity.setId("uuid-001");
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        entity.setCreateBy("admin");
        entity.setUpdateBy("admin");
        entity.setDeleted("0");
        entity.setVersion(0);
        entity.setOrderNo("ORD-1");
        assertEquals("uuid-001", entity.getId());
        assertEquals("0", entity.getDeleted());
        assertEquals(0, entity.getVersion());
    }

    @Test
    @DisplayName("3. 雪花零泛型阶梯（BaseIdEntity -> BaseTimeEntity -> BaseEntity）契约验证")
    void shouldDefineSnowflakeAuditLadderCorrectly() throws NoSuchFieldException {
        assertTrue(BaseGenericEntity.class.isAssignableFrom(BaseIdEntity.class));
        assertTrue(Identifiable.class.isAssignableFrom(BaseIdEntity.class));
        assertTrue(BaseIdEntity.class.isAssignableFrom(BaseTimeEntity.class));
        assertTrue(TimeAuditable.class.isAssignableFrom(BaseTimeEntity.class));
        assertTrue(BaseTimeEntity.class.isAssignableFrom(BaseEntity.class));
        assertTrue(Auditable.class.isAssignableFrom(BaseEntity.class));

        Field idField = BaseIdEntity.class.getDeclaredField("id");
        TableId tableId = idField.getAnnotation(TableId.class);
        assertNotNull(tableId);
        assertEquals(IdType.ASSIGN_ID, tableId.type());
        assertEquals(Long.class, idField.getType());

        Field createTimeField = BaseTimeEntity.class.getDeclaredField("createTime");
        assertEquals(FieldFill.INSERT, createTimeField.getAnnotation(TableField.class).fill());
        Field updateTimeField = BaseTimeEntity.class.getDeclaredField("updateTime");
        assertEquals(FieldFill.INSERT_UPDATE, updateTimeField.getAnnotation(TableField.class).fill());

        Field createByField = BaseEntity.class.getDeclaredField("createBy");
        assertEquals(FieldFill.INSERT, createByField.getAnnotation(TableField.class).fill());
        Field updateByField = BaseEntity.class.getDeclaredField("updateBy");
        assertEquals(FieldFill.INSERT_UPDATE, updateByField.getAnnotation(TableField.class).fill());

        assertThrows(NoSuchFieldException.class, () -> BaseEntity.class.getDeclaredField("version"));
        assertThrows(NoSuchFieldException.class, () -> BaseEntity.class.getDeclaredField("deleted"));
    }

    @Test
    @DisplayName("4. 常规软删基类 BaseLogicEntity 继承 BaseEntity，采用 Long 占位软删且不含 @Version")
    void shouldDefineBaseLogicEntityWithoutVersion() throws NoSuchFieldException {
        assertTrue(BaseEntity.class.isAssignableFrom(BaseLogicEntity.class));
        assertTrue(LogicDeletable.class.isAssignableFrom(BaseLogicEntity.class));

        // 明确不绑定乐观锁
        assertThrows(NoSuchFieldException.class, () -> BaseLogicEntity.class.getDeclaredField("version"));

        Field deletedField = BaseLogicEntity.class.getDeclaredField("deleted");
        assertEquals(Long.class, deletedField.getType());
        TableLogic tableLogic = deletedField.getAnnotation(TableLogic.class);
        assertNotNull(tableLogic);
        assertEquals("0", tableLogic.value());
        assertEquals("id", tableLogic.delval());
        assertNotNull(deletedField.getAnnotation(JsonIgnore.class));

        TestLogicEntity entity = new TestLogicEntity();
        entity.setId(4001L);
        entity.setDeleted(0L);
        entity.setDeptName("研发部");
        String json = JsonUtil.toJsonString(entity);
        assertThat(json).contains("deptName").doesNotContain("deleted");
    }

    @Test
    @DisplayName("5. 全要素基类 BaseFullEntity 继承 BaseLogicEntity 并追加 @Version Integer version")
    void shouldDefineBaseFullEntityWithVersionAndPlaceholderDeleted() throws NoSuchFieldException {
        assertTrue(BaseLogicEntity.class.isAssignableFrom(BaseFullEntity.class));
        assertTrue(Versioned.class.isAssignableFrom(BaseFullEntity.class));

        // deleted 继承自 BaseLogicEntity
        assertThrows(NoSuchFieldException.class, () -> BaseFullEntity.class.getDeclaredField("deleted"));

        Field versionField = BaseFullEntity.class.getDeclaredField("version");
        assertNotNull(versionField.getAnnotation(Version.class));
        assertEquals(Integer.class, versionField.getType());

        TestFullEntity entity = new TestFullEntity();
        entity.setId(5001L);
        entity.setDeleted(0L);
        entity.setVersion(0);
        entity.setUsername("admin");

        assertEquals(0L, entity.getDeleted());
        assertEquals(0, entity.getVersion());
        String json = JsonUtil.toJsonString(entity);
        assertThat(json).contains("username").doesNotContain("deleted");
    }
}
