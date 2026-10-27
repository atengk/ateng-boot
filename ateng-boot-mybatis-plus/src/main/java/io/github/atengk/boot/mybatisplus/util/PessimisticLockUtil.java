package io.github.atengk.boot.mybatisplus.util;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.SharedString;
import io.github.atengk.boot.mybatisplus.enums.LockMode;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 悲观并发锁控制工具类
 * 统一收敛 Wrapper 级别悲观锁语法注入与底层方言快速失败防御，杜绝业务层裸写 SQL 片段
 *
 * @author Ateng
 * @since 2026-10-27
 */
public final class PessimisticLockUtil {

    private static volatile DbType currentDbType;

    private static final Field LAST_SQL_FIELD;

    static {
        Field field = null;
        try {
            field = AbstractWrapper.class.getDeclaredField("lastSql");
            field.setAccessible(true);
        } catch (Exception ignored) {
        }
        LAST_SQL_FIELD = field;
    }

    /**
     * 明确不支持任何行级悲观锁语法的方言集合
     */
    private static final Set<DbType> UNSUPPORTED_LOCK_DIALECTS = EnumSet.of(
            DbType.SQLITE,
            DbType.CLICK_HOUSE,
            DbType.HIVE2,
            DbType.PRESTO,
            DbType.TRINO,
            DbType.DUCKDB
    );

    /**
     * 明确不支持 SKIP LOCKED / NOWAIT 跳锁排队特性的方言集合
     */
    private static final Set<DbType> UNSUPPORTED_SKIP_LOCKED_DIALECTS = EnumSet.of(
            DbType.DERBY,
            DbType.HSQL,
            DbType.SQL_SERVER2005
    );

    private PessimisticLockUtil() {
    }

    /**
     * 设置当前数据库方言类型
     *
     * @param dbType 数据库方言枚举
     */
    public static void setDbType(DbType dbType) {
        currentDbType = dbType;
    }

    /**
     * 获取当前配置的数据库方言类型
     *
     * @return 数据库方言枚举，未显式探测或配置时返回 null
     */
    public static DbType getDbType() {
        return currentDbType;
    }

    /**
     * 重置数据库方言类型（主要用于单测与多数据源现场清理）
     */
    public static void resetDbType() {
        currentDbType = null;
    }

    /**
     * 向条件封装器安全注入默认悲观排他锁语法（FOR UPDATE）
     *
     * @param wrapper 条件构造器实例（不能为 null）
     * @param <W>     Wrapper 泛型
     * @return 注入悲观锁后的原条件构造器
     */
    public static <W extends AbstractWrapper<?, ?, ?>> W forUpdate(W wrapper) {
        return apply(wrapper, LockMode.FOR_UPDATE);
    }

    /**
     * 向条件封装器安全注入指定模式的悲观锁语法
     *
     * @param wrapper  条件构造器实例（不能为 null）
     * @param lockMode 悲观锁控制模式（不能为 null）
     * @param <W>      Wrapper 泛型
     * @return 注入悲观锁后的原条件构造器
     */
    public static <W extends AbstractWrapper<?, ?, ?>> W forUpdate(W wrapper, LockMode lockMode) {
        return apply(wrapper, lockMode);
    }

    /**
     * 向条件封装器注入指定模式的悲观锁控制语法，并在方言不匹配时实施 Fail-Fast 快速失败阻断
     * 智能识别并保留既有 lastSql（如 LIMIT 子句），并防范重复追加锁片段
     *
     * @param wrapper  条件构造器实例（不能为 null）
     * @param lockMode 悲观锁控制模式（不能为 null）
     * @param <W>      Wrapper 泛型
     * @return 注入悲观锁后的原条件构造器
     * @throws UnsupportedOperationException 当底层数据库方言明确不支持目标悲观锁语义时抛出
     */
    public static <W extends AbstractWrapper<?, ?, ?>> W apply(W wrapper, LockMode lockMode) {
        Objects.requireNonNull(wrapper, "wrapper 不能为 null");
        Objects.requireNonNull(lockMode, "lockMode 不能为 null");
        validateDialect(lockMode);

        String suffix = lockMode.getSqlSuffix();
        String existingLastSql = getExistingLastSql(wrapper);

        if (existingLastSql != null && !existingLastSql.isBlank()) {
            String trimmed = existingLastSql.trim();
            if (trimmed.endsWith(suffix)) {
                // 已存在相同锁语法，幂等返回
                return wrapper;
            }
            wrapper.last(trimmed + " " + suffix);
        } else {
            wrapper.last(suffix);
        }

        return wrapper;
    }

    /**
     * 获取 Wrapper 中已有的 lastSql 字符串内容
     *
     * @param wrapper 条件构造器实例
     * @return 原已设置的 lastSql 字符串，不存在或获取失败时返回 null
     */
    private static String getExistingLastSql(AbstractWrapper<?, ?, ?> wrapper) {
        if (LAST_SQL_FIELD == null) {
            return null;
        }
        try {
            SharedString sharedString = (SharedString) LAST_SQL_FIELD.get(wrapper);
            return sharedString != null ? sharedString.getStringValue() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 校验当前数据库方言对指定悲观锁语法的支持度
     *
     * @param lockMode 悲观锁控制模式
     */
    private static void validateDialect(LockMode lockMode) {
        DbType dbType = currentDbType;
        if (dbType == null) {
            return;
        }
        if (UNSUPPORTED_LOCK_DIALECTS.contains(dbType)) {
            throw new UnsupportedOperationException("当前数据库方言 [" + dbType + "] 不支持悲观行级锁特性");
        }
        if (lockMode == LockMode.SKIP_LOCKED || lockMode == LockMode.NOWAIT) {
            if (UNSUPPORTED_SKIP_LOCKED_DIALECTS.contains(dbType)) {
                throw new UnsupportedOperationException("当前数据库方言 [" + dbType + "] 不支持 " + lockMode.name() + " 悲观排队锁特性");
            }
        }
    }
}
