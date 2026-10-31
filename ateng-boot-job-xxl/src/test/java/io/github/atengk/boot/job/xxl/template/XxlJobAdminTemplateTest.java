package io.github.atengk.boot.job.xxl.template;

import com.xxl.job.core.openapi.admin.AdminJobBiz;
import com.xxl.job.core.openapi.admin.dto.JobAddRequest;
import com.xxl.job.core.openapi.admin.dto.JobOperateRequest;
import com.xxl.job.core.openapi.admin.dto.JobTriggerRequest;
import com.xxl.job.core.openapi.admin.dto.JobUpdateRequest;
import com.xxl.tool.response.Response;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * XXL-Job 3.x OpenAPI 编程式管理门面单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class XxlJobAdminTemplateTest {

    @Test
    @DisplayName("测试 addJob 与 addJobAndGetId 新增任务")
    void testAddJobSuccess() {
        AdminJobBiz mockBiz = new BaseMockAdminJobBiz() {
            @Override
            public Response<String> addJob(JobAddRequest request) {
                return Response.ofSuccess("88");
            }
        };

        XxlJobAdminTemplate template = new XxlJobAdminTemplate(List.of(mockBiz));

        JobAddRequest req = new JobAddRequest();
        req.setName("testJob");
        req.setJobGroup(1);

        String idStr = template.addJob(req);
        assertEquals("88", idStr);

        int id = template.addJobAndGetId(req);
        assertEquals(88, id);
    }

    @Test
    @DisplayName("测试 updateJob 更新任务")
    void testUpdateJobSuccess() {
        AtomicInteger updateCount = new AtomicInteger();
        AdminJobBiz mockBiz = new BaseMockAdminJobBiz() {
            @Override
            public Response<String> updateJob(JobUpdateRequest request) {
                updateCount.incrementAndGet();
                return Response.ofSuccess("true");
            }
        };

        XxlJobAdminTemplate template = new XxlJobAdminTemplate(List.of(mockBiz));

        JobUpdateRequest req = new JobUpdateRequest();
        req.setId(88);
        req.setName("updatedJob");

        template.updateJob(req);
        assertEquals(1, updateCount.get());
    }

    @Test
    @DisplayName("测试 removeJob 删除任务 (包含 int 便捷重载)")
    void testRemoveJobSuccess() {
        List<Integer> removedIds = new ArrayList<>();
        AdminJobBiz mockBiz = new BaseMockAdminJobBiz() {
            @Override
            public Response<String> removeJob(JobOperateRequest request) {
                removedIds.add(request.getId());
                return Response.ofSuccess("true");
            }
        };

        XxlJobAdminTemplate template = new XxlJobAdminTemplate(List.of(mockBiz));

        JobOperateRequest req = new JobOperateRequest();
        req.setId(10);
        template.removeJob(req);
        template.removeJob(20);

        assertEquals(List.of(10, 20), removedIds);
    }

    @Test
    @DisplayName("测试 startJob 与 stopJob 启停任务 (对称性语义)")
    void testStartAndStopJobSuccess() {
        List<String> actions = new ArrayList<>();
        AdminJobBiz mockBiz = new BaseMockAdminJobBiz() {
            @Override
            public Response<String> startJob(JobOperateRequest request) {
                actions.add("START:" + request.getId());
                return Response.ofSuccess("true");
            }

            @Override
            public Response<String> stopJob(JobOperateRequest request) {
                actions.add("STOP:" + request.getId());
                return Response.ofSuccess("true");
            }
        };

        XxlJobAdminTemplate template = new XxlJobAdminTemplate(List.of(mockBiz));

        template.startJob(100);
        template.stopJob(100);

        assertEquals(List.of("START:100", "STOP:100"), actions);
    }

    @Test
    @DisplayName("测试 triggerJob 即时触发与多形态重载")
    void testTriggerJobSuccess() {
        List<String> triggers = new ArrayList<>();
        AdminJobBiz mockBiz = new BaseMockAdminJobBiz() {
            @Override
            public Response<String> triggerJob(JobTriggerRequest request) {
                triggers.add(request.getId() + "#" + request.getExecutorParam() + "#" + request.getAddressList());
                return Response.ofSuccess("true");
            }
        };

        XxlJobAdminTemplate template = new XxlJobAdminTemplate(List.of(mockBiz));

        template.triggerJob(1);
        template.triggerJob(2, "paramA");
        template.triggerJob(3, "paramB", "127.0.0.1:9999");

        assertEquals(List.of("1#null#null", "2#paramA#null", "3#paramB#127.0.0.1:9999"), triggers);
    }

    @Test
    @DisplayName("测试高可用集群故障转移 (Failover Retry) 机制")
    void testClusterFailoverSuccess() {
        AtomicInteger attemptNode1 = new AtomicInteger();
        AtomicInteger attemptNode2 = new AtomicInteger();

        // 节点 1 模拟网络不可达抛出异常
        AdminJobBiz node1 = new BaseMockAdminJobBiz() {
            @Override
            public Response<String> triggerJob(JobTriggerRequest request) {
                attemptNode1.incrementAndGet();
                throw new RuntimeException("Connection refused to admin1:8080");
            }
        };

        // 节点 2 正常响应
        AdminJobBiz node2 = new BaseMockAdminJobBiz() {
            @Override
            public Response<String> triggerJob(JobTriggerRequest request) {
                attemptNode2.incrementAndGet();
                return Response.ofSuccess("true");
            }
        };

        XxlJobAdminTemplate template = new XxlJobAdminTemplate(List.of(node1, node2));

        template.triggerJob(66);

        assertEquals(1, attemptNode1.get(), "应首先尝试节点 1");
        assertEquals(1, attemptNode2.get(), "节点 1 失败后应自动故障转移至节点 2");
    }

    @Test
    @DisplayName("测试全部调度中心均调用失败时抛出因果业务异常")
    void testAllNodesFailedThrowsBusinessException() {
        AdminJobBiz node1 = new BaseMockAdminJobBiz() {
            @Override
            public Response<String> stopJob(JobOperateRequest request) {
                return Response.ofFail("admin1 数据库连接异常");
            }
        };

        AdminJobBiz node2 = new BaseMockAdminJobBiz() {
            @Override
            public Response<String> stopJob(JobOperateRequest request) {
                throw new RuntimeException("admin2 网络超时");
            }
        };

        XxlJobAdminTemplate template = new XxlJobAdminTemplate(List.of(node1, node2));

        BusinessException ex = assertThrows(BusinessException.class, () -> template.stopJob(99));
        assertTrue(ex.getMessage().contains("全部调度中心节点均调用失败"));
        assertTrue(ex.getMessage().contains("admin1 数据库连接异常"));
        assertTrue(ex.getMessage().contains("admin2 网络超时"));
    }

    @Test
    @DisplayName("测试调度中心未配置时快速失败")
    void testEmptyAdminAddressesThrowsException() {
        XxlJobProperties properties = new XxlJobProperties();
        properties.setAdminAddresses("");

        XxlJobAdminTemplate template = new XxlJobAdminTemplate(properties);
        assertEquals(0, template.getEndpointCount());

        BusinessException ex = assertThrows(BusinessException.class, () -> template.triggerJob(1));
        assertTrue(ex.getMessage().contains("调度中心未配置或不可用"));
    }

    @Test
    @DisplayName("测试多地址解析与配置属性绑定")
    void testMultipleAdminAddressesParsing() {
        XxlJobProperties properties = new XxlJobProperties();
        properties.setAdminAddresses("http://127.0.0.1:8080/xxl-job-admin, http://127.0.0.1:8081/xxl-job-admin/");
        properties.setAppname("demo-executor");
        properties.setTimeout(Duration.ofSeconds(5));

        XxlJobAdminTemplate template = new XxlJobAdminTemplate(properties);
        assertEquals(2, template.getEndpointCount());
    }

    @Test
    @DisplayName("测试非法参数快速失败防卫")
    void testFailFastParameterValidation() {
        XxlJobAdminTemplate template = new XxlJobAdminTemplate(Collections.emptyList());

        assertThrows(IllegalArgumentException.class, () -> template.addJob(null));
        assertThrows(IllegalArgumentException.class, () -> template.updateJob(null));
        assertThrows(IllegalArgumentException.class, () -> template.removeJob(0));
        assertThrows(IllegalArgumentException.class, () -> template.startJob(-1));
        assertThrows(IllegalArgumentException.class, () -> template.stopJob(-5));
        assertThrows(IllegalArgumentException.class, () -> template.triggerJob(0));
    }

    /**
     * 测试用 Mock 基类
     */
    private static class BaseMockAdminJobBiz implements AdminJobBiz {
        @Override
        public Response<String> addJob(JobAddRequest request) {
            return Response.ofSuccess("1");
        }

        @Override
        public Response<String> updateJob(JobUpdateRequest request) {
            return Response.ofSuccess("true");
        }

        @Override
        public Response<String> removeJob(JobOperateRequest request) {
            return Response.ofSuccess("true");
        }

        @Override
        public Response<String> startJob(JobOperateRequest request) {
            return Response.ofSuccess("true");
        }

        @Override
        public Response<String> stopJob(JobOperateRequest request) {
            return Response.ofSuccess("true");
        }

        @Override
        public Response<String> triggerJob(JobTriggerRequest request) {
            return Response.ofSuccess("true");
        }
    }
}
