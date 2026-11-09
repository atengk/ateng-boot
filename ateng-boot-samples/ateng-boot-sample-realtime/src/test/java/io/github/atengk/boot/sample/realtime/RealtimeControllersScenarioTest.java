package io.github.atengk.boot.sample.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.realtime.dto.PushBroadcastRequest;
import io.github.atengk.boot.sample.realtime.dto.PushGroupMessageRequest;
import io.github.atengk.boot.sample.realtime.dto.PushUserMessageRequest;
import io.github.atengk.boot.sample.realtime.dto.SendOfflineMessageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 场景 7：6 大领域 Controller 端到端 HTTP 契约与 JSR-303 双防校验实测
 *
 * @author Ateng
 * @since 2026-11-09
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("场景 7: 6 大领域 Controller 端到端 HTTP 契约与校验测试")
class RealtimeControllersScenarioTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("PushController: 单播/定向/广播与 JSR-303 防御测试")
    void testPushController() throws Exception {
        // 1. 正常单播请求
        PushUserMessageRequest validReq = PushUserMessageRequest.builder()
                .userId("user-ctrl-01")
                .event("notice:order")
                .content("Order #1001 paid")
                .deviceType("WEB")
                .clientId("tab-01")
                .build();

        mockMvc.perform(post("/sample/realtime/push/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 2. JSR-303 校验拦截 (userId 为空，全局异常转译为 HTTP 200 + Code 400)
        PushUserMessageRequest invalidReq = PushUserMessageRequest.builder()
                .userId("")
                .event("notice:order")
                .content("Order #1001 paid")
                .build();

        mockMvc.perform(post("/sample/realtime/push/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        // 3. WebSocket 专线单播
        mockMvc.perform(post("/sample/realtime/push/websocket")
                        .param("userId", "user-ctrl-01")
                        .param("clientId", "ws-tab-01")
                        .param("message", "Hello WS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 4. SSE 专线单播
        mockMvc.perform(post("/sample/realtime/push/sse")
                        .param("userId", "user-ctrl-01")
                        .param("clientId", "sse-tab-01")
                        .param("message", "Hello SSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 租户组播
        mockMvc.perform(post("/sample/realtime/push/tenant")
                        .param("tenantId", "1001")
                        .param("message", "Tenant Announcement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 6. 全服广播
        PushBroadcastRequest broadcastReq = PushBroadcastRequest.builder()
                .event("system:broadcast")
                .content("System Maintenance")
                .build();

        mockMvc.perform(post("/sample/realtime/push/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(broadcastReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("GroupController: 组播/加群/退群/解散/成员列表测试")
    void testGroupController() throws Exception {
        String groupId = "group-ctrl-room-01";
        String userId = "user-ctrl-02";

        // 1. 加入群组
        mockMvc.perform(post("/sample/realtime/group/join")
                        .param("groupId", groupId)
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 2. 群组组播
        PushGroupMessageRequest groupMsgReq = PushGroupMessageRequest.builder()
                .groupId(groupId)
                .event("meeting:message")
                .content("Welcome to meeting")
                .build();

        mockMvc.perform(post("/sample/realtime/group/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupMsgReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3. 查询群组成员与统计
        mockMvc.perform(get("/sample/realtime/group/members")
                        .param("groupId", groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.groupId").value(groupId));

        // 4. 退出群组
        mockMvc.perform(post("/sample/realtime/group/leave")
                        .param("groupId", groupId)
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 解散群组
        mockMvc.perform(post("/sample/realtime/group/dismiss")
                        .param("groupId", groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("SessionController: 度量指标/用户在线状态/下线与租户驱逐测试")
    void testSessionController() throws Exception {
        // 1. 本地连接指标
        mockMvc.perform(get("/sample/realtime/session/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.onlineCount").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.data.onlineUserCount").value(greaterThanOrEqualTo(0)));

        // 2. 集群连接指标
        mockMvc.perform(get("/sample/realtime/session/cluster-metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(greaterThanOrEqualTo(0)));

        // 3. 用户在线检测
        mockMvc.perform(get("/sample/realtime/session/user-online")
                        .param("userId", "user-ctrl-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 4. 驱逐用户
        mockMvc.perform(post("/sample/realtime/session/kickout-user")
                        .param("userId", "user-ctrl-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 驱逐租户
        mockMvc.perform(post("/sample/realtime/session/kickout-tenant")
                        .param("tenantId", "1002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("OfflineController: 持久化离线消息/计数/拉取清空/删除测试")
    void testOfflineController() throws Exception {
        String userId = "user-ctrl-offline-01";

        // 1. 发送持久化离线消息
        SendOfflineMessageRequest offlineReq = SendOfflineMessageRequest.builder()
                .userId(userId)
                .event("alert:unread")
                .content("Server high load")
                .build();

        mockMvc.perform(post("/sample/realtime/offline/send-persistent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(offlineReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 2. 查询离线消息计数
        mockMvc.perform(get("/sample/realtime/offline/count")
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(greaterThanOrEqualTo(0)));

        // 3. 只读窥探离线消息列表 (保持 GET 幂等)
        mockMvc.perform(get("/sample/realtime/offline/messages")
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 4. 一次性拉取并排空 (POST 语义)
        mockMvc.perform(post("/sample/realtime/offline/pull")
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 5. 清空离线消息箱
        mockMvc.perform(delete("/sample/realtime/offline/messages")
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("SseStreamController: LLM 打字机异步流式推流响应测试")
    void testSseStreamController() throws Exception {
        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(get("/sample/realtime/sse/stream-llm")
                        .param("prompt", "Test streaming")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(mvcResult.getRequest().isAsyncStarted()).isTrue();
    }

    @Test
    @DisplayName("AuthController: Sa-Token 登录签发/注销/踢退/封禁联动测试")
    void testAuthController() throws Exception {
        String userId = "user-ctrl-auth-01";

        // 1. 模拟登录获取凭据
        mockMvc.perform(post("/sample/realtime/auth/login")
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isString());

        // 2. 主动注销
        mockMvc.perform(post("/sample/realtime/auth/logout")
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3. 强制踢退
        mockMvc.perform(post("/sample/realtime/auth/kickout")
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 4. 账号封禁
        mockMvc.perform(post("/sample/realtime/auth/disable")
                        .param("userId", userId)
                        .param("disableTime", "3600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
