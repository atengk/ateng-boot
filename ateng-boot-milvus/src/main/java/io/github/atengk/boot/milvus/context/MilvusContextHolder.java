package io.github.atengk.boot.milvus.context;

import org.springframework.util.Assert;

/**
 * Milvus 线程上下文持有者
 * 支持动态切库治理，保障线程级隔离与确定性现场恢复
 *
 * @author Ateng
 * @since 2026-11-04
 */
public final class MilvusContextHolder {

    private static final ThreadLocal<String> DATABASE_HOLDER = new ThreadLocal<>();

    private MilvusContextHolder() {
    }

    /**
     * 设置当前线程绑定的逻辑数据库名称
     *
     * @param database 数据库名称
     */
    public static void setDatabase(String database) {
        DATABASE_HOLDER.set(database);
    }

    /**
     * 获取当前线程绑定的逻辑数据库名称
     *
     * @return 数据库名称，若未设置则返回 null
     */
    public static String getDatabase() {
        return DATABASE_HOLDER.get();
    }

    /**
     * 清理当前线程绑定的逻辑数据库
     */
    public static void clearDatabase() {
        DATABASE_HOLDER.remove();
    }

    /**
     * 清理当前线程的所有 Milvus 上下文现场
     */
    public static void clear() {
        DATABASE_HOLDER.remove();
    }

    /**
     * 进入指定逻辑数据库作用域并返回可自动恢复上一层上下文的作用域句柄
     *
     * @param database 目标逻辑数据库名称
     * @return 实现 {@link AutoCloseable} 的作用域句柄，退出 try-with-resources 时确定性还原现场
     */
    public static DatabaseScope useDatabase(String database) {
        Assert.hasText(database, "database must not be empty");
        String previousDatabase = getDatabase();
        setDatabase(database.trim());
        return new DatabaseScope(previousDatabase);
    }

    /**
     * 逻辑数据库作用域句柄，保障嵌套切库及异常退出后精确恢复外层数据库上下文
     */
    public static final class DatabaseScope implements AutoCloseable {

        private final String previousDatabase;
        private boolean closed;

        private DatabaseScope(String previousDatabase) {
            this.previousDatabase = previousDatabase;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previousDatabase != null) {
                setDatabase(previousDatabase);
            } else {
                clearDatabase();
            }
        }
    }
}
