package io.github.atengk.boot.mybatisplus.util;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.atengk.boot.mybatisplus.enums.LockMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 悲观并发锁控制工具单元测试
 * 严格覆盖锁语法注入、方言快速失败拦截及前置判空防御
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("PessimisticLockUtil 单元测试")
class PessimisticLockUtilTest {

    @AfterEach
    void tearDown() {
        PessimisticLockUtil.resetDbType();
    }

    @Test
    @DisplayName("测试注入默认悲观排他锁语法")
    void testForUpdateDefault() {
        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        wrapper.eq("status", "PENDING");
        PessimisticLockUtil.forUpdate(wrapper);

        assertThat(wrapper.getCustomSqlSegment()).contains("FOR UPDATE");
    }

    @Test
    @DisplayName("测试保留已有 lastSql（如 LIMIT 子句）并防范重复追加")
    void testPreserveExistingLastSqlAndIdempotency() {
        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        wrapper.eq("status", "PENDING").last("LIMIT 10");

        PessimisticLockUtil.apply(wrapper, LockMode.SKIP_LOCKED);
        assertThat(wrapper.getCustomSqlSegment()).contains("LIMIT 10 FOR UPDATE SKIP LOCKED");

        // 重复调用实施幂等防护，不产生重复锁后缀
        PessimisticLockUtil.apply(wrapper, LockMode.SKIP_LOCKED);
        assertThat(wrapper.getCustomSqlSegment()).contains("LIMIT 10 FOR UPDATE SKIP LOCKED");
        assertThat(wrapper.getCustomSqlSegment()).doesNotContain("SKIP LOCKED FOR UPDATE SKIP LOCKED");
    }

    @Test
    @DisplayName("测试显式指定 LockMode 语法注入")
    void testApplyAllLockModes() {
        QueryWrapper<Object> wrapper1 = new QueryWrapper<>();
        PessimisticLockUtil.apply(wrapper1, LockMode.NOWAIT);
        assertThat(wrapper1.getCustomSqlSegment()).contains("FOR UPDATE NOWAIT");

        QueryWrapper<Object> wrapper2 = new QueryWrapper<>();
        PessimisticLockUtil.apply(wrapper2, LockMode.SKIP_LOCKED);
        assertThat(wrapper2.getCustomSqlSegment()).contains("FOR UPDATE SKIP LOCKED");

        QueryWrapper<Object> wrapper3 = new QueryWrapper<>();
        PessimisticLockUtil.apply(wrapper3, LockMode.FOR_SHARE);
        assertThat(wrapper3.getCustomSqlSegment()).contains("FOR SHARE");
    }

    @Test
    @DisplayName("测试 SQLite 等不支持行锁方言下快速失败阻断")
    void testFailFastOnUnsupportedLockDialect() {
        PessimisticLockUtil.setDbType(DbType.SQLITE);
        QueryWrapper<Object> wrapper = new QueryWrapper<>();

        assertThatThrownBy(() -> PessimisticLockUtil.apply(wrapper, LockMode.FOR_UPDATE))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SQLITE")
                .hasMessageContaining("不支持悲观行级锁特性");

        assertThatThrownBy(() -> PessimisticLockUtil.apply(wrapper, LockMode.SKIP_LOCKED))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SQLITE");
    }

    @Test
    @DisplayName("测试不支持跳锁方言下 SKIP_LOCKED 与 NOWAIT 快速失败阻断")
    void testFailFastOnUnsupportedSkipLockedDialect() {
        PessimisticLockUtil.setDbType(DbType.HSQL);
        QueryWrapper<Object> wrapper = new QueryWrapper<>();

        // HSQL 支持普通 FOR UPDATE
        PessimisticLockUtil.apply(wrapper, LockMode.FOR_UPDATE);
        assertThat(wrapper.getCustomSqlSegment()).contains("FOR UPDATE");

        // 但不支持 SKIP_LOCKED 和 NOWAIT
        QueryWrapper<Object> skipWrapper = new QueryWrapper<>();
        assertThatThrownBy(() -> PessimisticLockUtil.apply(skipWrapper, LockMode.SKIP_LOCKED))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("HSQL")
                .hasMessageContaining("SKIP_LOCKED");

        QueryWrapper<Object> nowaitWrapper = new QueryWrapper<>();
        assertThatThrownBy(() -> PessimisticLockUtil.apply(nowaitWrapper, LockMode.NOWAIT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("HSQL")
                .hasMessageContaining("NOWAIT");
    }

    @Test
    @DisplayName("测试主流支持跳锁数据库方言放行")
    void testAllowSupportedDialects() {
        for (DbType dbType : new DbType[]{DbType.MYSQL, DbType.POSTGRE_SQL, DbType.ORACLE, DbType.H2, DbType.DM, DbType.KINGBASE_ES}) {
            PessimisticLockUtil.setDbType(dbType);
            QueryWrapper<Object> wrapper = new QueryWrapper<>();
            PessimisticLockUtil.apply(wrapper, LockMode.SKIP_LOCKED);
            assertThat(wrapper.getCustomSqlSegment()).contains("FOR UPDATE SKIP LOCKED");
        }
    }

    @Test
    @DisplayName("测试入参空安全前置防御")
    void testNullSafety() {
        assertThatThrownBy(() -> PessimisticLockUtil.apply(null, LockMode.FOR_UPDATE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("wrapper 不能为 null");

        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        assertThatThrownBy(() -> PessimisticLockUtil.apply(wrapper, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("lockMode 不能为 null");
    }
}
