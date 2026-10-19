package io.github.atengk.boot.sample.mapper;

import io.github.atengk.boot.mybatisplus.annotation.IgnoreTenant;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.sample.entity.TenantOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 多租户订单 Mapper 接口
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Mapper
public interface TenantOrderMapper extends AtengBaseMapper<TenantOrderEntity> {

    /**
     * 标注 @IgnoreTenant 注解的特权查询方法
     * 自动短路绕过行级多租户过滤，查询全量跨租户订单数据
     *
     * @return 全量跨租户订单列表
     */
    @IgnoreTenant
    @Select("SELECT * FROM sys_tenant_order")
    List<TenantOrderEntity> selectAllWithIgnoreTenant();
}
