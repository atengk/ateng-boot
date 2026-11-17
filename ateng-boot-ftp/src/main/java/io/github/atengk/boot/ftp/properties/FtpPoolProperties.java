package io.github.atengk.boot.ftp.properties;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * FTP 客户端连接池配置属性
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
@EqualsAndHashCode
@ToString
public class FtpPoolProperties {

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final Set<String> configuredFields = new HashSet<>();

    /**
     * 连接池最大总连接数
     */
    private int maxTotal = 16;

    /**
     * 连接池最大空闲连接数
     */
    private int maxIdle = 8;

    /**
     * 连接池最小空闲连接数
     */
    private int minIdle = 2;

    /**
     * 从连接池获取连接的最大等待超时时间（超过此时间抛出 PoolExhausted 异常）
     */
    private Duration maxWait = Duration.ofSeconds(5);

    /**
     * 从连接池借出连接时是否执行存活校验（发送 NOOP 心跳探针）
     */
    private boolean testOnBorrow = true;

    /**
     * 归还连接时是否执行有效性校验
     */
    private boolean testOnReturn = false;

    /**
     * 连接在连接池空闲时是否被后台驱逐线程校验
     */
    private boolean testWhileIdle = true;

    /**
     * 空闲连接后台驱逐线程的巡检周期
     */
    private Duration timeBetweenEvictionRuns = Duration.ofMinutes(1);

    /**
     * 连接在池中保持空闲而不被驱逐的最小存活时间
     */
    private Duration minEvictableIdleTime = Duration.ofMinutes(5);

    public void setMaxTotal(int maxTotal) {
        this.maxTotal = maxTotal;
        this.configuredFields.add("maxTotal");
    }

    public void setMaxIdle(int maxIdle) {
        this.maxIdle = maxIdle;
        this.configuredFields.add("maxIdle");
    }

    public void setMinIdle(int minIdle) {
        this.minIdle = minIdle;
        this.configuredFields.add("minIdle");
    }

    public void setMaxWait(Duration maxWait) {
        this.maxWait = maxWait;
        this.configuredFields.add("maxWait");
    }

    public void setTestOnBorrow(boolean testOnBorrow) {
        this.testOnBorrow = testOnBorrow;
        this.configuredFields.add("testOnBorrow");
    }

    public void setTestOnReturn(boolean testOnReturn) {
        this.testOnReturn = testOnReturn;
        this.configuredFields.add("testOnReturn");
    }

    public void setTestWhileIdle(boolean testWhileIdle) {
        this.testWhileIdle = testWhileIdle;
        this.configuredFields.add("testWhileIdle");
    }

    public void setTimeBetweenEvictionRuns(Duration timeBetweenEvictionRuns) {
        this.timeBetweenEvictionRuns = timeBetweenEvictionRuns;
        this.configuredFields.add("timeBetweenEvictionRuns");
    }

    public void setMinEvictableIdleTime(Duration minEvictableIdleTime) {
        this.minEvictableIdleTime = minEvictableIdleTime;
        this.configuredFields.add("minEvictableIdleTime");
    }

    /**
     * 创建当前连接池配置的深拷贝（隔离可变状态）
     *
     * @return 独立拷贝实例
     */
    public FtpPoolProperties copy() {
        FtpPoolProperties copy = new FtpPoolProperties();
        copy.maxTotal = this.maxTotal;
        copy.maxIdle = this.maxIdle;
        copy.minIdle = this.minIdle;
        copy.maxWait = this.maxWait;
        copy.testOnBorrow = this.testOnBorrow;
        copy.testOnReturn = this.testOnReturn;
        copy.testWhileIdle = this.testWhileIdle;
        copy.timeBetweenEvictionRuns = this.timeBetweenEvictionRuns;
        copy.minEvictableIdleTime = this.minEvictableIdleTime;
        copy.configuredFields.addAll(this.configuredFields);
        return copy;
    }

    /**
     * 从父级默认连接池配置继承当前实例未显式设置的属性（绝不覆盖显式配置）
     *
     * @param defaults 父级默认连接池配置
     */
    public void mergeFrom(FtpPoolProperties defaults) {
        if (defaults == null) {
            return;
        }
        this.maxTotal = pick("maxTotal", this.maxTotal, defaults.maxTotal);
        this.maxIdle = pick("maxIdle", this.maxIdle, defaults.maxIdle);
        this.minIdle = pick("minIdle", this.minIdle, defaults.minIdle);
        this.maxWait = pick("maxWait", this.maxWait, defaults.maxWait);
        this.testOnBorrow = pick("testOnBorrow", this.testOnBorrow, defaults.testOnBorrow);
        this.testOnReturn = pick("testOnReturn", this.testOnReturn, defaults.testOnReturn);
        this.testWhileIdle = pick("testWhileIdle", this.testWhileIdle, defaults.testWhileIdle);
        this.timeBetweenEvictionRuns = pick("timeBetweenEvictionRuns", this.timeBetweenEvictionRuns, defaults.timeBetweenEvictionRuns);
        this.minEvictableIdleTime = pick("minEvictableIdleTime", this.minEvictableIdleTime, defaults.minEvictableIdleTime);
    }

    private <T> T pick(String key, T current, T parent) {
        return configuredFields.contains(key) ? current : parent;
    }
}
