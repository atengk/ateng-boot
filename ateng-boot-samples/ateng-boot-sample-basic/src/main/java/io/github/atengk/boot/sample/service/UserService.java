package io.github.atengk.boot.sample.service;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.sample.dto.UserCreateDTO;
import io.github.atengk.boot.sample.vo.UserVO;

/**
 * 示例用户业务接口
 * 负责用户创建、查询、更新与分页检索等核心业务逻辑，为控制层提供业务边界防腐
 *
 * @author Ateng
 * @since 2026-10-17
 */
public interface UserService {

    /**
     * 创建新用户
     *
     * @param dto 创建用户请求 DTO
     * @return 新增用户分配的全局唯一 ID
     */
    Long createUser(UserCreateDTO dto);

    /**
     * 根据主键 ID 获取用户视图对象
     *
     * @param id 用户主键 ID
     * @return 用户视图对象
     */
    UserVO getUserById(Long id);

    /**
     * 更新用户名称
     *
     * @param id   用户主键 ID
     * @param name 新用户名称
     * @return 是否更新成功
     */
    boolean updateUserName(Long id, String name);

    /**
     * 分页查询用户列表
     *
     * @param query 分页入参
     * @return 分页视图结果
     */
    PageResult<UserVO> pageUsers(PageQuery query);
}
