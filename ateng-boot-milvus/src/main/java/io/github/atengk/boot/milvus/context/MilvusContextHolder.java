package io.github.atengk.boot.milvus.context;

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
}
