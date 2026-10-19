package io.github.atengk.boot.sample.mybatisplus.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
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
}
