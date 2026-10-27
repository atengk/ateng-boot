package io.github.atengk.boot.sample.mybatisplus.service;

import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.mybatisplus.slowsql.SlowSqlEvent;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;

import java.util.List;

/**
 * 慢 SQL 性能拦截、敏感脱敏与告警业务场景服务契约
 * 演示慢 SQL 执行耗时拦截、格式化可执行 SQL 美化、调用栈定位与敏感字段掩码
 *
 * @author Ateng
 * @since 2026-10-28
 */
public interface SlowSqlAlertScenarioService extends AtengBaseService<UserEntity> {

    /**
     * 创建包含敏感加密字段的用户实体（演示插入时 @FieldEncrypt 字段掩码）
     *
     * @param name   用户姓名
     * @param age    年龄
     * @param email  邮箱（加密字段）
     * @param phone  手机号（加密字段）
     * @param deptId 部门 ID
     * @return 保存后的用户实体
     */
    UserEntity createUserWithEncryptedFields(String name, Integer age, String email, String phone, Long deptId);

    /**
     * 根据主键查询用户实体（演示查询触发慢 SQL 拦截）
     *
     * @param id 用户主键 ID
     * @return 用户实体
     */
    UserEntity getUserById(Long id);

    /**
     * 查询符合年龄条件的用户列表
     *
     * @param minAge 最小年龄
     * @return 用户实体列表
     */
    List<UserEntity> listUsersByAge(Integer minAge);

    /**
     * 获取所有收集到的慢 SQL 告警事件
     *
     * @return 慢 SQL 事件列表
     */
    List<SlowSqlEvent> getCapturedAlerts();

    /**
     * 清空已收集的慢 SQL 告警事件
     */
    void clearAlerts();
}
