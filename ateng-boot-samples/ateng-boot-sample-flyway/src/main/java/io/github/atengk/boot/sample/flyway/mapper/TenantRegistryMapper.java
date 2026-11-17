package io.github.atengk.boot.sample.flyway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.atengk.boot.sample.flyway.entity.TenantRegistryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 平台租户注册表 Mapper
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Mapper
public interface TenantRegistryMapper extends BaseMapper<TenantRegistryEntity> {
}
