package io.github.atengk.boot.sample.mybatisplus.mapper;

import io.github.atengk.boot.mybatisplus.annotation.IgnoreTenant;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.sample.mybatisplus.entity.DemoJsonUserEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * JSON 字段映射演示持久层 Mapper 接口
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Mapper
@IgnoreTenant
public interface DemoJsonUserMapper extends AtengBaseMapper<DemoJsonUserEntity> {
}
