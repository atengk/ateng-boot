package io.github.atengk.boot.sample.mybatisplus.mapper;

import io.github.atengk.boot.mybatisplus.annotation.IgnoreTenant;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.sample.mybatisplus.entity.SoftDeleteItemEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 联合唯一键软删除持久层 Mapper 接口
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Mapper
@IgnoreTenant
public interface SoftDeleteItemMapper extends AtengBaseMapper<SoftDeleteItemEntity> {
}
