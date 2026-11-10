package io.github.atengk.boot.sample.rocketmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.rocketmq.dto.*;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.service.SimpleConsumerPullScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * RocketMQ SimpleConsumer 流式微批拉取与租约控制场景控制器
 *
 * <p>提供微批流式拉取、动态租约续期、显式 ACK 确认、高阶闭包消费与租约句柄审计端点。</p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/rocketmq/simple-consumer")
@RequiredArgsConstructor
public class RocketmqSimpleConsumerController {

    private final SimpleConsumerPullScenarioService simpleConsumerPullScenarioService;

    /**
     * 长轮询微批流式拉取消息并缓存句柄
     *
     * @param request 拉取参数（可选）
     * @return 消息 VO 列表
     */
    @PostMapping("/pull")
    public Result<List<SimpleMessageVO>> pullMessages(@Valid @RequestBody(required = false) SimplePullRequest request) {
        SimplePullRequest req = request != null ? request : new SimplePullRequest();
        List<MessageView> messages = simpleConsumerPullScenarioService.pullMessages(
                req.resolveTopic(),
                req.resolveGroup(),
                req.resolveTag(),
                req.resolveMaxNum(),
                req.resolveInvisibleDuration()
        );
        List<SimpleMessageVO> vos = new ArrayList<>(messages.size());
        for (MessageView mv : messages) {
            vos.add(SimpleConsumerPullScenarioService.convertToVO(mv));
        }
        return Result.success(vos);
    }

    /**
     * 对指定待决消息动态续期不可见租约
     *
     * @param request 续期请求参数
     * @return 续期结果说明
     */
    @PostMapping("/renew")
    public Result<String> renewLease(@Valid @RequestBody SimpleRenewRequest request) {
        simpleConsumerPullScenarioService.renewLease(
                request.getGroup(),
                request.getMessageId(),
                request.resolveDuration()
        );
        return Result.success("租约续期成功: messageId=" + request.getMessageId()
                + ", invisibleSeconds=" + request.getInvisibleSeconds() + "s");
    }

    /**
     * 对指定消息显式 ACK 确认出队并释放句柄
     *
     * @param request 确认请求参数
     * @return 确认结果说明
     */
    @PostMapping("/ack")
    public Result<String> acknowledge(@Valid @RequestBody SimpleAckRequest request) {
        simpleConsumerPullScenarioService.acknowledge(request.getGroup(), request.getMessageId());
        return Result.success("消息确认出队成功: messageId=" + request.getMessageId());
    }

    /**
     * 高阶函数式闭包一步拉取、推断反序列化、消费执行与自动确认
     *
     * @param request 拉取与闭包消费参数（可选）
     * @return 成功消费的消息数量
     */
    @PostMapping("/consume")
    public Result<Integer> consumeAndExecute(@Valid @RequestBody(required = false) SimplePullRequest request) {
        SimplePullRequest req = request != null ? request : new SimplePullRequest();
        int executed = simpleConsumerPullScenarioService.pullAndExecuteOrders(
                req.resolveTopic(),
                req.resolveGroup(),
                req.resolveTag(),
                req.resolveMaxNum(),
                req.resolveInvisibleDuration()
        );
        return Result.success(executed);
    }

    /**
     * 审计当前内存中缓存的待决租约句柄状态
     *
     * @return 租约句柄审计视图
     */
    @GetMapping("/cached-views")
    public Result<SimpleLeaseStatusVO> getCachedViews() {
        List<SimpleConsumerPullScenarioService.CachedMessageHandle> handles =
                simpleConsumerPullScenarioService.getAllCachedHandles();
        List<SimpleMessageVO> vos = new ArrayList<>(handles.size());
        for (SimpleConsumerPullScenarioService.CachedMessageHandle handle : handles) {
            vos.add(SimpleConsumerPullScenarioService.convertToVO(handle.messageView()));
        }
        SimpleLeaseStatusVO status = SimpleLeaseStatusVO.builder()
                .cachedCount(vos.size())
                .messages(vos)
                .build();
        return Result.success(status);
    }

    /**
     * 查询函数式闭包消费接收到的订单数据
     *
     * @return 已消费订单列表
     */
    @GetMapping("/consumed")
    public Result<List<OrderMessage>> getConsumedOrders() {
        return Result.success(simpleConsumerPullScenarioService.getConsumedOrders());
    }

    /**
     * 清理所有待决租约句柄缓存与闭包消费记录
     *
     * @return 清理结果
     */
    @DeleteMapping("/clear")
    public Result<String> clear() {
        simpleConsumerPullScenarioService.clearCache();
        return Result.success("SimpleConsumer 句柄缓存与消费记录已清空");
    }
}
