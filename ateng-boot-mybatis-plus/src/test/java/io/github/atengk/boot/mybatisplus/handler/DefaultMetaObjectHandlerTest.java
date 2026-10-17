package io.github.atengk.boot.mybatisplus.handler;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.mybatisplus.entity.BaseEntity;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计元对象填充器 DefaultMetaObjectHandler 测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("审计元对象填充器 DefaultMetaObjectHandler 测试")
class DefaultMetaObjectHandlerTest {

    static class OrderEntity extends BaseEntity {
        private String orderNo;

        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    }

    static class TenantOrderEntity extends BaseEntity {
        private String orderNo;
        private String tenantId;

        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    }

    static class LongTenantOrderEntity extends BaseEntity {
        private Long tenantId;

        public Long getTenantId() { return tenantId; }
        public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    }

    static class IdOnlyEntity extends io.github.atengk.boot.mybatisplus.entity.BaseIdEntity {
        private String content;

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    static class TimeAuditEntity extends io.github.atengk.boot.mybatisplus.entity.BaseTimeEntity {
        private String tag;

        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }
    }

    @BeforeEach
    @AfterEach
    void cleanUp() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }


    @Test
    @DisplayName("插入填充：当字段为 null 时从 SPI 自动填充时间与当前操作人")
    void shouldFillAuditFieldsOnInsertWhenNull() {
        CurrentUserProvider provider = () -> "user-spi-100";
        DefaultMetaObjectHandler handler = new DefaultMetaObjectHandler(provider, "system");

        OrderEntity order = new OrderEntity();
        order.setOrderNo("ORD-001");
        MetaObject metaObject = SystemMetaObject.forObject(order);

        handler.insertFill(metaObject);

        assertNotNull(order.getCreateTime());
        assertNotNull(order.getUpdateTime());
        assertEquals("user-spi-100", order.getCreateBy());
        assertEquals("user-spi-100", order.getUpdateBy());
    }

    @Test
    @DisplayName("插入填充防御性策略：当开发者已显式赋值时保留显式值不覆盖")
    void shouldPreserveExplicitValuesOnInsert() {
        DefaultMetaObjectHandler handler = new DefaultMetaObjectHandler(UserContextHolder::get, "system");

        OrderEntity order = new OrderEntity();
        order.setOrderNo("ORD-002");
        LocalDateTime explicitTime = LocalDateTime.of(2020, 1, 1, 0, 0, 0);
        order.setCreateTime(explicitTime);
        order.setCreateBy("historical-operator");

        MetaObject metaObject = SystemMetaObject.forObject(order);
        handler.insertFill(metaObject);

        // 显式值被完整保留
        assertEquals(explicitTime, order.getCreateTime());
        assertEquals("historical-operator", order.getCreateBy());

        // 原为 null 的字段仍会被自动填充
        assertNotNull(order.getUpdateTime());
        assertEquals("system", order.getUpdateBy());
    }

    @Test
    @DisplayName("当 SPI 返回 null 或空白时自动回退至 UserContextHolder 获取当前用户")
    void shouldFallbackToUserContextHolderWhenSpiReturnsNull() {
        CurrentUserProvider nullProvider = () -> null;
        UserContextHolder.set("context-user-888");
        DefaultMetaObjectHandler handler = new DefaultMetaObjectHandler(nullProvider, "SYSTEM");

        OrderEntity order = new OrderEntity();
        MetaObject metaObject = SystemMetaObject.forObject(order);

        handler.insertFill(metaObject);

        assertEquals("context-user-888", order.getCreateBy());
        assertEquals("context-user-888", order.getUpdateBy());
    }

    @Test
    @DisplayName("当 SPI 与 UserContextHolder 均无值时使用配置的默认兜底操作人 SYSTEM")
    void shouldUseDefaultOperatorWhenUserIsNull() {
        DefaultMetaObjectHandler handler = new DefaultMetaObjectHandler();

        OrderEntity order = new OrderEntity();
        MetaObject metaObject = SystemMetaObject.forObject(order);

        handler.insertFill(metaObject);

        assertEquals("SYSTEM", order.getCreateBy());
        assertEquals("SYSTEM", order.getUpdateBy());
    }

    @Test
    @DisplayName("更新填充：刷新 updateTime 与 updateBy")
    void shouldFillAuditFieldsOnUpdate() {
        UserContextHolder.set("operator-editor");
        DefaultMetaObjectHandler handler = new DefaultMetaObjectHandler(UserContextHolder::get, "system");

        OrderEntity order = new OrderEntity();
        MetaObject metaObject = SystemMetaObject.forObject(order);

        handler.updateFill(metaObject);

        assertNotNull(order.getUpdateTime());
        assertEquals("operator-editor", order.getUpdateBy());
        assertNull(order.getCreateTime());
        assertNull(order.getCreateBy());
    }

    @Test
    @DisplayName("更新填充：即使实体已包含旧的 updateTime 与 updateBy，更新时依然强制刷新为当前值")
    void shouldOverwriteExistingUpdateTimeOnUpdate() {
        UserContextHolder.set("new-modifier");
        DefaultMetaObjectHandler handler = new DefaultMetaObjectHandler();

        OrderEntity order = new OrderEntity();
        LocalDateTime oldTime = LocalDateTime.of(2019, 1, 1, 0, 0, 0);
        order.setUpdateTime(oldTime);
        order.setUpdateBy("old-modifier");
        MetaObject metaObject = SystemMetaObject.forObject(order);

        handler.updateFill(metaObject);

        assertNotEquals(oldTime, order.getUpdateTime());
        assertEquals("new-modifier", order.getUpdateBy());
    }

    @Test
    @DisplayName("防御性兼容：对仅继承 BaseIdEntity 的实体执行插入与更新填充，静默安全跳过且不报错")
    void shouldSafelyHandleBaseIdEntityWithoutAuditFields() {
        DefaultMetaObjectHandler handler = new DefaultMetaObjectHandler();
        IdOnlyEntity entity = new IdOnlyEntity();
        entity.setId(100L);
        entity.setContent("test");

        MetaObject metaObject = SystemMetaObject.forObject(entity);
        assertDoesNotThrow(() -> handler.insertFill(metaObject));
        assertDoesNotThrow(() -> handler.updateFill(metaObject));
        assertEquals(100L, entity.getId());
        assertEquals("test", entity.getContent());
    }

    @Test
    @DisplayName("防御性兼容：对继承 BaseTimeEntity 的实体仅自动填充创建与修改时间，不填充缺失的操作人字段")
    void shouldSafelyHandleBaseTimeEntityWithOnlyTimeFields() {
        DefaultMetaObjectHandler handler = new DefaultMetaObjectHandler(() -> "ignored-user", "system");
        TimeAuditEntity entity = new TimeAuditEntity();
        entity.setId(200L);
        entity.setTag("alpha");

        MetaObject metaObject = SystemMetaObject.forObject(entity);
        assertDoesNotThrow(() -> handler.insertFill(metaObject));

        assertNotNull(entity.getCreateTime());
        assertNotNull(entity.getUpdateTime());

        LocalDateTime createdTime = entity.getCreateTime();
        assertDoesNotThrow(() -> handler.updateFill(metaObject));
        assertEquals(createdTime, entity.getCreateTime());
    }

    @Test
    @DisplayName("多租户自动填充：当实体含 String 类型 tenantId 且上下文存在租户时自动填充")
    void shouldAutoFillStringTenantIdOnInsertWhenNull() {
        TenantContextHolder.setTenantId("corp-tencent-100");
        DefaultMetaObjectHandler handler = new DefaultMetaObjectHandler();

        TenantOrderEntity entity = new TenantOrderEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        assertEquals("corp-tencent-100", entity.getTenantId());
    }

    @Test
    @DisplayName("多租户自动填充：当实体含 Long 类型 tenantId 且上下文为数字租户时自动转换并填充")
    void shouldAutoFillLongTenantIdOnInsertWhenNull() {
        TenantContextHolder.setTenantId(88888L);
        DefaultMetaObjectHandler handler = new DefaultMetaObjectHandler();

        LongTenantOrderEntity entity = new LongTenantOrderEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        assertEquals(88888L, entity.getTenantId());
    }

    @Test
    @DisplayName("多租户防御性填充：开发者显式赋值 tenantId 时予以保留不被覆盖")
    void shouldPreserveExplicitTenantIdOnInsert() {
        TenantContextHolder.setTenantId("context-tenant-999");
        DefaultMetaObjectHandler handler = new DefaultMetaObjectHandler();

        TenantOrderEntity entity = new TenantOrderEntity();
        entity.setTenantId("explicit-tenant-override");
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        assertEquals("explicit-tenant-override", entity.getTenantId());
    }

    @Test
    @DisplayName("多租户防空填充：上下文无租户时不填充 tenantId，保持 null")
    void shouldKeepTenantIdNullWhenContextIsEmpty() {
        TenantContextHolder.clear();
        DefaultMetaObjectHandler handler = new DefaultMetaObjectHandler();

        TenantOrderEntity entity = new TenantOrderEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        assertNull(entity.getTenantId());
    }
}

