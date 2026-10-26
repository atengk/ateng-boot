package io.github.atengk.boot.core.code;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务状态码标准号段划分规约枚举
 * 按照千位递增划分各领域与垂直 Starter 的错误码区间，消除跨中间件与微服务间的状态码碰撞与口径漂移
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Getter
@AllArgsConstructor
public enum ResultCodeSegment {

    /**
     * 系统与核心底座异常号段（1000 ~ 1999）
     * 覆盖通用参数错误、未知异常、业务断言中断、流控重试耗尽与表达式解析失败等底层核心场景
     */
    SYSTEM_CORE(1000, 1999, "系统与核心底座"),

    /**
     * 认证鉴权与安全凭据号段（2000 ~ 2999）
     * 覆盖未登录、无权限、令牌过期、会话踢出、接口签名防重放失败等安全场景
     */
    AUTH_SECURITY(2000, 2999, "认证鉴权与安全凭据"),

    /**
     * 对象存储与文件传输号段（3000 ~ 3999）
     * 覆盖文件不存在、文件超限、文件类型非法、分片上传中断、FTP/SFTP 连接断开等存储场景
     */
    STORAGE_FILE(3000, 3999, "对象存储与文件传输"),

    /**
     * 网络通信与消息投递号段（4000 ~ 4999）
     * 覆盖 HTTP/TCP 连接超时、消息队列投递失败、毒丸消费中断、RPC 远程通信异常等通信场景
     */
    NETWORK_RPC(4000, 4999, "网络通信与消息投递"),

    /**
     * 数据持久化与分布式事务号段（5000 ~ 5999）
     * 覆盖数据记录不存在、唯一索引冲突、乐观锁更新失败、租户跨权访问等持久层场景
     */
    PERSISTENCE_TX(5000, 5999, "数据持久化与分布式事务"),

    /**
     * 业务系统与垂直领域扩展预留号段（6000 ~ 9999）
     * 留给具体业务中台、垂直微服务或业务 Starter 自由扩展
     */
    BUSINESS_RESERVED(6000, 9999, "业务系统与垂直领域扩展预留");

    /**
     * 号段起始边界（闭区间）
     */
    private final int minCode;

    /**
     * 号段结束边界（闭区间）
     */
    private final int maxCode;

    /**
     * 领域说明描述
     */
    private final String description;

    /**
     * 判断指定数值状态码是否位于当前号段区间内（闭区间）
     *
     * @param code 状态码数值
     * @return 若在当前号段内返回 true，否则返回 false
     */
    public boolean contains(int code) {
        return code >= minCode && code <= maxCode;
    }

    /**
     * 根据数值状态码反查归属的标准领域号段
     *
     * @param code 状态码数值
     * @return 对应的号段枚举；若不属于任何预定号段则返回 null
     */
    public static ResultCodeSegment of(int code) {
        for (ResultCodeSegment segment : values()) {
            if (segment.contains(code)) {
                return segment;
            }
        }
        return null;
    }

    /**
     * 根据 IResultCode 契约反查归属的标准领域号段
     *
     * @param resultCode 状态码对象（非空且其 getCode() 非空）
     * @return 对应的号段枚举；若无归属号段或入参不合法则返回 null
     */
    public static ResultCodeSegment of(IResultCode resultCode) {
        if (resultCode == null || resultCode.getCode() == null) {
            return null;
        }
        return of(resultCode.getCode());
    }
}
