package io.github.atengk.boot.mybatisplus.logicdelete;

import io.github.atengk.boot.mybatisplus.context.LogicDeleteContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 逻辑删除 AST 剥离与穿透上下文拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("AtengLogicDeleteInnerInterceptor 单元测试")
class AtengLogicDeleteInnerInterceptorTest {

    private static final Set<String> COLUMNS = Set.of("deleted", "is_deleted", "del_flag");

    @AfterEach
    void tearDown() {
        LogicDeleteContextHolder.clear();
    }

    @Test
    @DisplayName("测试 SELECT 语句剥离末尾 AND deleted = 0 条件")
    void testStripSelectWithAndLogicDelete() {
        String sql = "SELECT id, name FROM sys_user WHERE id = 1 AND deleted = 0";
        String result = AtengLogicDeleteInnerInterceptor.stripLogicDeleteFromSql(sql, COLUMNS);

        assertThat(result).isEqualToIgnoringWhitespace("SELECT id, name FROM sys_user WHERE id = 1");
    }

    @Test
    @DisplayName("测试 SELECT 语句剥离首部 deleted = 0 AND 条件")
    void testStripSelectWithReverseAndLogicDelete() {
        String sql = "SELECT * FROM sys_user WHERE deleted = 0 AND status = 'ACTIVE'";
        String result = AtengLogicDeleteInnerInterceptor.stripLogicDeleteFromSql(sql, COLUMNS);

        assertThat(result).isEqualToIgnoringWhitespace("SELECT * FROM sys_user WHERE status = 'ACTIVE'");
    }

    @Test
    @DisplayName("测试 SELECT 语句仅有 deleted = 0 时完全移除 WHERE 子句")
    void testStripSelectOnlyLogicDelete() {
        String sql = "SELECT * FROM sys_user WHERE deleted = 0";
        String result = AtengLogicDeleteInnerInterceptor.stripLogicDeleteFromSql(sql, COLUMNS);

        assertThat(result).isEqualToIgnoringWhitespace("SELECT * FROM sys_user");
    }

    @Test
    @DisplayName("测试 UPDATE 恢复语句剥离 WHERE deleted = 0 条件")
    void testStripUpdateStatement() {
        String sql = "UPDATE sys_user SET deleted = 0, update_time = '2026-10-27' WHERE id = 1 AND deleted = 0";
        String result = AtengLogicDeleteInnerInterceptor.stripLogicDeleteFromSql(sql, COLUMNS);

        assertThat(result).isEqualToIgnoringWhitespace("UPDATE sys_user SET deleted = 0, update_time = '2026-10-27' WHERE id = 1");
    }

    @Test
    @DisplayName("测试回收站显式 deleted = 1 查询时保留 deleted = 1 并精准剥离 deleted = 0")
    void testPreserveExplicitDeletedCondition() {
        String sql = "SELECT * FROM sys_user WHERE deleted = 1 AND deleted = 0";
        String result = AtengLogicDeleteInnerInterceptor.stripLogicDeleteFromSql(sql, COLUMNS);

        assertThat(result).isEqualToIgnoringWhitespace("SELECT * FROM sys_user WHERE deleted = 1");
    }

    @Test
    @DisplayName("测试带括号组合条件下的精准剥离")
    void testStripWithParenthesis() {
        String sql = "SELECT * FROM sys_user WHERE (age > 18 OR dept_id = 10) AND deleted = 0";
        String result = AtengLogicDeleteInnerInterceptor.stripLogicDeleteFromSql(sql, COLUMNS);

        assertThat(result).isEqualToIgnoringWhitespace("SELECT * FROM sys_user WHERE (age > 18 OR dept_id = 10)");
    }

    @Test
    @DisplayName("测试带别名表 column 格式剥离（形如 t.deleted = 0）")
    void testStripWithTableAlias() {
        String sql = "SELECT t.id, t.name FROM sys_user t WHERE t.id = 100 AND t.deleted = 0";
        String result = AtengLogicDeleteInnerInterceptor.stripLogicDeleteFromSql(sql, COLUMNS);

        assertThat(result).isEqualToIgnoringWhitespace("SELECT t.id, t.name FROM sys_user t WHERE t.id = 100");
    }

    @Test
    @DisplayName("测试 LogicDeleteContextHolder 上下文生命周期与嵌套调用隔离")
    void testContextHolderLifecycleAndNesting() {
        assertThat(LogicDeleteContextHolder.isWithDeleted()).isFalse();

        LogicDeleteContextHolder.runWithDeleted(() -> {
            assertThat(LogicDeleteContextHolder.isWithDeleted()).isTrue();

            // 嵌套闭包
            String nestedResult = LogicDeleteContextHolder.callWithDeleted(() -> {
                assertThat(LogicDeleteContextHolder.isWithDeleted()).isTrue();
                return "OK";
            });

            assertThat(nestedResult).isEqualTo("OK");
            // 内层退出后外层仍然生效
            assertThat(LogicDeleteContextHolder.isWithDeleted()).isTrue();
        });

        // 外层退出后彻底恢复为 false
        assertThat(LogicDeleteContextHolder.isWithDeleted()).isFalse();
    }

    @Test
    @DisplayName("测试闭包抛出异常时 finally 确定性清理现场")
    void testContextHolderCleanupOnException() {
        assertThat(LogicDeleteContextHolder.isWithDeleted()).isFalse();

        assertThatThrownBy(() -> LogicDeleteContextHolder.runWithDeleted(() -> {
            assertThat(LogicDeleteContextHolder.isWithDeleted()).isTrue();
            throw new RuntimeException("业务模拟异常");
        })).isInstanceOf(RuntimeException.class).hasMessage("业务模拟异常");

        assertThat(LogicDeleteContextHolder.isWithDeleted()).isFalse();
    }
}
