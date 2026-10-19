package io.github.atengk.boot.sample.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.sample.entity.UserEntity;
import io.github.atengk.boot.sample.vo.UserRoleVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 示例用户持久层 Mapper 接口
 * 继承 AtengBaseMapper 提供标准 CRUD 与多表联表查询能力
 *
 * @author Ateng
 * @since 2026-10-15
 */
@Mapper
public interface UserMapper extends AtengBaseMapper<UserEntity> {

    /**
     * 多表关联分页查询用户角色信息
     *
     * @param page    分页入参
     * @param wrapper 条件构造器
     * @return 分页视图结果
     */
    <P extends com.baomidou.mybatisplus.core.metadata.IPage<UserRoleVO>> P selectUserRolePage(P page, @Param(Constants.WRAPPER) Wrapper<UserRoleVO> wrapper);

    /**
     * 多表关联列表查询用户角色信息
     *
     * @param wrapper 条件构造器
     * @return 列表视图结果
     */
    List<UserRoleVO> selectUserRoleList(@Param(Constants.WRAPPER) Wrapper<UserRoleVO> wrapper);
}
