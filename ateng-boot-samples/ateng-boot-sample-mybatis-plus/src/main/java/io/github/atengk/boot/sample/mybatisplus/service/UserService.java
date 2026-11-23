package io.github.atengk.boot.sample.mybatisplus.service;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.model.DataPermissionCompareVO;
import io.github.atengk.boot.sample.mybatisplus.model.UserSearchDTO;
import io.github.atengk.boot.sample.mybatisplus.model.UserVO;

import java.util.List;

/**
 * 用户业务服务契约
 * 继承 AtengBaseService 统一获取批量写入、动态查询、受管流式查询与零实体泄露基础能力
 *
 * @author Ateng
 * @since 2026-10-19
 */
public interface UserService extends AtengBaseService<UserEntity> {

    /**
     * 基于 @DataScope AST 细粒度数据权限（部门列表 + 本人操作人）查询可见用户列表
     *
     * @param deptIds      授权可访问的部门 ID 列表
     * @param selfOperator 本人操作人标识（可选）
     * @return 过滤后的可见用户视图列表
     */
    List<UserVO> listAccessibleUsersByDataScope(List<Long> deptIds, String selfOperator);

    /**
     * 对比验证 @DataScope 权限过滤与 runAsSuperAdmin / runWithoutPermission 秒级短路放行
     *
     * @param deptIds      授权可访问的部门 ID 列表
     * @param selfOperator 本人操作人标识（可选）
     * @return 数据权限过滤与短路放行对比视图
     */
    DataPermissionCompareVO compareDataPermissionAndBypass(List<Long> deptIds, String selfOperator);

    /**
     * 基于 PageUtil 实体白名单安全排序与流式 VO 转换执行用户分页查询
     *
     * @param pageQuery 分页与排序入参
     * @return 标准传输层用户分页结果
     */
    PageResult<UserVO> pageUsersSafely(PageQuery pageQuery);

    /**
     * 基于 URL Query 冒号高阶语法（含 ~ 波浪线范围、or_组号: 嵌套分组与六重安全防卫）执行动态分页查询
     *
     * @param queryString 原始 URL Query 字符串
     * @return 标准传输层用户分页结果
     */
    PageResult<UserVO> pageUsersByDynamicUrl(String queryString);

    /**
     * 基于声明了 @QueryField 的业务检索 DTO 执行动态分页查询
     *
     * @param searchDTO 用户声明式检索 DTO
     * @return 标准传输层用户分页结果
     */
    PageResult<UserVO> pageUsersBySearchDto(UserSearchDTO searchDTO);
}
