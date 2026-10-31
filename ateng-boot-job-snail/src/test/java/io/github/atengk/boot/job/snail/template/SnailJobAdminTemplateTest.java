package io.github.atengk.boot.job.snail.template;

import com.aizuda.snailjob.client.core.openapi.RetryOpenApiClientV2;
import com.aizuda.snailjob.client.job.core.openapi.JobOpenApiClientV2;
import com.aizuda.snailjob.common.core.enums.StatusEnum;
import com.aizuda.snailjob.common.core.model.Result;
import com.aizuda.snailjob.model.request.*;
import com.aizuda.snailjob.model.request.base.JobRequest;
import com.aizuda.snailjob.model.request.base.StatusUpdateRequest;
import com.aizuda.snailjob.model.request.base.WorkflowTriggerRequest;
import com.aizuda.snailjob.model.response.*;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SnailJob OpenAPI 管理门面单元测试
 *
 * <p>100% 离线脱网自洽，全面覆盖 Job、Workflow、BizId 与 Retry 门面方法的正向调用、
 * 因果异常链传递（Robustness Invariant 7）、失败状态码转译与防御性前置拦截。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@ExtendWith(MockitoExtension.class)
class SnailJobAdminTemplateTest {

    @Mock
    private JobOpenApiClientV2 jobClient;

    @Mock
    private RetryOpenApiClientV2 retryClient;

    private SnailJobAdminTemplate template;
    private SnailJobProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SnailJobProperties();
        properties.getOpenapi().setMaxRetries(1); // 默认单测单次尝试，避免休眠耗时
        properties.getOpenapi().setRetryInterval(java.time.Duration.ofMillis(10));
        template = new SnailJobAdminTemplate(jobClient, retryClient, properties);
    }

    // =========================================================================
    // 1. 任务核心生命周期管理单测
    // =========================================================================

    @Test
    @DisplayName("测试 addJob：成功新增任务并返回生成的数字型 JobId")
    void testAddJobSuccess() {
        JobRequest request = new JobRequest();
        request.setJobName("test-job");
        when(jobClient.addJob(request)).thenReturn(new Result<>(1001L));

        Long jobId = template.addJob(request);
        assertThat(jobId).isEqualTo(1001L);
        verify(jobClient).addJob(request);
    }

    @Test
    @DisplayName("测试 updateJob：成功更新任务配置")
    void testUpdateJobSuccess() {
        JobRequest request = new JobRequest();
        request.setId(1001L);
        request.setJobName("updated-job");
        when(jobClient.updateJob(request)).thenReturn(new Result<>(true));

        Boolean updated = template.updateJob(request);
        assertThat(updated).isTrue();
        verify(jobClient).updateJob(request);
    }

    @Test
    @DisplayName("测试 getJobDetail：成功查询任务元数据详情")
    void testGetJobDetailSuccess() {
        JobApiResponse response = new JobApiResponse();
        response.setId(1001L);
        response.setJobName("detail-job");
        when(jobClient.getJobDetail(1001L)).thenReturn(new Result<>(response));

        JobApiResponse result = template.getJobDetail(1001L);
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1001L);
        assertThat(result.getJobName()).isEqualTo("detail-job");
    }

    @Test
    @DisplayName("测试 getJobBatchDetail：成功查询任务批次详情")
    void testGetJobBatchDetailSuccess() {
        JobBatchApiResponse response = new JobBatchApiResponse();
        response.setId(2001L);
        when(jobClient.getJobBatchDetail(2001L)).thenReturn(new Result<>(response));

        JobBatchApiResponse result = template.getJobBatchDetail(2001L);
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(2001L);
    }

    @Test
    @DisplayName("测试 triggerJob：成功手动触发调度任务执行")
    void testTriggerJobSuccess() {
        when(jobClient.triggerJob(any(JobTriggerApiRequest.class))).thenReturn(new Result<>(true));

        Boolean triggered = template.triggerJob(1001L, "{\"shard\":0}");
        assertThat(triggered).isTrue();

        ArgumentCaptor<JobTriggerApiRequest> captor = ArgumentCaptor.forClass(JobTriggerApiRequest.class);
        verify(jobClient).triggerJob(captor.capture());
        assertThat(captor.getValue().getJobId()).isEqualTo(1001L);
        assertThat(captor.getValue().getTmpArgsStr()).isEqualTo("{\"shard\":0}");

        // 便捷无参重载
        template.triggerJob(1001L);
        verify(jobClient, times(2)).triggerJob(any(JobTriggerApiRequest.class));
    }

    @Test
    @DisplayName("测试 enableJob / disableJob：更新任务状态为启用或停用")
    void testEnableAndDisableJob() {
        when(jobClient.updateJobStatus(any(StatusUpdateRequest.class))).thenReturn(new Result<>(true));

        Boolean enabled = template.enableJob(1001L);
        assertThat(enabled).isTrue();

        Boolean disabled = template.disableJob(1001L);
        assertThat(disabled).isTrue();

        ArgumentCaptor<StatusUpdateRequest> captor = ArgumentCaptor.forClass(StatusUpdateRequest.class);
        verify(jobClient, times(2)).updateJobStatus(captor.capture());
        assertThat(captor.getAllValues().get(0).getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(StatusEnum.NO.getStatus());
    }

    @Test
    @DisplayName("测试 deleteJob / deleteJobs：成功删除任务")
    void testDeleteJobSuccess() {
        when(jobClient.deleteJob(anySet())).thenReturn(new Result<>(true));

        Boolean deletedSingle = template.deleteJob(1001L);
        assertThat(deletedSingle).isTrue();

        Boolean deletedBatch = template.deleteJobs(Set.of(1001L, 1002L));
        assertThat(deletedBatch).isTrue();

        verify(jobClient, times(2)).deleteJob(anySet());
    }

    @Test
    @DisplayName("测试 existsJobById 与 existsJob：成功检查任务存在性")
    void testExistsJob() {
        JobExistsResponse existResp = new JobExistsResponse();
        existResp.setId(1001L);
        existResp.setJobStatus(1);
        when(jobClient.existsJobById(1001L)).thenReturn(new Result<>(existResp));

        JobExistsResponse notExistResp = new JobExistsResponse();
        when(jobClient.existsJobById(9999L)).thenReturn(new Result<>(notExistResp));

        assertThat(template.existsJob(1001L)).isTrue();
        assertThat(template.existsJob(9999L)).isFalse();
    }

    // =========================================================================
    // 2. 工作流 (DAG Workflow) 单测
    // =========================================================================

    @Test
    @DisplayName("测试 getWorkflowBatchDetail：成功查询工作流批次详情")
    void testGetWorkflowBatchDetailSuccess() {
        WorkflowDetailApiResponse response = new WorkflowDetailApiResponse();
        response.setId(3001L);
        when(jobClient.getWorkflowBatchDetail(3001L)).thenReturn(new Result<>(response));

        WorkflowDetailApiResponse result = template.getWorkflowBatchDetail(3001L);
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(3001L);
    }

    @Test
    @DisplayName("测试 triggerWorkflow：成功手动触发工作流")
    void testTriggerWorkflowSuccess() {
        when(jobClient.triggerWorkFlow(any(WorkflowTriggerRequest.class))).thenReturn(new Result<>(true));

        Boolean triggered = template.triggerWorkflow(5001L, "{\"tenantId\":\"T001\"}");
        assertThat(triggered).isTrue();

        ArgumentCaptor<WorkflowTriggerRequest> captor = ArgumentCaptor.forClass(WorkflowTriggerRequest.class);
        verify(jobClient).triggerWorkFlow(captor.capture());
        assertThat(captor.getValue().getWorkflowId()).isEqualTo(5001L);
        assertThat(captor.getValue().getTmpWfContext()).isEqualTo("{\"tenantId\":\"T001\"}");

        // 无上下文重载
        template.triggerWorkflow(5001L);
        verify(jobClient, times(2)).triggerWorkFlow(any(WorkflowTriggerRequest.class));
    }

    @Test
    @DisplayName("测试 enableWorkflow / disableWorkflow：成功启停工作流")
    void testEnableAndDisableWorkflow() {
        when(jobClient.updateWorkFlowStatus(any(StatusUpdateRequest.class))).thenReturn(new Result<>(true));

        Boolean enabled = template.enableWorkflow(5001L);
        assertThat(enabled).isTrue();

        Boolean disabled = template.disableWorkflow(5001L);
        assertThat(disabled).isTrue();

        ArgumentCaptor<StatusUpdateRequest> captor = ArgumentCaptor.forClass(StatusUpdateRequest.class);
        verify(jobClient, times(2)).updateWorkFlowStatus(captor.capture());
        assertThat(captor.getAllValues().get(0).getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(StatusEnum.NO.getStatus());
    }

    @Test
    @DisplayName("测试 deleteWorkflow / deleteWorkflows：成功删除工作流")
    void testDeleteWorkflowSuccess() {
        when(jobClient.deleteWorkflow(anySet())).thenReturn(new Result<>(true));

        Boolean deletedSingle = template.deleteWorkflow(5001L);
        assertThat(deletedSingle).isTrue();

        Boolean deletedBatch = template.deleteWorkflows(Set.of(5001L, 5002L));
        assertThat(deletedBatch).isTrue();

        verify(jobClient, times(2)).deleteWorkflow(anySet());
    }

    @Test
    @DisplayName("测试 existsWorkflowById 与 existsWorkflow：成功检查工作流存在性")
    void testExistsWorkflow() {
        JobExistsResponse existResp = new JobExistsResponse();
        existResp.setId(5001L);
        when(jobClient.existsWorkflowById(5001L)).thenReturn(new Result<>(existResp));

        JobExistsResponse notExistResp = new JobExistsResponse();
        when(jobClient.existsWorkflowById(8888L)).thenReturn(new Result<>(notExistResp));

        assertThat(template.existsWorkflow(5001L)).isTrue();
        assertThat(template.existsWorkflow(8888L)).isFalse();
    }

    // =========================================================================
    // 3. BizId 业务唯一标识全量方法单测
    // =========================================================================

    @Test
    @DisplayName("测试 BizId 任务完整生命周期：add, update, get, trigger, enable, disable, delete, exists")
    void testJobBizIdLifecycle() {
        JobBizIdApiRequest addReq = new JobBizIdApiRequest();
        addReq.setBizId("BIZ-JOB-001");
        when(jobClient.addJobByBizId(addReq)).thenReturn(new Result<>(7001L));
        assertThat(template.addJobByBizId(addReq)).isEqualTo(7001L);

        when(jobClient.updateJobByBizId(addReq)).thenReturn(new Result<>(true));
        assertThat(template.updateJobByBizId(addReq)).isTrue();

        JobApiResponse jobDetail = new JobApiResponse();
        jobDetail.setId(7001L);
        when(jobClient.getJobDetailByBizId("BIZ-JOB-001")).thenReturn(new Result<>(jobDetail));
        assertThat(template.getJobDetailByBizId("BIZ-JOB-001").getId()).isEqualTo(7001L);

        when(jobClient.triggerJobByBizId(any(JobTriggerBizIdRequest.class))).thenReturn(new Result<>(true));
        assertThat(template.triggerJobByBizId("BIZ-JOB-001", "args")).isTrue();
        assertThat(template.triggerJobByBizId("BIZ-JOB-001")).isTrue();

        when(jobClient.updateJobStatusByBizId(any(StatusUpdateBizIdRequest.class))).thenReturn(new Result<>(true));
        assertThat(template.enableJobByBizId("BIZ-JOB-001")).isTrue();
        assertThat(template.disableJobByBizId("BIZ-JOB-001")).isTrue();

        when(jobClient.deleteJobByBizIds(any(DeleteBizIdRequest.class))).thenReturn(new Result<>(true));
        assertThat(template.deleteJobByBizId("BIZ-JOB-001")).isTrue();
        assertThat(template.deleteJobByBizIds(Set.of("BIZ-JOB-001", "BIZ-JOB-002"))).isTrue();

        JobExistsResponse existsResp = new JobExistsResponse();
        existsResp.setBizId("BIZ-JOB-001");
        when(jobClient.existsJobByBizId("BIZ-JOB-001")).thenReturn(new Result<>(existsResp));
        assertThat(template.existsJobBizId("BIZ-JOB-001")).isTrue();
    }

    @Test
    @DisplayName("测试 BizId 工作流生命周期：trigger, enable, disable, delete, exists")
    void testWorkflowBizIdLifecycle() {
        when(jobClient.triggerWorkflowByBizId(any(WorkflowTriggerBizIdRequest.class))).thenReturn(new Result<>(true));
        assertThat(template.triggerWorkflowByBizId("BIZ-WF-001", "wf-ctx")).isTrue();
        assertThat(template.triggerWorkflowByBizId("BIZ-WF-001")).isTrue();

        when(jobClient.updateWorkflowStatusByBizId(any(WorkflowStatusUpdateBizIdRequest.class))).thenReturn(new Result<>(true));
        assertThat(template.enableWorkflowByBizId("BIZ-WF-001")).isTrue();
        assertThat(template.disableWorkflowByBizId("BIZ-WF-001")).isTrue();

        when(jobClient.deleteWorkflowByBizIds(anySet())).thenReturn(new Result<>(true));
        assertThat(template.deleteWorkflowByBizId("BIZ-WF-001")).isTrue();
        assertThat(template.deleteWorkflowByBizIds(Set.of("BIZ-WF-001"))).isTrue();

        JobExistsResponse existsResp = new JobExistsResponse();
        existsResp.setBizId("BIZ-WF-001");
        when(jobClient.existsWorkflowByBizId("BIZ-WF-001")).thenReturn(new Result<>(existsResp));
        assertThat(template.existsWorkflowBizId("BIZ-WF-001")).isTrue();
    }

    // =========================================================================
    // 4. 重试 OpenAPI 单测
    // =========================================================================

    @Test
    @DisplayName("测试 Retry OpenAPI：queryRetryTask, triggerRetryTask, updateRetryTaskStatus")
    void testRetryOpenApiOperations() {
        RetryApiResponse response = new RetryApiResponse();
        response.setId(88001L);
        when(retryClient.queryRetryTask(88001L)).thenReturn(new Result<>(response));
        assertThat(template.queryRetryTask(88001L).getId()).isEqualTo(88001L);

        when(retryClient.triggerRetryTask(any(TriggerRetryApiRequest.class))).thenReturn(new Result<>(true));
        assertThat(template.triggerRetryTask(88001L)).isTrue();

        when(retryClient.updateRetryTaskStatus(any(StatusUpdateRequest.class))).thenReturn(new Result<>(true));
        assertThat(template.updateRetryTaskStatus(88001L, 1)).isTrue();
    }

    // =========================================================================
    // 5. 因果异常传递、错误码转译与防御性单测 (Robustness Invariant 7)
    // =========================================================================

    @Test
    @DisplayName("测试服务端返回失败状态（status != 1）：受控转译为 BusinessException")
    void testExecuteServerFailureTranslatedToBusinessException() {
        Result<Long> failResult = new Result<>();
        failResult.setStatus(0);
        failResult.setMessage("命名空间已过期或无权新增");
        when(jobClient.addJob(any())).thenReturn(failResult);

        JobRequest request = new JobRequest();
        assertThatThrownBy(() -> template.addJob(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("命名空间已过期或无权新增")
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getResultCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR);
                });
    }

    @Test
    @DisplayName("测试服务端返回 null 响应：受控转译为 BusinessException")
    void testExecuteNullResponseThrowsBusinessException() {
        when(jobClient.getJobDetail(1001L)).thenReturn(null);

        assertThatThrownBy(() -> template.getJobDetail(1001L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("服务端未返回有效响应")
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getResultCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR);
                });
    }

    @Test
    @DisplayName("测试底层网络/RPC 异常：转译为 BusinessException 且 100% 保留原始 cause 异常链")
    void testExecuteNetworkExceptionPreservesRootCause() {
        IOException networkError = new IOException("Connection refused to SnailJob server");
        when(jobClient.getJobDetail(1001L)).thenThrow(new RuntimeException("RPC transport failed", networkError));

        assertThatThrownBy(() -> template.getJobDetail(1001L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("RPC transport failed")
                .hasCauseInstanceOf(RuntimeException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getResultCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR);
                    assertThat(be.getCause()).isNotNull();
                    assertThat(be.getCause().getCause()).isSameAs(networkError);
                });
    }

    @Test
    @DisplayName("测试客户端未就绪守卫：当注入 null 客户端且底层无法初始化时抛出语义化 BusinessException")
    void testRequireClientThrowsWhenNotAvailable() {
        SnailJobAdminTemplate nullTemplate = new SnailJobAdminTemplate(null, null);

        // 如果底层未生成代理或者为空，则会抛出 BusinessException
        if (nullTemplate.getJobClient() == null) {
            assertThatThrownBy(() -> nullTemplate.addJob(new JobRequest()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("JobOpenApiClientV2 未就绪或未启用");
        }

        if (nullTemplate.getRetryClient() == null) {
            assertThatThrownBy(() -> nullTemplate.queryRetryTask(1001L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("RetryOpenApiClientV2 未就绪或未启用");
        }
    }

    @Test
    @DisplayName("测试入参空安全防御：空入参立即 Fail-Fast 抛出 IllegalArgumentException")
    void testNullArgumentsFailFast() {
        assertThatThrownBy(() -> template.addJob(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.getJobDetail(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.deleteJobs(Collections.emptySet()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.triggerJobByBizId(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.getJobDetailByBizId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试网络瞬时异常指数退避重试：第 1 次失败，第 2 次重试成功")
    void testExecuteWithRetrySuccess() {
        SnailJobProperties retryProps = new SnailJobProperties();
        retryProps.getOpenapi().setMaxRetries(3);
        retryProps.getOpenapi().setRetryInterval(java.time.Duration.ofMillis(20));
        SnailJobAdminTemplate retryTemplate = new SnailJobAdminTemplate(jobClient, retryClient, retryProps);

        JobApiResponse response = new JobApiResponse();
        response.setId(2001L);

        when(jobClient.getJobDetail(2001L))
                .thenThrow(new RuntimeException("Transient connection reset"))
                .thenReturn(new Result<>(response));

        JobApiResponse result = retryTemplate.getJobDetail(2001L);
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(2001L);
        verify(jobClient, times(2)).getJobDetail(2001L);
    }
}
