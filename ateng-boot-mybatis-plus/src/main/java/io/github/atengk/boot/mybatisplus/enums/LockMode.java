package io.github.atengk.boot.mybatisplus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 悲观并发锁控制模式枚举
 * 统一收敛数据库悲观锁后缀语法，杜绝业务层裸写 SQL 字符串片段
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Getter
@AllArgsConstructor
public enum LockMode {

    /**
     * 排他锁（阻塞等待获取行锁）: FOR UPDATE
     */
    FOR_UPDATE("FOR UPDATE"),

    /**
     * 非阻塞排他锁（若被锁定立即抛出锁冲突异常）: FOR UPDATE NOWAIT
     */
    NOWAIT("FOR UPDATE NOWAIT"),

    /**
     * 跳过已锁定行排他锁（高并发任务队列排队消费，自动跳过被其他事务持有的行）: FOR UPDATE SKIP LOCKED
     */
    SKIP_LOCKED("FOR UPDATE SKIP LOCKED"),

    /**
     * 共享锁（读锁，阻塞其他事务获取排他锁）: FOR SHARE
     */
    FOR_SHARE("FOR SHARE");

    /**
     * SQL 语法后缀
     */
    private final String sqlSuffix;
}
