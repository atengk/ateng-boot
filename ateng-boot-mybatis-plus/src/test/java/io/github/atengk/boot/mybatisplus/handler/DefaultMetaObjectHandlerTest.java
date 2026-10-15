package io.github.atengk.boot.mybatisplus.handler;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.spi.CurrentUserProvider;
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

    @BeforeEach
    @AfterEach
    void cleanUp() {
        UserContextHolder.clear();
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
}
