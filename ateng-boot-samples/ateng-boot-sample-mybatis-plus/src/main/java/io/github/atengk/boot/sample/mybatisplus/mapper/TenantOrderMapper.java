package io.github.atengk.boot.sample.mybatisplus.mapper;

import io.github.atengk.boot.mybatisplus.annotation.IgnoreTenant;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.sample.mybatisplus.entity.TenantOrderEntity;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 租户订单持久层 Mapper 接口
 * 继承 AtengBaseMapper 挂载原生批量操作与 Cursor 游标查询，支持 @IgnoreTenant 绕过行级多租户过滤
 *
 * @author Ateng
 * @since 2026-10-19
 */
public interface TenantOrderMapper extends AtengBaseMapper<TenantOrderEntity> {

    /**
     * 声明 @IgnoreTenant 绕过多租户行级过滤
     *
     * @return 全量订单列表（忽略租户）
     */
    @IgnoreTenant
    @Select("SELECT * FROM tenant_order")
    List<TenantOrderEntity> selectAllOrdersIgnoreTenant();
}
