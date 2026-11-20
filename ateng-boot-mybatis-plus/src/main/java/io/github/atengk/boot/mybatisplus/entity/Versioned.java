package io.github.atengk.boot.mybatisplus.entity;

/**
 * CAS 乐观锁版本号能力契约接口
 * 声明并发控制版本号读写方法，用于支持框架自动自愈初始版本号与并发自旋重试
 *
 * @author Ateng
 * @since 2026-11-20
 */
public interface Versioned {

    /**
     * 获取乐观锁版本号
     *
     * @return 版本号
     */
    Integer getVersion();

    /**
     * 设置乐观锁版本号
     *
     * @param version 版本号
     */
    void setVersion(Integer version);
}
