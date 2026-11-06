package io.github.atengk.boot.liteflow.component;

import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全谱系泛型受管组件基类体系与强类型传参辅助集成测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest(classes = LiteflowComponentHierarchyTest.TestConfig.class, properties = {
        "liteflow.rule-source=classpath:liteflow/hierarchy-flow.el.xml",
        "liteflow.enable=true"
})
@DisplayName("LiteFlow 全谱系泛型受管组件基类体系测试")
class LiteflowComponentHierarchyTest {

    @Autowired
    private LiteflowTemplate liteflowTemplate;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({
            SampleNode.class,
            BranchANode.class,
            BranchBNode.class,
            CounterNode.class,
            ItemNode.class,
            SampleSwitch.class,
            SampleIf.class,
            SampleFor.class,
            SampleWhile.class,
            SampleIterator.class,
            SampleBreak.class,
            DataAndTagNode.class,
            DataListNode.class,
            EndFlowNode.class,
            UnreachableNode.class,
            DelivererNode.class,
            ReceiverNode.class,
            SubChainCallerNode.class,
            SubChainTargetNode.class,
            RequestDataNode.class,
            FallbackIf.class,
            FailingIf.class,
            FallbackSwitch.class,
            FallbackFor.class,
            FallbackWhile.class,
            FallbackIterator.class,
            FallbackBreak.class
    })
    static class TestConfig {
    }

    /**
     * 测试强类型数据传输载体 Record
     */
    public record NodeCmpData(int limit, String role) {
    }

    /**
     * 业务上下文测试实体
     */
    public static class HierarchyTestContext {
        private final List<String> executedNodes = new ArrayList<>();
        private final List<String> collectedItems = new ArrayList<>();
        private String switchTarget = "branchA";
        private boolean ifCondition = true;
        private int forLoops = 3;
        private int whileCount = 3;
        private int breakThreshold = 4;
        private int counterExecutions = 0;
        private List<String> iteratorItems = List.of("apple", "banana", "cherry");
        private String capturedTag;
        private NodeCmpData capturedData;
        private List<String> capturedDataList;

        public List<String> getExecutedNodes() {
            return executedNodes;
        }

        public List<String> getCollectedItems() {
            return collectedItems;
        }

        public String getSwitchTarget() {
            return switchTarget;
        }

        public void setSwitchTarget(String switchTarget) {
            this.switchTarget = switchTarget;
        }

        public boolean isIfCondition() {
            return ifCondition;
        }

        public void setIfCondition(boolean ifCondition) {
            this.ifCondition = ifCondition;
        }

        public int getForLoops() {
            return forLoops;
        }

        public void setForLoops(int forLoops) {
            this.forLoops = forLoops;
        }

        public int getWhileCount() {
            return whileCount;
        }

        public void setWhileCount(int whileCount) {
            this.whileCount = whileCount;
        }

        public boolean decrementWhile() {
            if (whileCount > 0) {
                whileCount--;
                return true;
            }
            return false;
        }

        public int getBreakThreshold() {
            return breakThreshold;
        }

        public void setBreakThreshold(int breakThreshold) {
            this.breakThreshold = breakThreshold;
        }

        public int getCounterExecutions() {
            return counterExecutions;
        }

        public void incrementCounter() {
            this.counterExecutions++;
        }

        public List<String> getIteratorItems() {
            return iteratorItems;
        }

        public void setIteratorItems(List<String> iteratorItems) {
            this.iteratorItems = iteratorItems;
        }

        public String getCapturedTag() {
            return capturedTag;
        }

        public void setCapturedTag(String capturedTag) {
            this.capturedTag = capturedTag;
        }

        public NodeCmpData getCapturedData() {
            return capturedData;
        }

        public void setCapturedData(NodeCmpData capturedData) {
            this.capturedData = capturedData;
        }

        public List<String> getCapturedDataList() {
            return capturedDataList;
        }

        public void setCapturedDataList(List<String> capturedDataList) {
            this.capturedDataList = capturedDataList;
        }
    }

    // ==================== 1. BaseNodeComponent 测试 ====================

    @Component("sampleNode")
    public static class SampleNode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            context.getExecutedNodes().add("sampleNode");
        }
    }

    @Component("branchA")
    public static class BranchANode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            context.getExecutedNodes().add("branchA");
        }
    }

    @Component("branchB")
    public static class BranchBNode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            context.getExecutedNodes().add("branchB");
        }
    }

    @Component("counterNode")
    public static class CounterNode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            context.incrementCounter();
            context.getExecutedNodes().add("counter:" + context.getCounterExecutions());
        }
    }

    @Component("itemNode")
    public static class ItemNode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            String item = this.getCurrLoopObject(String.class);
            Integer loopIndex = this.getLoopIndex();
            context.getCollectedItems().add(item);
            context.getExecutedNodes().add("item:" + item + ":" + loopIndex);
        }
    }

    // ==================== 2. BaseSwitchComponent 测试 ====================

    @Component("sampleSwitch")
    public static class SampleSwitch extends BaseSwitchComponent<HierarchyTestContext> {
        @Override
        public String processSwitch(HierarchyTestContext context) {
            return context.getSwitchTarget();
        }
    }

    // ==================== 3. BaseIfComponent 测试 ====================

    @Component("sampleIf")
    public static class SampleIf extends BaseIfComponent<HierarchyTestContext> {
        @Override
        public boolean processIf(HierarchyTestContext context) {
            return context.isIfCondition();
        }
    }

    // ==================== 4. BaseForComponent 测试 ====================

    @Component("sampleFor")
    public static class SampleFor extends BaseForComponent<HierarchyTestContext> {
        @Override
        public int processFor(HierarchyTestContext context) {
            return context.getForLoops();
        }
    }

    // ==================== 5. BaseWhileComponent 测试 ====================

    @Component("sampleWhile")
    public static class SampleWhile extends BaseWhileComponent<HierarchyTestContext> {
        @Override
        public boolean processWhile(HierarchyTestContext context) {
            return context.decrementWhile();
        }
    }

    // ==================== 6. BaseIteratorComponent 测试 ====================

    @Component("sampleIterator")
    public static class SampleIterator extends BaseIteratorComponent<HierarchyTestContext> {
        @Override
        public Iterator<?> processIterator(HierarchyTestContext context) {
            return context.getIteratorItems().iterator();
        }
    }

    // ==================== 7. BaseBreakComponent 测试 ====================

    @Component("sampleBreak")
    public static class SampleBreak extends BaseBreakComponent<HierarchyTestContext> {
        @Override
        public boolean processBreak(HierarchyTestContext context) {
            return context.getCounterExecutions() >= context.getBreakThreshold();
        }
    }

    // ==================== 8. Tag & CmpData 测试组件 ====================

    @Component("dataAndTagNode")
    public static class DataAndTagNode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            context.setCapturedTag(this.getTag());
            context.setCapturedData(this.getCmpData(NodeCmpData.class));
            context.getExecutedNodes().add("dataAndTagNode");
        }
    }

    @Component("dataListNode")
    public static class DataListNode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            context.setCapturedDataList(this.getCmpDataList(String.class));
            context.getExecutedNodes().add("dataListNode");
        }
    }

    public record CustomParam(String name, int age) {
    }

    public record DeliveryPayload(String token, long expiry) {
    }

    // ==================== 9. 高阶编排原语测试组件 ====================

    @Component("endFlowNode")
    public static class EndFlowNode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            context.getExecutedNodes().add("endFlowNode");
            this.endFlow();
        }
    }

    @Component("unreachableNode")
    public static class UnreachableNode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            context.getExecutedNodes().add("unreachableNode");
        }
    }

    @Component("delivererNode")
    public static class DelivererNode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            context.getExecutedNodes().add("delivererNode");
            this.deliverTo("receiverNode", new DeliveryPayload("secret-token", 3600L));
        }
    }

    @Component("receiverNode")
    public static class ReceiverNode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            DeliveryPayload payload = this.receiveDelivery(DeliveryPayload.class);
            if (payload != null) {
                context.getExecutedNodes().add("receiverNode:" + payload.token());
            }
        }
    }

    @Component("subChainTargetNode")
    public static class SubChainTargetNode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            String param = this.getRequestData(String.class);
            context.getExecutedNodes().add("subChainTarget:" + param);
        }
    }

    @Component("subChainCallerNode")
    public static class SubChainCallerNode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            context.getExecutedNodes().add("callerNode");
            this.invokeSubChain("testSubChainTarget", "helloSubChain");
        }
    }

    @Component("requestDataNode")
    public static class RequestDataNode extends BaseNodeComponent<HierarchyTestContext> {
        @Override
        public void process(HierarchyTestContext context) {
            CustomParam param = this.getRequestData(CustomParam.class);
            if (param != null) {
                context.getExecutedNodes().add("requestData:" + param.name() + ":" + param.age());
            }
        }
    }

    // ==================== 10. 控制流 onError 降级测试组件 ====================

    @Component("fallbackIf")
    public static class FallbackIf extends BaseIfComponent<HierarchyTestContext> {
        @Override
        public boolean processIf(HierarchyTestContext context) throws Exception {
            throw new RuntimeException("Simulated IF error");
        }

        @Override
        public boolean onError(Exception e, HierarchyTestContext context) {
            return false;
        }
    }

    @Component("failingIf")
    public static class FailingIf extends BaseIfComponent<HierarchyTestContext> {
        @Override
        public boolean processIf(HierarchyTestContext context) throws Exception {
            throw new RuntimeException("Simulated IF failure without fallback");
        }
    }

    @Component("fallbackSwitch")
    public static class FallbackSwitch extends BaseSwitchComponent<HierarchyTestContext> {
        @Override
        public String processSwitch(HierarchyTestContext context) throws Exception {
            throw new RuntimeException("Simulated Switch error");
        }

        @Override
        public String onError(Exception e, HierarchyTestContext context) {
            return "branchB";
        }
    }

    @Component("fallbackFor")
    public static class FallbackFor extends BaseForComponent<HierarchyTestContext> {
        @Override
        public int processFor(HierarchyTestContext context) throws Exception {
            throw new RuntimeException("Simulated For error");
        }

        @Override
        public int onError(Exception e, HierarchyTestContext context) {
            return 2;
        }
    }

    @Component("fallbackWhile")
    public static class FallbackWhile extends BaseWhileComponent<HierarchyTestContext> {
        @Override
        public boolean processWhile(HierarchyTestContext context) throws Exception {
            throw new RuntimeException("Simulated While error");
        }

        @Override
        public boolean onError(Exception e, HierarchyTestContext context) {
            return false;
        }
    }

    @Component("fallbackIterator")
    public static class FallbackIterator extends BaseIteratorComponent<HierarchyTestContext> {
        @Override
        public Iterator<?> processIterator(HierarchyTestContext context) throws Exception {
            throw new RuntimeException("Simulated Iterator error");
        }

        @Override
        public Iterator<?> onError(Exception e, HierarchyTestContext context) {
            return List.of("fallbackItem").iterator();
        }
    }

    @Component("fallbackBreak")
    public static class FallbackBreak extends BaseBreakComponent<HierarchyTestContext> {
        @Override
        public boolean processBreak(HierarchyTestContext context) throws Exception {
            throw new RuntimeException("Simulated Break error");
        }

        @Override
        public boolean onError(Exception e, HierarchyTestContext context) {
            return true;
        }
    }

    // ==================== 测试用例 ====================

    @Test
    @DisplayName("测试 BaseNodeComponent 强类型上下文获取与执行")
    void testBaseNodeComponent() {
        HierarchyTestContext context = new HierarchyTestContext();
        HierarchyTestContext result = liteflowTemplate.executeWithContext("testNodeChain", context);

        assertThat(result).isSameAs(context);
        assertThat(result.getExecutedNodes()).containsExactly("sampleNode");
    }

    @Test
    @DisplayName("测试 BaseSwitchComponent 路由选择分支 A 与分支 B")
    void testBaseSwitchComponent() {
        // 分支 A
        HierarchyTestContext contextA = new HierarchyTestContext();
        contextA.setSwitchTarget("branchA");
        liteflowTemplate.executeWithContext("testSwitchChain", contextA);
        assertThat(contextA.getExecutedNodes()).containsExactly("branchA");

        // 分支 B
        HierarchyTestContext contextB = new HierarchyTestContext();
        contextB.setSwitchTarget("branchB");
        liteflowTemplate.executeWithContext("testSwitchChain", contextB);
        assertThat(contextB.getExecutedNodes()).containsExactly("branchB");
    }

    @Test
    @DisplayName("测试 BaseIfComponent 条件分支 True 与 False")
    void testBaseIfComponent() {
        // True -> branchA
        HierarchyTestContext contextTrue = new HierarchyTestContext();
        contextTrue.setIfCondition(true);
        liteflowTemplate.executeWithContext("testIfChain", contextTrue);
        assertThat(contextTrue.getExecutedNodes()).containsExactly("branchA");

        // False -> branchB
        HierarchyTestContext contextFalse = new HierarchyTestContext();
        contextFalse.setIfCondition(false);
        liteflowTemplate.executeWithContext("testIfChain", contextFalse);
        assertThat(contextFalse.getExecutedNodes()).containsExactly("branchB");
    }

    @Test
    @DisplayName("测试 BaseForComponent 循环指定次数")
    void testBaseForComponent() {
        HierarchyTestContext context = new HierarchyTestContext();
        context.setForLoops(3);
        liteflowTemplate.executeWithContext("testForChain", context);

        assertThat(context.getCounterExecutions()).isEqualTo(3);
        assertThat(context.getExecutedNodes()).containsExactly("counter:1", "counter:2", "counter:3");
    }

    @Test
    @DisplayName("测试 BaseWhileComponent 依条件循环直到终止")
    void testBaseWhileComponent() {
        HierarchyTestContext context = new HierarchyTestContext();
        context.setWhileCount(4);
        liteflowTemplate.executeWithContext("testWhileChain", context);

        assertThat(context.getCounterExecutions()).isEqualTo(4);
        assertThat(context.getWhileCount()).isZero();
    }

    @Test
    @DisplayName("测试 BaseIteratorComponent 迭代集合元素")
    void testBaseIteratorComponent() {
        HierarchyTestContext context = new HierarchyTestContext();
        context.setIteratorItems(List.of("apple", "banana", "cherry"));
        liteflowTemplate.executeWithContext("testIteratorChain", context);

        assertThat(context.getCollectedItems()).containsExactly("apple", "banana", "cherry");
        assertThat(context.getExecutedNodes()).containsExactly("item:apple:0", "item:banana:1", "item:cherry:2");
    }

    @Test
    @DisplayName("测试 BaseBreakComponent 提前跳出循环")
    void testBaseBreakComponent() {
        HierarchyTestContext context = new HierarchyTestContext();
        context.setBreakThreshold(3);
        liteflowTemplate.executeWithContext("testBreakChain", context);

        // FOR 循环原本为 10 次，达到 3 次时触发 BREAK 退出
        assertThat(context.getCounterExecutions()).isEqualTo(3);
    }

    @Test
    @DisplayName("测试 getTag 与 getCmpData 强类型 Record 解析")
    void testTagAndCmpData() {
        HierarchyTestContext context = new HierarchyTestContext();
        liteflowTemplate.executeWithContext("testDataAndTagChain", context);

        assertThat(context.getCapturedTag()).isEqualTo("vip-tag");
        assertThat(context.getCapturedData()).isNotNull();
        assertThat(context.getCapturedData().limit()).isEqualTo(99);
        assertThat(context.getCapturedData().role()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("测试 getCmpDataList 强类型列表反序列化")
    void testCmpDataList() {
        HierarchyTestContext context = new HierarchyTestContext();
        liteflowTemplate.executeWithContext("testDataListChain", context);

        assertThat(context.getCapturedDataList()).containsExactly("item1", "item2", "item3");
    }

    @Test
    @DisplayName("测试上下文类型不匹配时显式拦截杜绝盲目强转")
    void testContextTypeMismatchRejection() {
        SampleNode sampleNode = new SampleNode();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ComponentSupport.resolveContext(sampleNode, HierarchyTestContext.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未能从当前执行槽中获取到类型为");
    }

    @Test
    @DisplayName("测试流程执行中上下文类型不匹配时显式拦截杜绝盲目强转")
    void testPipelineContextTypeMismatchRejection() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                liteflowTemplate.executeWithContext("testNodeChain", new UnrelatedContext()))
                .isInstanceOf(io.github.atengk.boot.liteflow.exception.LiteflowExecutionException.class)
                .hasMessageContaining("不匹配，拒绝执行不安全的盲目类型转换");
    }

    @Test
    @DisplayName("测试 endFlow 提前正常终止流程链路")
    void testEndFlow() {
        HierarchyTestContext context = new HierarchyTestContext();
        HierarchyTestContext result = liteflowTemplate.executeWithContext("testEndFlowChain", context);

        assertThat(result).isSameAs(context);
        // endFlowNode 执行后终止，unreachableNode 绝不执行
        assertThat(context.getExecutedNodes()).containsExactly("endFlowNode");
    }

    @Test
    @DisplayName("测试 deliverTo 与 receiveDelivery 跨节点强类型私有数据投递")
    void testDeliverToAndReceiveDelivery() {
        HierarchyTestContext context = new HierarchyTestContext();
        liteflowTemplate.executeWithContext("testDeliveryChain", context);

        assertThat(context.getExecutedNodes()).containsExactly("delivererNode", "receiverNode:secret-token");
    }

    @Test
    @DisplayName("测试 invokeSubChain 组件内隐式调用子规则链")
    void testInvokeSubChain() {
        HierarchyTestContext context = new HierarchyTestContext();
        liteflowTemplate.executeWithContext("testSubChainCaller", context);

        assertThat(context.getExecutedNodes()).containsExactly("callerNode", "subChainTarget:helloSubChain");
    }

    @Test
    @DisplayName("测试 invokeSubChain 子链失败时正确包装为业务受管异常并保留因果链路")
    void testInvokeSubChainFailure() {
        SubChainCallerNode node = new SubChainCallerNode();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                node.invokeSubChain(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chainId 不能为空");

        // 构造真实子链执行失败场景，验证因果链完整保留且统一包装为 LiteflowExecutionException
        liteflowTemplate.reloadChain("testSubChainTarget", "IF(failingIf, branchA, branchB);");
        try {
            HierarchyTestContext context = new HierarchyTestContext();
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    liteflowTemplate.executeWithContext("testSubChainCaller", context))
                    .isInstanceOf(io.github.atengk.boot.liteflow.exception.LiteflowExecutionException.class)
                    .hasMessageContaining("testSubChainTarget")
                    .hasRootCauseMessage("Simulated IF failure without fallback");
        } finally {
            liteflowTemplate.reloadChain("testSubChainTarget", "THEN(subChainTargetNode);");
        }
    }

    @Test
    @DisplayName("测试 getRequestData 强类型提取入参对象及类型不匹配拦截")
    void testGetRequestData() {
        HierarchyTestContext context = new HierarchyTestContext();
        CustomParam param = new CustomParam("Alice", 28);
        HierarchyTestContext result = liteflowTemplate.executeWithContext("testRequestDataChain", param, context);

        assertThat(result).isSameAs(context);
        assertThat(context.getExecutedNodes()).containsExactly("requestData:Alice:28");

        // 类型不匹配时抛出受管异常
        HierarchyTestContext mismatchContext = new HierarchyTestContext();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                liteflowTemplate.executeWithContext("testRequestDataChain", "WrongTypeParam", mismatchContext))
                .isInstanceOf(io.github.atengk.boot.liteflow.exception.LiteflowExecutionException.class)
                .hasRootCauseMessage("流程入参类型 [java.lang.String] 与期望类型 [io.github.atengk.boot.liteflow.component.LiteflowComponentHierarchyTest$CustomParam] 不匹配，拒绝执行不安全的盲目转换");
    }

    @Test
    @DisplayName("测试 BaseIfComponent onError 就地降级走假分支")
    void testBaseIfComponentOnError() {
        HierarchyTestContext context = new HierarchyTestContext();
        liteflowTemplate.executeWithContext("testFallbackIfChain", context);

        // 发生异常后降级返回 false，从而走向 branchB
        assertThat(context.getExecutedNodes()).containsExactly("branchB");
    }

    @Test
    @DisplayName("测试 BaseIfComponent 默认 onError 向上抛出异常中断")
    void testBaseIfComponentDefaultError() {
        HierarchyTestContext context = new HierarchyTestContext();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                liteflowTemplate.executeWithContext("testDefaultErrorIfChain", context))
                .isInstanceOf(io.github.atengk.boot.liteflow.exception.LiteflowExecutionException.class);
    }

    @Test
    @DisplayName("测试 BaseSwitchComponent onError 就地降级路由")
    void testBaseSwitchComponentOnError() {
        HierarchyTestContext context = new HierarchyTestContext();
        liteflowTemplate.executeWithContext("testFallbackSwitchChain", context);

        // 发生异常后降级返回 "branchB"
        assertThat(context.getExecutedNodes()).containsExactly("branchB");
    }

    @Test
    @DisplayName("测试 BaseForComponent onError 就地降级循环次数")
    void testBaseForComponentOnError() {
        HierarchyTestContext context = new HierarchyTestContext();
        liteflowTemplate.executeWithContext("testFallbackForChain", context);

        // 发生异常后降级返回 2 次循环
        assertThat(context.getCounterExecutions()).isEqualTo(2);
        assertThat(context.getExecutedNodes()).containsExactly("counter:1", "counter:2");
    }

    @Test
    @DisplayName("测试 BaseWhileComponent onError 就地降级终止条件")
    void testBaseWhileComponentOnError() {
        HierarchyTestContext context = new HierarchyTestContext();
        liteflowTemplate.executeWithContext("testFallbackWhileChain", context);

        // 发生异常后降级返回 false，循环不执行
        assertThat(context.getCounterExecutions()).isZero();
    }

    @Test
    @DisplayName("测试 BaseIteratorComponent onError 就地降级保底迭代器")
    void testBaseIteratorComponentOnError() {
        HierarchyTestContext context = new HierarchyTestContext();
        liteflowTemplate.executeWithContext("testFallbackIteratorChain", context);

        // 发生异常后降级返回 fallbackItem 迭代器
        assertThat(context.getCollectedItems()).containsExactly("fallbackItem");
        assertThat(context.getExecutedNodes()).containsExactly("item:fallbackItem:0");
    }

    @Test
    @DisplayName("测试 BaseBreakComponent onError 就地降级触发提前退出")
    void testBaseBreakComponentOnError() {
        HierarchyTestContext context = new HierarchyTestContext();
        liteflowTemplate.executeWithContext("testFallbackBreakChain", context);

        // 发生异常后降级返回 true，立即 break，因此只执行 1 次 counterNode
        assertThat(context.getCounterExecutions()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试 ComponentSupport 各工具方法的边界防御与非空校验")
    void testComponentSupportDefensiveGuards() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ComponentSupport.getRequestData(null, String.class))
                .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ComponentSupport.getCurrLoopObject(null, String.class))
                .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ComponentSupport.receiveDelivery(null, String.class))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ComponentSupport.getLoopIndex(null)).isNull();
    }

    public static class UnrelatedContext {
        private String dummy = "dummy";
        public String getDummy() { return dummy; }
        public void setDummy(String dummy) { this.dummy = dummy; }
    }
}
