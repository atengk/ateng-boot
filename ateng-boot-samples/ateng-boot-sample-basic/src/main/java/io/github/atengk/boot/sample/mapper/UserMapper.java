package io.github.atengk.boot.sample.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.atengk.boot.sample.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 示例用户持久层 Mapper 接口
 * 继承 MyBatis-Plus 泛型 BaseMapper 提供标准 CRUD 能力
 *
 * @author Ateng
 * @since 2026-10-15
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
