package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实体阶梯式继承链体系单元测试
 * 覆盖 BaseGenericEntity、BaseIdEntity、BaseTimeEntity、BaseEntity 与 BaseFullEntity 全层级
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("实体阶梯式继承链体系测试")
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
    static class TestFullEntity extends BaseFullEntity {
        private String username;
    }

    @Test
    @DisplayName("1. 顶层泛型抽象 BaseGenericEntity 支持非 Long 类型的自定义主键与多态访问")
    void shouldSupportGenericCustomPrimaryKey() {
        CustomStringEntity entity = new CustomStringEntity();
        entity.setId("uuid-string-key-001");
        entity.setName("测试自定义主键");

        assertEquals("uuid-string-key-001", entity.getId());
        assertEquals("测试自定义主键", entity.getName());

        // 多态指向顶层泛型基类
        BaseGenericEntity<String> generic = entity;
        assertEquals("uuid-string-key-001", generic.getId());
        generic.setId("new-uuid-002");
        assertEquals("new-uuid-002", entity.getId());
    }

    @Test
    @DisplayName("2. 极简主键基类 BaseIdEntity 仅声明雪花长整型 id 与 @TableId 注解")
    void shouldDefineBaseIdEntityCorrectly() throws NoSuchFieldException {
        Field idField = BaseIdEntity.class.getDeclaredField("id");
        TableId tableId = idField.getAnnotation(TableId.class);
        assertNotNull(tableId);
        assertEquals(IdType.ASSIGN_ID, tableId.type());
        assertEquals(Long.class, idField.getType());

        // 验证仅含主键，不包含时间、操作人、版本号或逻辑删除字段
        assertThrows(NoSuchFieldException.class, () -> BaseIdEntity.class.getDeclaredField("createTime"));
        assertThrows(NoSuchFieldException.class, () -> BaseIdEntity.class.getDeclaredField("updateTime"));
        assertThrows(NoSuchFieldException.class, () -> BaseIdEntity.class.getDeclaredField("createBy"));
        assertThrows(NoSuchFieldException.class, () -> BaseIdEntity.class.getDeclaredField("updateBy"));
        assertThrows(NoSuchFieldException.class, () -> BaseIdEntity.class.getDeclaredField("version"));
        assertThrows(NoSuchFieldException.class, () -> BaseIdEntity.class.getDeclaredField("deleted"));

        TestIdOnlyEntity entity = new TestIdOnlyEntity();
        entity.setId(1001L);
        entity.setRemark("流水记录");
        assertEquals(1001L, entity.getId());
        assertEquals("流水记录", entity.getRemark());
    }

    @Test
    @DisplayName("3. 时间审计基类 BaseTimeEntity 声明创建时间与更新时间自动填充")
    void shouldDefineBaseTimeEntityCorrectly() throws NoSuchFieldException {
        assertTrue(BaseIdEntity.class.isAssignableFrom(BaseTimeEntity.class));

        Field createTimeField = BaseTimeEntity.class.getDeclaredField("createTime");
        TableField createTimeFieldAnnotation = createTimeField.getAnnotation(TableField.class);
        assertNotNull(createTimeFieldAnnotation);
        assertEquals(FieldFill.INSERT, createTimeFieldAnnotation.fill());

        Field updateTimeField = BaseTimeEntity.class.getDeclaredField("updateTime");
        TableField updateTimeFieldAnnotation = updateTimeField.getAnnotation(TableField.class);
        assertNotNull(updateTimeFieldAnnotation);
        assertEquals(FieldFill.INSERT_UPDATE, updateTimeFieldAnnotation.fill());

        // 不含操作人、版本号与逻辑删除
        assertThrows(NoSuchFieldException.class, () -> BaseTimeEntity.class.getDeclaredField("createBy"));
        assertThrows(NoSuchFieldException.class, () -> BaseTimeEntity.class.getDeclaredField("version"));
        assertThrows(NoSuchFieldException.class, () -> BaseTimeEntity.class.getDeclaredField("deleted"));

        TestTimeEntity entity = new TestTimeEntity();
        entity.setId(2001L);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setDictCode("STATUS_ACTIVE");

        assertEquals(2001L, entity.getId());
        assertEquals(now, entity.getCreateTime());
        assertEquals(now, entity.getUpdateTime());
        assertEquals("STATUS_ACTIVE", entity.getDictCode());
    }

    @Test
    @DisplayName("4. 标准业务基类 BaseEntity 声明操作人审计，解耦 version 与 deleted")
    void shouldDefineBaseEntityCorrectly() throws NoSuchFieldException {
        assertTrue(BaseTimeEntity.class.isAssignableFrom(BaseEntity.class));

        Field createByField = BaseEntity.class.getDeclaredField("createBy");
        TableField createByTableField = createByField.getAnnotation(TableField.class);
        assertNotNull(createByTableField);
        assertEquals(FieldFill.INSERT, createByTableField.fill());
        assertEquals(String.class, createByField.getType());

        Field updateByField = BaseEntity.class.getDeclaredField("updateBy");
        TableField updateByTableField = updateByField.getAnnotation(TableField.class);
        assertNotNull(updateByTableField);
        assertEquals(FieldFill.INSERT_UPDATE, updateByTableField.fill());
        assertEquals(String.class, updateByField.getType());

        // 验证 BaseEntity 已彻底切除 version 与 deleted
        assertThrows(NoSuchFieldException.class, () -> BaseEntity.class.getDeclaredField("version"));
        assertThrows(NoSuchFieldException.class, () -> BaseEntity.class.getDeclaredField("deleted"));

        TestBusinessEntity entity = new TestBusinessEntity();
        entity.setId(3001L);
        entity.setCreateBy("creator");
        entity.setUpdateBy("modifier");
        entity.setTitle("文章标题");

        assertEquals(3001L, entity.getId());
        assertEquals("creator", entity.getCreateBy());
        assertEquals("modifier", entity.getUpdateBy());
        assertEquals("文章标题", entity.getTitle());
    }

    @Test
    @DisplayName("5. 全要素基类 BaseFullEntity 继承 BaseEntity 并挂载 version 乐观锁与 deleted 逻辑删除")
    void shouldDefineBaseFullEntityCorrectly() throws NoSuchFieldException {
        assertTrue(BaseEntity.class.isAssignableFrom(BaseFullEntity.class));

        // version 字段标配 @Version
        Field versionField = BaseFullEntity.class.getDeclaredField("version");
        assertNotNull(versionField.getAnnotation(Version.class));
        assertEquals(Integer.class, versionField.getType());

        // deleted 字段标配 @TableLogic 与 @JsonIgnore
        Field deletedField = BaseFullEntity.class.getDeclaredField("deleted");
        assertNotNull(deletedField.getAnnotation(TableLogic.class));
        assertNotNull(deletedField.getAnnotation(JsonIgnore.class));
        assertEquals(Integer.class, deletedField.getType());

        TestFullEntity entity = new TestFullEntity();
        entity.setId(4001L);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setCreateBy("full-creator");
        entity.setUpdateBy("full-modifier");
        entity.setVersion(1);
        entity.setDeleted(0);
        entity.setUsername("admin");

        assertEquals(4001L, entity.getId());
        assertEquals(now, entity.getCreateTime());
        assertEquals(now, entity.getUpdateTime());
        assertEquals("full-creator", entity.getCreateBy());
        assertEquals("full-modifier", entity.getUpdateBy());
        assertEquals(1, entity.getVersion());
        assertEquals(0, entity.getDeleted());
        assertEquals("admin", entity.getUsername());
    }
}
