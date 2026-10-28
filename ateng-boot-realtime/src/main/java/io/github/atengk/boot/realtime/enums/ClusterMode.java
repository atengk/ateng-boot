package io.github.atengk.boot.realtime.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 实时通信底座集群协作模式枚举
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Getter
@RequiredArgsConstructor
public enum ClusterMode {

    /**
     * 自动探测模式：当容器存在 RedissonClient 时自动无缝升级为 Redis Pub/Sub 集群广播，否则纯内存单机运行
     */
    AUTO("auto", "自动探测模式"),

    /**
     * 强制单机模式：始终使用纯内存单机注册表，禁用跨节点广播
     */
    STANDALONE("standalone", "强制单机模式");

    private final String code;
    private final String description;
}
