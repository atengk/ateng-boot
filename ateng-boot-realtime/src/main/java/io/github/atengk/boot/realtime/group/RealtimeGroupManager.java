package io.github.atengk.boot.realtime.group;

import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时通信客户端单机会话分组与频道订阅受管注册表
 * <p>
 * 基于无锁高并发 ConcurrentHashMap 维护房间/群组与会话的双向映射关系，
 * 实现 RealtimeSessionListener 接口，在会话断开时自动确定性排空名下所属分组，彻底杜绝内存句柄泄漏。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class RealtimeGroupManager implements RealtimeSessionListener {

    /**
     * 房间/群组正向索引: groupId -> Set<SessionKey>
     */
    private final ConcurrentHashMap<String, Set<SessionKey>> groupSessions = new ConcurrentHashMap<>();

    /**
     * 会话所属群组反向索引: SessionKey -> Set<groupId>
     */
    private final ConcurrentHashMap<SessionKey, Set<String>> sessionGroups = new ConcurrentHashMap<>();

    /**
     * 将指定会话加入指定分组/房间
     *
     * @param groupId    群组标识
     * @param sessionKey 会话坐标
     * @return 是否成功加入（若原本已在群组中返回 false）
     */
    public boolean joinGroup(String groupId, SessionKey sessionKey) {
        if (groupId == null || groupId.isBlank() || sessionKey == null) {
            return false;
        }
        String cleanGroupId = groupId.trim();
        boolean added = groupSessions.computeIfAbsent(cleanGroupId, k -> ConcurrentHashMap.newKeySet()).add(sessionKey);
        sessionGroups.computeIfAbsent(sessionKey, k -> ConcurrentHashMap.newKeySet()).add(cleanGroupId);
        if (added) {
            log.debug("[Realtime] 会话加入分组: groupId={}, sessionKey={}", cleanGroupId, sessionKey);
        }
        return added;
    }

    /**
     * 将指定会话移出指定分组/房间
     *
     * @param groupId    群组标识
     * @param sessionKey 会话坐标
     * @return 是否成功移出
     */
    public boolean leaveGroup(String groupId, SessionKey sessionKey) {
        if (groupId == null || groupId.isBlank() || sessionKey == null) {
            return false;
        }
        String cleanGroupId = groupId.trim();
        Set<SessionKey> sessions = groupSessions.get(cleanGroupId);
        boolean removed = false;
        if (sessions != null) {
            removed = sessions.remove(sessionKey);
            if (sessions.isEmpty()) {
                groupSessions.remove(cleanGroupId, Collections.emptySet());
            }
        }
        Set<String> groups = sessionGroups.get(sessionKey);
        if (groups != null) {
            groups.remove(cleanGroupId);
            if (groups.isEmpty()) {
                sessionGroups.remove(sessionKey, Collections.emptySet());
            }
        }
        if (removed) {
            log.debug("[Realtime] 会话离开分组: groupId={}, sessionKey={}", cleanGroupId, sessionKey);
        }
        return removed;
    }

    /**
     * 将指定会话从其加入的所有分组中一次性注销移出
     *
     * @param sessionKey 会话坐标
     * @return 注销离开的分组集合
     */
    public Set<String> leaveAllGroups(SessionKey sessionKey) {
        if (sessionKey == null) {
            return Collections.emptySet();
        }
        Set<String> groups = sessionGroups.remove(sessionKey);
        if (groups == null || groups.isEmpty()) {
            return Collections.emptySet();
        }
        for (String groupId : groups) {
            Set<SessionKey> members = groupSessions.get(groupId);
            if (members != null) {
                members.remove(sessionKey);
                if (members.isEmpty()) {
                    groupSessions.remove(groupId, Collections.emptySet());
                }
            }
        }
        log.debug("[Realtime] 会话排空所属全量分组: sessionKey={}, groupCount={}", sessionKey, groups.size());
        return Collections.unmodifiableSet(groups);
    }

    /**
     * 获取指定群组名下的所有会话坐标
     *
     * @param groupId 群组标识
     * @return 会话坐标集合（空安全防御，绝不返回 null）
     */
    public Set<SessionKey> getGroupSessions(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return Collections.emptySet();
        }
        Set<SessionKey> members = groupSessions.get(groupId.trim());
        if (members == null || members.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(members));
    }

    /**
     * 获取指定群组的成员在线连接数
     *
     * @param groupId 群组标识
     * @return 成员连接数
     */
    public int getGroupMemberCount(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return 0;
        }
        Set<SessionKey> members = groupSessions.get(groupId.trim());
        return members != null ? members.size() : 0;
    }

    /**
     * 获取指定会话已加入的所有分组标识集合
     *
     * @param sessionKey 会话坐标
     * @return 分组标识集合
     */
    public Set<String> getSessionGroups(SessionKey sessionKey) {
        if (sessionKey == null) {
            return Collections.emptySet();
        }
        Set<String> groups = sessionGroups.get(sessionKey);
        if (groups == null || groups.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(groups));
    }

    /**
     * 获取当前节点全量活跃分组标识集合
     *
     * @return 全量分组集合
     */
    public Set<String> getAllGroupIds() {
        if (groupSessions.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(groupSessions.keySet()));
    }

    /**
     * 解散指定群组/房间，移出本节点名下所有成员会话
     *
     * @param groupId 群组标识
     * @return 被移出的群组成员会话坐标集合（空安全防御，绝不返回 null）
     */
    public Set<SessionKey> dismissGroup(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return Collections.emptySet();
        }
        String cleanGroupId = groupId.trim();
        Set<SessionKey> members = groupSessions.remove(cleanGroupId);
        if (members == null || members.isEmpty()) {
            return Collections.emptySet();
        }
        for (SessionKey sessionKey : members) {
            Set<String> groups = sessionGroups.get(sessionKey);
            if (groups != null) {
                groups.remove(cleanGroupId);
                if (groups.isEmpty()) {
                    sessionGroups.remove(sessionKey, Collections.emptySet());
                }
            }
        }
        log.debug("[Realtime] 本地解散分组: groupId={}, memberCount={}", cleanGroupId, members.size());
        return Collections.unmodifiableSet(new HashSet<>(members));
    }

    /**
     * 获取全集群指定群组的在线成员总数（单机保底实现，返回当前单机节点数量）
     *
     * @param groupId 群组标识
     * @return 成员连接总数
     */
    public int getClusterGroupMemberCount(String groupId) {
        return getGroupMemberCount(groupId);
    }

    /**
     * 获取全集群指定群组名下的所有会话坐标集合（单机保底实现，返回当前单机节点成员）
     *
     * @param groupId 群组标识
     * @return 会话坐标集合（空安全防御，绝不返回 null）
     */
    public Set<SessionKey> getClusterGroupMembers(String groupId) {
        return getGroupSessions(groupId);
    }

    /**
     * 清空全量分组信息
     */
    public void clear() {
        groupSessions.clear();
        sessionGroups.clear();
    }

    // ==================== RealtimeSessionListener 联动清理 ====================

    @Override
    public void onSessionClosed(RealtimeSession session) {
        if (session != null) {
            leaveAllGroups(session.getSessionKey());
        }
    }

    @Override
    public void onSessionClosed(RealtimeSession session, CloseStatus status) {
        onSessionClosed(session);
    }
}
