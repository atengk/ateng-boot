package io.github.atengk.boot.sample.mybatisplus;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryHelper;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.UserMapper;
import io.github.atengk.boot.sample.mybatisplus.permission.SampleSecurityContext;
import io.github.atengk.boot.sample.mybatisplus.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户领域模型（sys_user）端到端集成自验测试
 * 覆盖：
 * 1. 实体五级继承阶梯链（BaseFullEntity）全要素生命周期
 * 2. 审计自动填充（createBy/updateBy/createTime/updateTime）与显式赋值防御
 * 3. 逻辑删除（@TableLogic 改写 UPDATE 与物理留存）
 * 4. 字段级透明存储加密（@FieldEncrypt 入库密文、出库真明文）
 * 5. 声明式细粒度数据权限（@DataScope AST 条件追加与超管/闭包放行）
 * 6. DynamicQueryHelper 动态查询与 URL 冒号语法安全转译
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootTest
@DisplayName("用户领域模型 (sys_user) 核心特性集成自验")
class UserDomainIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanState() {
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        SampleSecurityContext.clear();
        jdbcTemplate.execute("DELETE FROM sys_user");
    }

    @Test
    @DisplayName("1. 验证实体五级阶梯链全要素与审计元数据自动填充")
    void shouldDemonstrateFiveTierEntityHierarchyAndAuditAutoFill() {
        UserContextHolder.set("admin-operator");

        UserEntity user = new UserEntity("张三", 25, "zhangsan@example.com", "13800138000", 100L);
        boolean saved = userService.save(user);

        assertThat(saved).isTrue();
        assertThat(user.getId()).isNotNull(); // 64位雪花 ID (BaseIdEntity)
        assertThat(user.getCreateTime()).isNotNull(); // 时间流转 (BaseTimeEntity)
        assertThat(user.getUpdateTime()).isNotNull();
        assertThat(user.getCreateBy()).isEqualTo("admin-operator"); // 四要素审计 (BaseEntity)
        assertThat(user.getUpdateBy()).isEqualTo("admin-operator");

        UserEntity loaded = userService.getById(user.getId());
        assertThat(loaded.getVersion()).isEqualTo(0); // 乐观锁 (BaseFullEntity)
        assertThat(loaded.getDeleted()).isEqualTo(0); // 逻辑删除 (BaseFullEntity)
    }

    @Test
    @DisplayName("2. 验证审计自动填充防御性策略：显式赋值绝不被覆盖")
    void shouldDefendExplicitAuditFieldsNotOverwritten() {
        UserContextHolder.set("operator-auto");

        LocalDateTime explicitPastTime = LocalDateTime.of(2025, 1, 1, 12, 0, 0);
        UserEntity user = new UserEntity("李四", 30, "lisi@example.com", "13900139000", 200L);
        user.setCreateBy("EXPLICIT_CREATOR");
        user.setCreateTime(explicitPastTime);

        userService.save(user);

        UserEntity loaded = userService.getById(user.getId());
        assertThat(loaded.getCreateBy()).isEqualTo("EXPLICIT_CREATOR");
        assertThat(loaded.getCreateTime()).isEqualTo(explicitPastTime);
        assertThat(loaded.getUpdateBy()).isEqualTo("operator-auto");
    }

    @Test
    @DisplayName("3. 验证逻辑删除：业务查询自动过滤且底层记录物理留存 (deleted=1)")
    void shouldPerformLogicDeleteAndExcludeFromQueries() {
        UserEntity user = new UserEntity("王五", 28, "wangwu@example.com", "13700137000", 100L);
        userService.save(user);
        Long userId = user.getId();

        // 执行逻辑删除
        boolean removed = userService.removeById(userId);
        assertThat(removed).isTrue();

        // 业务查询已被过滤
        UserEntity queryUser = userService.getById(userId);
        assertThat(queryUser).isNull();

        // 底层 JDBC 物理查询证实物理记录依然存在且 deleted=1
        Integer deletedVal = jdbcTemplate.queryForObject(
                "SELECT deleted FROM sys_user WHERE id = ?", Integer.class, userId);
        assertThat(deletedVal).isEqualTo(1);
    }

    @Test
    @DisplayName("4. 验证存储透明加密与内存真明文铁律：入库 AES 密文，出库真实明文")
    void shouldDemonstrateFieldEncryptionStorageCipherAndInMemoryPlaintext() {
        String rawPhone = "13812345678";
        String rawEmail = "secret@ateng.com";
        UserEntity user = new UserEntity("赵六", 35, rawEmail, rawPhone, 100L);
        userService.save(user);

        // 1. 物理 JDBC 直查底层数据库表，断言字段已被透明加密为密文（非明文字符串）
        Map<String, Object> rawRow = jdbcTemplate.queryForMap(
                "SELECT phone, email FROM sys_user WHERE id = ?", user.getId());
        String dbPhone = (String) rawRow.get("phone");
        String dbEmail = (String) rawRow.get("email");

        assertThat(dbPhone).isNotEqualTo(rawPhone);
        assertThat(dbEmail).isNotEqualTo(rawEmail);
        assertThat(dbPhone).isNotBlank();
        assertThat(dbEmail).isNotBlank();

        // 2. 持久层出库反序列化后，断言内存实体对象中 100% 还原为真实明文
        UserEntity loaded = userService.getById(user.getId());
        assertThat(loaded.getPhone()).isEqualTo(rawPhone);
        assertThat(loaded.getEmail()).isEqualTo(rawEmail);
    }

    @Test
    @DisplayName("5. 验证基于 JSqlParser AST 的 @DataScope 细粒度部门数据权限过滤")
    void shouldFilterByDataScopeDepartmentPermission() {
        UserEntity userDept10 = new UserEntity("部门10用户", 22, "u10@example.com", "13100000010", 10L);
        UserEntity userDept20 = new UserEntity("部门20用户", 24, "u20@example.com", "13100000020", 20L);
        UserEntity userDept30 = new UserEntity("部门30用户", 26, "u30@example.com", "13100000030", 30L);
        userService.saveBatch(List.of(userDept10, userDept20, userDept30));

        // 设置当前安全上下文仅有部门 10 与 20 的权限
        SampleSecurityContext.setAccessibleDeptIds(List.of(10L, 20L));

        List<UserEntity> accessibleUsers = userMapper.selectAccessibleUsers(new QueryWrapper<>());
        assertThat(accessibleUsers).hasSize(2)
                .extracting(UserEntity::getName)
                .containsExactlyInAnyOrder("部门10用户", "部门20用户");
    }

    @Test
    @DisplayName("6. 验证 @DataScope 超级管理员放行与编程式 runWithoutPermission 豁免闭包")
    void shouldBypassDataScopeWhenSuperAdminOrWithoutPermission() {
        UserEntity user1 = new UserEntity("用户A", 20, "a@example.com", "13100000001", 10L);
        UserEntity user2 = new UserEntity("用户B", 25, "b@example.com", "13100000002", 999L);
        userService.saveBatch(List.of(user1, user2));

        // 限制只能看部门 10
        SampleSecurityContext.setAccessibleDeptIds(List.of(10L));

        // 1. 超级管理员身份秒级放行
        DataPermissionContextHolder.runAsSuperAdmin(() -> {
            List<UserEntity> list = userMapper.selectAccessibleUsers(new QueryWrapper<>());
            assertThat(list).hasSize(2);
        });

        // 2. 编程式闭包豁免放行
        DataPermissionContextHolder.runWithoutPermission(() -> {
            List<UserEntity> list = userMapper.selectAccessibleUsers(new QueryWrapper<>());
            assertThat(list).hasSize(2);
        });
    }

    @Test
    @DisplayName("7. 验证 DynamicQueryHelper 动态查询与 URL 冒号语法安全转译")
    void shouldExecuteDynamicQueryWithUrlColonSyntax() {
        UserEntity u1 = new UserEntity("阿里工程师", 28, "ali@example.com", "13500000001", 10L);
        UserEntity u2 = new UserEntity("腾讯产品汪", 32, "tx@example.com", "13500000002", 10L);
        UserEntity u3 = new UserEntity("字节运营喵", 22, "bytedance@example.com", "13500000003", 20L);
        userService.saveBatch(List.of(u1, u2, u3));

        // 模拟前端传入 URL 参数: age:gte=25&name:like=工
        String queryString = "age:gte=25&name:like=工";
        QueryWrapper<UserEntity> wrapper = DynamicQueryHelper.buildWrapper(queryString, UserEntity.class);

        List<UserEntity> matched = userService.list(wrapper);
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).getName()).isEqualTo("阿里工程师");
    }
}
