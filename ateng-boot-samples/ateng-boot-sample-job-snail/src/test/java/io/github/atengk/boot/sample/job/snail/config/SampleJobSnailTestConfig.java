package io.github.atengk.boot.sample.job.snail.config;

import com.aizuda.snailjob.model.response.JobApiResponse;
import com.aizuda.snailjob.model.response.RetryApiResponse;
import com.aizuda.snailjob.model.response.WorkflowDetailApiResponse;
import io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * SnailJob 样例工程离线单测自洽配置桩
 *
 * <p>在非 local (即 CI 或离线本地) 环境下，向容器注入具备完整仿真契约的
 * {@link SnailJobAdminTemplate} Mock 桩，确保 5 大 Controller 的 Web 测试
 * 在脱网环境下 100% 独立自洽运行。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@TestConfiguration(proxyBeanMethods = false)
public class SampleJobSnailTestConfig {

    @Bean
    @Primary
    @Profile("!local")
    public SnailJobAdminTemplate mockSnailJobAdminTemplate() {
        SnailJobAdminTemplate mock = Mockito.mock(SnailJobAdminTemplate.class);

        // 1. 任务管理 OpenAPI 行为打桩
        when(mock.addJob(any())).thenReturn(1001L);
        when(mock.updateJob(any())).thenReturn(true);
        when(mock.triggerJob(anyLong())).thenReturn(true);
        when(mock.deleteJob(anyLong())).thenReturn(true);

        JobApiResponse mockJob = new JobApiResponse();
        mockJob.setId(1001L);
        mockJob.setJobName("mockSystemWarmupJob");
        mockJob.setExecutorInfo("warmupAndProbeJobHandler");
        mockJob.setJobStatus(1);
        when(mock.getJobDetail(anyLong())).thenReturn(mockJob);

        // 2. 工作流 DAG 管理 OpenAPI 行为打桩
        when(mock.triggerWorkflow(anyLong())).thenReturn(true);
        when(mock.enableWorkflow(anyLong())).thenReturn(true);
        when(mock.disableWorkflow(anyLong())).thenReturn(true);

        WorkflowDetailApiResponse mockWorkflow = new WorkflowDetailApiResponse();
        mockWorkflow.setId(2001L);
        mockWorkflow.setWorkflowName("mockBillSettlementWorkflow");
        when(mock.getWorkflowBatchDetail(anyLong())).thenReturn(mockWorkflow);

        // 3. 重试运维 OpenAPI 行为打桩
        when(mock.triggerRetryTask(anyLong())).thenReturn(true);
        when(mock.updateRetryTaskStatus(anyLong(), any())).thenReturn(true);

        RetryApiResponse mockRetry = new RetryApiResponse();
        mockRetry.setId(888L);
        mockRetry.setSceneName("orderPayRetry");
        mockRetry.setRetryStatus(1);
        when(mock.queryRetryTask(anyLong())).thenReturn(mockRetry);

        return mock;
    }
}
