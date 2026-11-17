package io.github.atengk.boot.sample.flyway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.atengk.boot.sample.flyway.entity.TenantOrderEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * SaaS 租户订单表 Mapper
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Mapper
public interface TenantOrderMapper extends BaseMapper<TenantOrderEntity> {
}
