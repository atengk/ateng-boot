package io.github.atengk.boot.sample.mybatisplus.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.model.UserDeptDetailVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 系统用户持久层 Mapper 接口
 * 继承 AtengBaseMapper 挂载原生批量 Insert/Upsert/Update 与原生 Cursor 流式查询方法
 *
 * @author Ateng
 * @since 2026-10-19
 */
public interface UserMapper extends AtengBaseMapper<UserEntity> {

    /**
     * 声明 @DataScope 细粒度数据权限过滤查询
     *
     * @param wrapper 条件构造器
     * @return 过滤后可见用户列表
     */
    @DataScope(deptColumn = "dept_id")
    @Select("SELECT * FROM sys_user ${ew.customSqlSegment}")
    List<UserEntity> selectAccessibleUsers(@Param(Constants.WRAPPER) Wrapper<UserEntity> wrapper);

    /**
     * 多表联查部门与用户分页方法（XML 实现，支持 AST 别名自愈与动态条件注入）
     *
     * @param page    分页对象
     * @param wrapper 包含动态条件与排序的 Wrapper
     * @return 包含用户与部门联合投影的 IPage 分页结果
     */
    IPage<UserDeptDetailVO> selectUserDeptPage(IPage<UserDeptDetailVO> page, @Param(Constants.WRAPPER) Wrapper<UserDeptDetailVO> wrapper);

    /**
     * 演示包含密码/凭据敏感参数的查询方法（用于慢 SQL 拦截器密码脱敏实测）
     *
     * @param username 用户名
     * @param password 密码凭据
     * @return 匹配的用户列表
     */
    @Select("SELECT * FROM sys_user WHERE name = #{username} AND #{password} IS NOT NULL")
    List<UserEntity> selectByUsernameAndPassword(@Param("username") String username, @Param("password") String password);
}
