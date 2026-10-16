package io.github.atengk.boot.mybatisplus.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.mybatisplus.context.TenantContextHolder;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;

/**
 * 数据持久层智能审计元对象填充处理器
 * 插入时自动填充创建/修改时间、操作人与租户标识，更新时自动填充修改时间与修改人，采用防御性策略优先保留开发者显式赋值
 *
 * @author Ateng
 * @since 2026-10-15
 */
public class DefaultMetaObjectHandler implements MetaObjectHandler {

    public static final String FIELD_CREATE_TIME = "createTime";
    public static final String FIELD_UPDATE_TIME = "updateTime";
    public static final String FIELD_CREATE_BY = "createBy";
    public static final String FIELD_UPDATE_BY = "updateBy";
    public static final String FIELD_TENANT_ID = "tenantId";
    public static final String DEFAULT_OPERATOR = "SYSTEM";

    private final CurrentUserProvider currentUserProvider;
    private final String defaultOperator;

    public DefaultMetaObjectHandler(CurrentUserProvider currentUserProvider, String defaultOperator) {
        this.currentUserProvider = currentUserProvider;
        this.defaultOperator = (defaultOperator != null && !defaultOperator.isBlank()) ? defaultOperator : DEFAULT_OPERATOR;
    }

    public DefaultMetaObjectHandler(String defaultOperator) {
        this(UserContextHolder::get, defaultOperator);
    }

    public DefaultMetaObjectHandler() {
        this(UserContextHolder::get, DEFAULT_OPERATOR);
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        String operator = resolveCurrentUser();

        // 防御性填充：仅当属性存在且当前为 null 时进行自动填充，保留开发者显式赋的值
        fillPropertyIfNull(FIELD_CREATE_TIME, now, metaObject);
        fillPropertyIfNull(FIELD_UPDATE_TIME, now, metaObject);
        fillPropertyIfNull(FIELD_CREATE_BY, operator, metaObject);
        fillPropertyIfNull(FIELD_UPDATE_BY, operator, metaObject);

        // 多租户 ID 自动填充：仅当属性存在且当前为 null，且上下文有租户标识时填充
        fillTenantIdIfNull(metaObject);
    }


    @Override
    public void updateFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        String operator = resolveCurrentUser();

        // 更新时始终刷新修改时间与修改人，不受实体原有旧时间阻碍
        if (metaObject != null && metaObject.hasSetter(FIELD_UPDATE_TIME)) {
            setFieldValByName(FIELD_UPDATE_TIME, now, metaObject);
        }
        if (metaObject != null && metaObject.hasSetter(FIELD_UPDATE_BY)) {
            setFieldValByName(FIELD_UPDATE_BY, operator, metaObject);
        }
    }

    /**
     * 智能防御性属性填充：仅在目标实体具备 Setter 且当前字段值为 null 时填充
     *
     * @param fieldName  属性名称
     * @param fieldVal   待填充属性值
     * @param metaObject 元对象
     */
    private void fillPropertyIfNull(String fieldName, Object fieldVal, MetaObject metaObject) {
        if (metaObject != null && metaObject.hasSetter(fieldName)) {
            Object currentVal = getFieldValByName(fieldName, metaObject);
            if (currentVal == null && fieldVal != null) {
                setFieldValByName(fieldName, fieldVal, metaObject);
            }
        }
    }

    /**
     * 解析当前操作人标识，优先从 SPI 获取，空则回退至 ThreadLocal 内存上下文，最后由配置的默认值兜底
     *
     * @return 当前操作人标识
     */
    private String resolveCurrentUser() {
        String user = null;
        if (currentUserProvider != null) {
            try {
                user = currentUserProvider.getCurrentUser();
            } catch (Throwable ignored) {
                // 防御 SPI 接入实现抛出异常导致业务事务中断
            }
        }

        if (user == null || user.isBlank()) {
            user = UserContextHolder.get();
        }

        if (user == null || user.isBlank()) {
            user = defaultOperator;
        }

        return user;
    }

    /**
     * 多租户属性防御性自动填充：仅当目标实体包含 tenantId 属性且当前为 null，且当前线程上下文存在有效租户时填充
     * 兼容 Long 与 String 两种常见的租户字段类型，保留业务端显式赋值
     *
     * @param metaObject 元对象
     */
    private void fillTenantIdIfNull(MetaObject metaObject) {
        if (metaObject == null || !metaObject.hasSetter(FIELD_TENANT_ID)) {
            return;
        }
        Object currentVal = getFieldValByName(FIELD_TENANT_ID, metaObject);
        if (currentVal != null) {
            return;
        }
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        Class<?> getterType = metaObject.getGetterType(FIELD_TENANT_ID);
        if (Long.class.equals(getterType)) {
            try {
                setFieldValByName(FIELD_TENANT_ID, Long.parseLong(tenantId), metaObject);
            } catch (NumberFormatException ignored) {
                // 上下文为非纯数字格式时静默跳过，避免类型转换异常
            }
        } else if (String.class.equals(getterType)) {
            setFieldValByName(FIELD_TENANT_ID, tenantId, metaObject);
        }
    }
}

