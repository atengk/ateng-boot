package io.github.atengk.boot.realtime.group;

import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.*;

/**
 * 基于 Redisson 的分布式双向 Set 群组与频道会话管理器
 * <p>
 * 继承 {@link RealtimeGroupManager} 单机内存路由能力，并基于 Redis 双向 Set 键结构实现跨节点全域感知：
 * <ul>
 *     <li>正向成员索引：{@code ateng:realtime:cluster:group:{groupId}} 存储 {@code SessionKey.format()}，支持快速检索全域成员与 O(1) 人数统计；</li>
 *     <li>反向所属索引：{@code ateng:realtime:cluster:session:{sessionKey}:groups} 存储 {@code groupId}，在会话注销或故障时实现级联清场；</li>
 * </ul>
 * 完备支持全集群房间实时人数统计、跨节点成员坐标感知与原子群组解散。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
public class RedissonRealtimeGroupManager extends RealtimeGroupManager {

    private final RedissonClient redissonClient;
    private final RealtimeProperties properties;

    public RedissonRealtimeGroupManager(RedissonClient redissonClient, RealtimeProperties properties) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        log.info("[Realtime] Redisson 分布式群组管理器已就绪 (Distributed Group Manager Enabled)");
    }

    @Override
    public boolean joinGroup(String groupId, SessionKey sessionKey) {
        if (groupId == null || groupId.isBlank() || sessionKey == null) {
            return false;
        }
        String cleanGroupId = groupId.trim();
        boolean added = super.joinGroup(cleanGroupId, sessionKey);
        try {
            String formattedKey = sessionKey.format();
            RSet<String> groupSet = redissonClient.getSet(getGroupKey(cleanGroupId), StringCodec.INSTANCE);
            groupSet.add(formattedKey);

            RSet<String> sessionGroupsSet = redissonClient.getSet(getSessionGroupsKey(sessionKey), StringCodec.INSTANCE);
            sessionGroupsSet.add(cleanGroupId);
            log.debug("[Realtime-Cluster] 会话成功加入分布式群组: groupId={}, sessionKey={}", cleanGroupId, formattedKey);
        } catch (Exception e) {
            log.warn("[Realtime-Cluster] Redis 记录群组成员异常: groupId={}, sessionKey={}, error={}",
                    cleanGroupId, sessionKey, e.getMessage());
        }
        return added;
    }

    @Override
    public boolean leaveGroup(String groupId, SessionKey sessionKey) {
        if (groupId == null || groupId.isBlank() || sessionKey == null) {
            return false;
        }
        String cleanGroupId = groupId.trim();
        boolean removed = super.leaveGroup(cleanGroupId, sessionKey);
        try {
            String formattedKey = sessionKey.format();
            RSet<String> groupSet = redissonClient.getSet(getGroupKey(cleanGroupId), StringCodec.INSTANCE);
            groupSet.remove(formattedKey);

            RSet<String> sessionGroupsSet = redissonClient.getSet(getSessionGroupsKey(sessionKey), StringCodec.INSTANCE);
            sessionGroupsSet.remove(cleanGroupId);
            log.debug("[Realtime-Cluster] 会话成功退出分布式群组: groupId={}, sessionKey={}", cleanGroupId, formattedKey);
        } catch (Exception e) {
            log.warn("[Realtime-Cluster] Redis 移除群组成员异常: groupId={}, sessionKey={}, error={}",
                    cleanGroupId, sessionKey, e.getMessage());
        }
        return removed;
    }

    @Override
    public Set<String> leaveAllGroups(SessionKey sessionKey) {
        if (sessionKey == null) {
            return Collections.emptySet();
        }
        Set<String> localGroups = super.leaveAllGroups(sessionKey);
        try {
            RSet<String> sessionGroupsSet = redissonClient.getSet(getSessionGroupsKey(sessionKey), StringCodec.INSTANCE);
            Set<String> redisGroups = sessionGroupsSet.readAll();
            Set<String> allGroups = new HashSet<>(localGroups);
            if (redisGroups != null && !redisGroups.isEmpty()) {
                allGroups.addAll(redisGroups);
            }
            String formattedKey = sessionKey.format();
            for (String gid : allGroups) {
                try {
                    RSet<String> groupSet = redissonClient.getSet(getGroupKey(gid), StringCodec.INSTANCE);
                    groupSet.remove(formattedKey);
                } catch (Exception ex) {
                    log.warn("[Realtime-Cluster] Redis 移出会话群组关联异常: groupId={}, sessionKey={}, error={}",
                            gid, sessionKey, ex.getMessage());
                }
            }
            sessionGroupsSet.delete();
            log.debug("[Realtime-Cluster] 会话成功排空分布式全量群组: sessionKey={}, groupCount={}",
                    sessionKey, allGroups.size());
        } catch (Exception e) {
            log.warn("[Realtime-Cluster] Redis 排空会话全量群组异常: sessionKey={}, error={}", sessionKey, e.getMessage());
        }
        return localGroups;
    }

    @Override
    public int getClusterGroupMemberCount(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return 0;
        }
        try {
            RSet<String> groupSet = redissonClient.getSet(getGroupKey(groupId.trim()), StringCodec.INSTANCE);
            return groupSet.size();
        } catch (Exception e) {
            log.warn("[Realtime-Cluster] 查询集群群组成员总数异常: groupId={}, error={}", groupId, e.getMessage());
            return super.getGroupMemberCount(groupId);
        }
    }

    @Override
    public Set<SessionKey> getClusterGroupMembers(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return Collections.emptySet();
        }
        try {
            RSet<String> groupSet = redissonClient.getSet(getGroupKey(groupId.trim()), StringCodec.INSTANCE);
            Set<String> members = groupSet.readAll();
            if (members == null || members.isEmpty()) {
                return Collections.emptySet();
            }
            Set<SessionKey> result = new HashSet<>(members.size());
            for (String m : members) {
                try {
                    result.add(SessionKey.parse(m));
                } catch (Exception ex) {
                    log.warn("[Realtime-Cluster] 解析集群成员 SessionKey 失败: member={}, error={}", m, ex.getMessage());
                }
            }
            return Collections.unmodifiableSet(result);
        } catch (Exception e) {
            log.warn("[Realtime-Cluster] 查询集群群组成员列表异常: groupId={}, error={}", groupId, e.getMessage());
            return super.getGroupSessions(groupId);
        }
    }

    @Override
    public Set<SessionKey> dismissGroup(String groupId) {
        Set<SessionKey> localMembers = super.dismissGroup(groupId);
        if (groupId == null || groupId.isBlank()) {
            return localMembers;
        }
        String cleanGroupId = groupId.trim();
        try {
            RSet<String> groupSet = redissonClient.getSet(getGroupKey(cleanGroupId), StringCodec.INSTANCE);
            Set<String> members = groupSet.readAll();
            if (members != null && !members.isEmpty()) {
                for (String memberStr : members) {
                    try {
                        SessionKey key = SessionKey.parse(memberStr);
                        RSet<String> sessionGroupsSet = redissonClient.getSet(getSessionGroupsKey(key), StringCodec.INSTANCE);
                        sessionGroupsSet.remove(cleanGroupId);
                    } catch (Exception ex) {
                        log.warn("[Realtime-Cluster] 移除会话群组反向索引异常: member={}, error={}", memberStr, ex.getMessage());
                    }
                }
            }
            groupSet.delete();
            log.info("[Realtime-Cluster] Redis 集群解散群组成功: groupId={}, clusterMemberCount={}",
                    cleanGroupId, members != null ? members.size() : 0);
        } catch (Exception e) {
            log.warn("[Realtime-Cluster] Redis 解散群组异常: groupId={}, error={}", cleanGroupId, e.getMessage());
        }
        return localMembers;
    }

    /**
     * 获取指定群组在 Redis 中的正向成员 Set 键名
     *
     * @param groupId 群组标识
     * @return Redis 键名
     */
    public String getGroupKey(String groupId) {
        String prefix = (properties != null && properties.getCluster() != null && properties.getCluster().getGroupKeyPrefix() != null)
                ? properties.getCluster().getGroupKeyPrefix()
                : "ateng:realtime:cluster:group:";
        return prefix + groupId;
    }

    /**
     * 获取指定会话在 Redis 中的反向所属群组 Set 键名
     *
     * @param sessionKey 会话坐标
     * @return Redis 键名
     */
    public String getSessionGroupsKey(SessionKey sessionKey) {
        String prefix = (properties != null && properties.getCluster() != null && properties.getCluster().getSessionGroupsKeyPrefix() != null)
                ? properties.getCluster().getSessionGroupsKeyPrefix()
                : "ateng:realtime:cluster:session:";
        return prefix + sessionKey.format() + ":groups";
    }
}
