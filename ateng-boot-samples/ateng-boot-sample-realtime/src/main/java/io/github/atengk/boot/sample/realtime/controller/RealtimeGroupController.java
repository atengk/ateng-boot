package io.github.atengk.boot.sample.realtime.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import io.github.atengk.boot.sample.realtime.dto.PushGroupMessageRequest;
import io.github.atengk.boot.sample.realtime.vo.GroupMembersVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 分布式群组协同与房间组播控制器
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/realtime/group")
@RequiredArgsConstructor
public class RealtimeGroupController {

    private final RealtimeTemplate realtimeTemplate;

    /**
     * 向指定房间/群组组播下发消息
     */
    @PostMapping("/send")
    public Result<Integer> sendToGroup(@Valid @RequestBody PushGroupMessageRequest request) {
        log.info("[Sample] 触发群组组播: groupId={}, event={}", request.getGroupId(), request.getEvent());
        int count = realtimeTemplate.sendToGroup(request.getGroupId(),
                RealtimeMessage.of(request.getEvent(), request.getContent()));
        return Result.success(count);
    }

    /**
     * 向指定房间/群组组播下发消息并排除请求中的指定会话（如聊天室广播不回显发送方自身）
     */
    @PostMapping("/send-except")
    public Result<Integer> sendToGroupExcept(@Valid @RequestBody PushGroupMessageRequest request) {
        log.info("[Sample] 触发群组排除会话组播: groupId={}, event={}, excludeCount={}",
                request.getGroupId(), request.getEvent(),
                request.getExcludeSessionKeys() != null ? request.getExcludeSessionKeys().size() : 0);
        List<SessionKey> excludeKeys = parseSessionKeys(request.getExcludeSessionKeys());
        int count = realtimeTemplate.sendToGroupExcept(request.getGroupId(),
                RealtimeMessage.of(request.getEvent(), request.getContent()), excludeKeys);
        return Result.success(count);
    }

    private List<SessionKey> parseSessionKeys(List<String> rawKeys) {
        if (CollectionUtils.isEmpty(rawKeys)) {
            return Collections.emptyList();
        }
        List<SessionKey> keys = new ArrayList<>(rawKeys.size());
        for (String raw : rawKeys) {
            if (StringUtils.hasText(raw)) {
                try {
                    keys.add(SessionKey.parse(raw.trim()));
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(CommonResultCode.BAD_REQUEST,
                            "会话坐标格式非法，需符合 userId:deviceType:clientId 规范: " + raw, e);
                }
            }
        }
        return keys;
    }

    /**
     * 将指定用户的所有在线会话加入群组
     */
    @PostMapping("/join")
    public Result<Void> joinGroup(@RequestParam String groupId, @RequestParam String userId) {
        log.info("[Sample] 用户加入群组: groupId={}, userId={}", groupId, userId);
        realtimeTemplate.joinGroup(groupId, userId);
        return Result.success();
    }

    /**
     * 将指定用户从群组中退出
     */
    @PostMapping("/leave")
    public Result<Void> leaveGroup(@RequestParam String groupId, @RequestParam String userId) {
        log.info("[Sample] 用户退出群组: groupId={}, userId={}", groupId, userId);
        realtimeTemplate.leaveGroup(groupId, userId);
        return Result.success();
    }

    /**
     * 解散房间群组（清空 Redis Set 双向索引，向全员广播解散通知帧并踢退）
     */
    @PostMapping("/dismiss")
    public Result<Integer> dismissGroup(@RequestParam String groupId) {
        log.info("[Sample] 解散群组: groupId={}", groupId);
        int localEjected = realtimeTemplate.dismissGroup(groupId);
        return Result.success(localEjected);
    }

    /**
     * 查询群组成员列表与统计（包含集群全域与本节点局部数据）
     */
    @GetMapping("/members")
    public Result<GroupMembersVo> getGroupMembers(@RequestParam String groupId) {
        int clusterCount = realtimeTemplate.getClusterGroupMemberCount(groupId);
        Set<SessionKey> clusterMembers = realtimeTemplate.getClusterGroupMembers(groupId);
        int localCount = realtimeTemplate.getGroupMemberCount(groupId);
        Set<SessionKey> localMembers = realtimeTemplate.getGroupSessions(groupId);

        GroupMembersVo vo = GroupMembersVo.builder()
                .groupId(groupId)
                .clusterCount(clusterCount)
                .clusterMembers(clusterMembers != null ? clusterMembers : Set.of())
                .localCount(localCount)
                .localMembers(localMembers != null ? localMembers : Set.of())
                .build();
        return Result.success(vo);
    }
}
