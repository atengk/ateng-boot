package io.github.atengk.boot.redis.cache.twolevel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 两级缓存跨节点广播失效载荷
 * 封装目标 cacheName、缓存 key、发起方实例唯一 instanceId 与全量清空标记，杜绝自重入死循环
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheEvictMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标缓存空间名称
     */
    private String cacheName;

    /**
     * 缓存键（如果 clearAll 为 true，则此字段可为 null）
     */
    private Object key;

    /**
     * 发起方实例唯一标识（用于接收端比对并忽略自身发出的事件，杜绝死循环）
     */
    private String instanceId;

    /**
     * 是否全量清空该 cacheName 下的缓存
     */
    private boolean clearAll;
}
