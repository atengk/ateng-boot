package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实体抽象基类 BaseEntity 测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("实体抽象基类 BaseEntity 测试")
class BaseEntityTest {

    static class TestUserEntity extends BaseEntity {
        private String name;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Test
    @DisplayName("验证 BaseEntity 字段及其注解元数据完整性")
    void shouldDefineBaseEntityFieldsAndAnnotationsCorrectly() throws NoSuchFieldException {
        // id 字段
        Field idField = BaseEntity.class.getDeclaredField("id");
        TableId tableId = idField.getAnnotation(TableId.class);
        assertNotNull(tableId);
        assertEquals(IdType.ASSIGN_ID, tableId.type());

        // createTime 字段
        Field createTimeField = BaseEntity.class.getDeclaredField("createTime");
        TableField createTimeTableField = createTimeField.getAnnotation(TableField.class);
        assertNotNull(createTimeTableField);
        assertEquals(FieldFill.INSERT, createTimeTableField.fill());

        // updateTime 字段
        Field updateTimeField = BaseEntity.class.getDeclaredField("updateTime");
        TableField updateTimeTableField = updateTimeField.getAnnotation(TableField.class);
        assertNotNull(updateTimeTableField);
        assertEquals(FieldFill.INSERT_UPDATE, updateTimeTableField.fill());

        // createBy 字段
        Field createByField = BaseEntity.class.getDeclaredField("createBy");
        TableField createByTableField = createByField.getAnnotation(TableField.class);
        assertNotNull(createByTableField);
        assertEquals(FieldFill.INSERT, createByTableField.fill());
        assertEquals(String.class, createByField.getType());

        // updateBy 字段
        Field updateByField = BaseEntity.class.getDeclaredField("updateBy");
        TableField updateByTableField = updateByField.getAnnotation(TableField.class);
        assertNotNull(updateByTableField);
        assertEquals(FieldFill.INSERT_UPDATE, updateByTableField.fill());
        assertEquals(String.class, updateByField.getType());

        // version 字段标配 @Version
        Field versionField = BaseEntity.class.getDeclaredField("version");
        assertNotNull(versionField.getAnnotation(com.baomidou.mybatisplus.annotation.Version.class));
        assertEquals(Integer.class, versionField.getType());

        // deleted 字段标配 @TableLogic 与 @JsonIgnore
        Field deletedField = BaseEntity.class.getDeclaredField("deleted");
        assertNotNull(deletedField.getAnnotation(TableLogic.class));
        assertNotNull(deletedField.getAnnotation(JsonIgnore.class));
        assertEquals(Integer.class, deletedField.getType());
    }

    @Test
    @DisplayName("子类继承并访问审计字段属性")
    void shouldSupportGetterAndSetter() {
        TestUserEntity entity = new TestUserEntity();
        entity.setId(1001L);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setCreateBy("ateng-admin");
        entity.setUpdateBy("ateng-admin");
        entity.setVersion(1);
        entity.setDeleted(0);
        entity.setName("张三");

        assertEquals(1001L, entity.getId());
        assertEquals(now, entity.getCreateTime());
        assertEquals(now, entity.getUpdateTime());
        assertEquals("ateng-admin", entity.getCreateBy());
        assertEquals("ateng-admin", entity.getUpdateBy());
        assertEquals(1, entity.getVersion());
        assertEquals(0, entity.getDeleted());
        assertEquals("张三", entity.getName());
    }
}
