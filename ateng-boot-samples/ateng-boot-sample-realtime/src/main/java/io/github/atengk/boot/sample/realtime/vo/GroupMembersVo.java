package io.github.atengk.boot.sample.realtime.vo;

import io.github.atengk.boot.realtime.model.SessionKey;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Set;

/**
 * 分布式群组成员与度量详情视图对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupMembersVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 群组/房间唯一标识
     */
    private String groupId;

    /**
     * 全域集群该群组在线总成员数
     */
    private Integer clusterCount;

    /**
     * 全域集群该群组所有成员会话三维标识集合
     */
    private Set<SessionKey> clusterMembers;

    /**
     * 当前服务节点该群组本地在线成员数
     */
    private Integer localCount;

    /**
     * 当前服务节点该群组本地在线成员会话集合
     */
    private Set<SessionKey> localMembers;
}
