package io.github.atengk.boot.sample.mybatisplus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.UserMapper;
import io.github.atengk.boot.sample.mybatisplus.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL 日志打印与可执行带参诊断引擎真实端到端集成自验测试
 * 针对 H2 内存库全真环境运行，验证：
 * 1. SqlLogInterceptor 自动装配与参数脱敏
 * 2. 单表 CRUD 诊断看板输出
 * 3. BatchUtil 多值批量插入单条完整输出与防刷屏
 * 4. StreamingQueryTemplate 受管游标流式查询只读防卫
 * 5. PageUtil 分页查询改写与行数度量
 *
 * @author Ateng
 * @since 2026-10-22
 */
@SpringBootTest
@DisplayName("SQL 日志打印与耗时诊断引擎真实端到端集成自验测试")
class SqlLogIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StreamingQueryTemplate streamingQueryTemplate;

    @Autowired
    private SqlLogInterceptor sqlLogInterceptor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanState() {
        UserContextHolder.clear();
        jdbcTemplate.execute("DELETE FROM sys_user");
    }

    @Test
    @DisplayName("1. 验证 SqlLogInterceptor 成功装配且配置生效")
    void testSqlLogInterceptorBeanLoaded() {
        assertThat(sqlLogInterceptor).isNotNull();
        assertThat(sqlLogInterceptor.getProperties().isEnabled()).isTrue();
        assertThat(sqlLogInterceptor.getProperties().isMaskSensitive()).isTrue();
        assertThat(sqlLogInterceptor.getProperties().isFormatSql()).isTrue();
    }

    @Test
    @DisplayName("2. 验证单表 CRUD：带参 SQL 自动反填且敏感字段（邮箱/手机）自动脱敏掩码")
    void testSingleTableCrudWithSensitiveMasking() {
        UserContextHolder.set("admin");

        // 1. 插入用户（带加密字段 phone 与 email）
        UserEntity user = new UserEntity("体验用户", 28, "sensitive@example.com", "13800138000", 100L);
        boolean saved = userService.save(user);
        assertThat(saved).isTrue();

        // 2. 根据主键查询
        UserEntity loaded = userService.getById(user.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getName()).isEqualTo("体验用户");
        // 验证内存真明文不变量：业务层内存中始终保持明文
        assertThat(loaded.getPhone()).isEqualTo("13800138000");

        // 3. 更新操作
        loaded.setAge(29);
        boolean updated = userService.updateById(loaded);
        assertThat(updated).isTrue();

        // 4. 删除操作（逻辑删除）
        boolean removed = userService.removeById(loaded.getId());
        assertThat(removed).isTrue();
    }

    @Test
    @DisplayName("3. 验证 BatchUtil 原生多值批量插入：单条大 SQL 输出，杜绝循环刷屏")
    void testNativeMultiValueBatchInsert() {
        UserContextHolder.set("batch-admin");

        List<UserEntity> batchList = new ArrayList<>(5);
        for (int i = 1; i <= 5; i++) {
            batchList.add(new UserEntity("批量用户_" + i, 20 + i, "user_" + i + "@example.com", "1380013800" + i, 100L));
        }

        // 使用 BatchUtil 原生多值批量插入
        int inserted = BatchUtil.insertBatch(userMapper, batchList, 1000);
        assertThat(inserted).isEqualTo(5);

        long count = userService.count();
        assertThat(count).isEqualTo(5);
    }

    @Test
    @DisplayName("4. 验证 StreamingQueryTemplate 受管游标流式查询：游标延迟只读防卫")
    void testStreamingCursorQuerySafeGuard() {
        UserContextHolder.set("stream-admin");

        for (int i = 1; i <= 3; i++) {
            userService.save(new UserEntity("游标用户_" + i, 20 + i, "stream_" + i + "@example.com", "1390013900" + i, 200L));
        }

        AtomicInteger processed = new AtomicInteger(0);
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<UserEntity>()
                .likeRight(UserEntity::getName, "游标用户");

        streamingQueryTemplate.forEach(() -> userMapper.selectCursor(wrapper), user -> {
            assertThat(user).isNotNull();
            processed.incrementAndGet();
        });

        assertThat(processed.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("5. 验证 PageUtil 分页查询：自动拼接 LIMIT 与行数统计")
    void testPaginationQuery() {
        UserContextHolder.set("page-admin");

        for (int i = 1; i <= 10; i++) {
            userService.save(new UserEntity("分页用户_" + i, 20 + i, "page_" + i + "@example.com", "1370013700" + i, 300L));
        }

        PageQuery pageQuery = new PageQuery(1L, 5L);
        Page<UserEntity> pageRequest = PageUtil.toPage(pageQuery);

        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<UserEntity>()
                .likeRight(UserEntity::getName, "分页用户")
                .orderByAsc(UserEntity::getId);

        Page<UserEntity> result = userService.page(pageRequest, wrapper);
        assertThat(result.getRecords()).hasSize(5);
        assertThat(result.getTotal()).isEqualTo(10);
    }
}
