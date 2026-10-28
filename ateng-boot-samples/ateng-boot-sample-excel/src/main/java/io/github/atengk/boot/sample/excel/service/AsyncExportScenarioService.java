package io.github.atengk.boot.sample.excel.service;

import io.github.atengk.boot.excel.async.AsyncExportTaskService;
import io.github.atengk.boot.excel.async.ExportTask;
import io.github.atengk.boot.excel.async.storage.ExportFileStorage;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.sample.excel.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.excel.vo.UserExcelVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 业务场景 4：后台受管异步导出任务中心场景服务
 * <p>
 * 演示异步导出任务的线程池提交、任务状态轮询、物理结果文件存储检索及过期物理任务清理。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Service
public class AsyncExportScenarioService {

    private final AsyncExportTaskService asyncExportTaskService;
    private final ExportFileStorage exportFileStorage;

    public AsyncExportScenarioService(@Autowired(required = false) AsyncExportTaskService asyncExportTaskService,
                                      @Autowired(required = false) ExportFileStorage exportFileStorage) {
        this.asyncExportTaskService = asyncExportTaskService;
        this.exportFileStorage = exportFileStorage;
    }

    /**
     * 提交后台异步导出任务
     *
     * @param taskName 任务名称
     * @param rowCount 导出记录数
     * @return 生成的任务 ID
     */
    public String submitTask(String taskName, int rowCount) {
        if (asyncExportTaskService == null) {
            throw new IllegalStateException("AsyncExportTaskService 未装配");
        }
        return asyncExportTaskService.submit(taskName, out -> {
            List<UserExcelVO> mockData = new ArrayList<>(rowCount);
            for (int i = 1; i <= rowCount; i++) {
                mockData.add(new UserExcelVO(
                        (long) i,
                        "异步用户_" + i,
                        "1380000" + String.format("%04d", i),
                        "async" + i + "@example.com",
                        (i % 2 == 0) ? SampleGenderEnum.FEMALE : SampleGenderEnum.MALE,
                        "运营中心",
                        "0"
                ));
            }
            ExcelUtil.export(out, "全量数据", mockData, UserExcelVO.class);
        });
    }

    /**
     * 获取异步任务当前状态
     *
     * @param taskId 任务 ID
     * @return 任务对象
     */
    public ExportTask getTask(String taskId) {
        if (asyncExportTaskService == null) {
            return null;
        }
        return asyncExportTaskService.getTask(taskId);
    }

    /**
     * 下载已生成完毕的异步导出任务文件
     *
     * @param taskId 任务 ID
     * @param out    目标输出流
     * @throws IOException IO 异常
     */
    public void downloadTaskFile(String taskId, OutputStream out) throws IOException {
        ExportTask task = getTask(taskId);
        if (task == null || exportFileStorage == null) {
            throw new IllegalStateException("任务或文件存储不存在: " + taskId);
        }
        String storageKey = task.getStorageKey() != null ? task.getStorageKey() : task.getFilePath();
        try (InputStream in = exportFileStorage.getInputStream(taskId, storageKey)) {
            if (in == null) {
                throw new IllegalStateException("无法读取目标导出文件: " + taskId);
            }
            in.transferTo(out);
        }
    }

    /**
     * 清理过期已落盘的异步任务物理文件与元数据
     *
     * @param retention 存活保留周期阈值
     */
    public void cleanExpiredTasks(Duration retention) {
        if (asyncExportTaskService != null) {
            asyncExportTaskService.cleanExpiredTasks(retention);
        }
    }
}
