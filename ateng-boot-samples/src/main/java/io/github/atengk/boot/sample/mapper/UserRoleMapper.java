package io.github.atengk.boot.sample.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.atengk.boot.sample.entity.UserRoleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户角色轻量关联表 Mapper 持久层接口
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRoleEntity> {
}
