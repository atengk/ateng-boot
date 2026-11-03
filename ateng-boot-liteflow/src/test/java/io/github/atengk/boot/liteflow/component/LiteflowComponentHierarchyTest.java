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
            DataListNode.class
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
            String item = this.getCurrLoopObj();
            context.getCollectedItems().add(item);
            context.getExecutedNodes().add("item:" + item);
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
        assertThat(context.getExecutedNodes()).containsExactly("item:apple", "item:banana", "item:cherry");
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
}
