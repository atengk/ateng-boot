package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.storage.context.StorageContextHolder;
import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.event.StorageDeletedEvent;
import io.github.atengk.boot.storage.event.StorageEvent;
import io.github.atengk.boot.storage.event.StorageUploadedEvent;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.model.StorageUploadRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * StorageTemplate Spring 事务感知存储协同（提交后延迟删除与回滚防孤儿补偿）单元测试
 *
 * @author Ateng
 * @since 2026-11-14
 */
@DisplayName("StorageTemplate 事务感知存储协同测试")
class StorageTemplateTransactionTest {

    @TempDir
    Path tempDir;

    private LocalDriver driver;
    private StorageTemplate template;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        driver = new LocalDriver(tempDir.toString());
        template = new StorageTemplate(driver);
        eventPublisher = mock(ApplicationEventPublisher.class);
        template.setApplicationEventPublisher(eventPublisher);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        StorageContextHolder.clear();
    }

    @Test
    @DisplayName("活动事务下 deleteAfterCommit 提交前保留对象，提交后删除并发布事件，回滚时完好保留")
    void testDeleteAfterCommitAndRollbackBehavior() {
        template.uploadString("tx/commit-target.txt", "To be deleted on commit");
        template.uploadString("tx/rollback-target.txt", "Keep on rollback");
        org.mockito.Mockito.clearInvocations(eventPublisher);

        // 1. 活动事务正常提交场景
        TransactionSynchronizationManager.initSynchronization();
        template.deleteAfterCommit("tx/commit-target.txt");
        assertTrue(template.exists("tx/commit-target.txt"), "事务未提交前不得提前物理删除");
        verify(eventPublisher, never()).publishEvent(any(StorageDeletedEvent.class));

        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        assertFalse(template.exists("tx/commit-target.txt"), "事务提交后应延迟物理删除对象");

        ArgumentCaptor<StorageEvent> captor = ArgumentCaptor.forClass(StorageEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        assertTrue(captor.getValue() instanceof StorageDeletedEvent);
        assertEquals("tx/commit-target.txt", captor.getValue().getPath());

        // 2. 活动事务回滚场景
        TransactionSynchronizationManager.clearSynchronization();
        org.mockito.Mockito.clearInvocations(eventPublisher);
        TransactionSynchronizationManager.initSynchronization();
        template.deleteAfterCommit("tx/rollback-target.txt");

        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertTrue(template.exists("tx/rollback-target.txt"), "事务回滚后原对象必须完好保留");
        verify(eventPublisher, never()).publishEvent(any(StorageDeletedEvent.class));
    }

    @Test
    @DisplayName("活动事务下 deleteBatchAfterCommit 支持提交后批量删除、回滚保留及防外部列表篡改")
    void testDeleteBatchAfterCommit() {
        template.uploadString("batch/1.txt", "1");
        template.uploadString("batch/2.txt", "2");
        template.uploadString("batch/3.txt", "3");

        // 空集合或 null 安全空操作
        assertDoesNotThrow(() -> template.deleteBatchAfterCommit(null));
        assertDoesNotThrow(() -> template.deleteBatchAfterCommit(Collections.emptyList()));

        TransactionSynchronizationManager.initSynchronization();
        List<String> mutableList = new ArrayList<>(List.of("batch/1.txt", "batch/2.txt"));
        template.deleteBatchAfterCommit(mutableList);
        // 外部后续篡改列表不应影响已捕获的删除快照
        mutableList.clear();

        assertTrue(template.exists("batch/1.txt"));
        assertTrue(template.exists("batch/2.txt"));

        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        assertFalse(template.exists("batch/1.txt"));
        assertFalse(template.exists("batch/2.txt"));
        assertTrue(template.exists("batch/3.txt"));

        // 事务回滚时批量删除不执行
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        template.deleteBatchAfterCommit(List.of("batch/3.txt"));
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertTrue(template.exists("batch/3.txt"));
    }

    @Test
    @DisplayName("活动事务下 uploadWithRollbackDelete 三类重载及 registerRollbackDelete 提交保留、回滚自动清理")
    void testUploadWithRollbackDeleteAndRegisterRollbackDelete() {
        // 1. 事务提交：所有上传与登记对象永久保留
        TransactionSynchronizationManager.initSynchronization();
        MockMultipartFile mpCommit = new MockMultipartFile("file", "keep.txt", "text/plain", "keep-mp".getBytes(StandardCharsets.UTF_8));
        StorageObject mpObj = template.uploadWithRollbackDelete(mpCommit, "keep/mp.txt");
        byte[] streamBytes = "keep-stream".getBytes(StandardCharsets.UTF_8);
        StorageObject streamObj = template.uploadWithRollbackDelete(new ByteArrayInputStream(streamBytes), "keep/stream.txt", "text/plain", streamBytes.length);
        StorageObject reqObj = template.uploadWithRollbackDelete(StorageUploadRequest.builder()
                .path("keep/req.json")
                .inputStream(new ByteArrayInputStream("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)))
                .contentType("application/json")
                .build());
        template.uploadString("keep/manual.txt", "manual");
        template.registerRollbackDelete("keep/manual.txt");

        assertNotNull(mpObj);
        assertNotNull(streamObj);
        assertNotNull(reqObj);

        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        assertTrue(template.exists("keep/mp.txt"));
        assertTrue(template.exists("keep/stream.txt"));
        assertTrue(template.exists("keep/req.json"));
        assertTrue(template.exists("keep/manual.txt"));

        // 2. 事务回滚：自动补偿清理刚上传与登记的对象，消除孤儿文件
        TransactionSynchronizationManager.clearSynchronization();
        org.mockito.Mockito.clearInvocations(eventPublisher);
        TransactionSynchronizationManager.initSynchronization();

        MockMultipartFile mpRollback = new MockMultipartFile("file", "orphan.txt", "text/plain", "orphan-mp".getBytes(StandardCharsets.UTF_8));
        template.uploadWithRollbackDelete(mpRollback, "orphan/");
        byte[] orphanStream = "orphan-stream".getBytes(StandardCharsets.UTF_8);
        template.uploadWithRollbackDelete(new ByteArrayInputStream(orphanStream), "orphan/stream.txt", "text/plain", orphanStream.length);
        template.uploadWithRollbackDelete(StorageUploadRequest.builder()
                .path("orphan/req.txt")
                .inputStream(new ByteArrayInputStream("orphan-req".getBytes(StandardCharsets.UTF_8)))
                .contentType("text/plain")
                .build());
        template.uploadString("orphan/manual.txt", "orphan-manual");
        template.registerRollbackDelete("orphan/manual.txt");

        assertTrue(template.exists("orphan/orphan.txt"));
        assertTrue(template.exists("orphan/stream.txt"));
        assertTrue(template.exists("orphan/req.txt"));
        assertTrue(template.exists("orphan/manual.txt"));

        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertFalse(template.exists("orphan/orphan.txt"));
        assertFalse(template.exists("orphan/stream.txt"));
        assertFalse(template.exists("orphan/req.txt"));
        assertFalse(template.exists("orphan/manual.txt"));
        verify(eventPublisher, times(4)).publishEvent(any(StorageDeletedEvent.class));
    }

    @Test
    @DisplayName("非事务普通线程平滑降级：deleteAfterCommit 立即删除，uploadWithRollbackDelete 正常保留，registerRollbackDelete 静默忽略")
    void testNonTransactionGracefulDegradation() {
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());

        template.uploadString("direct/single.txt", "single");
        template.deleteAfterCommit("direct/single.txt");
        assertFalse(template.exists("direct/single.txt"));

        template.uploadString("direct/b1.txt", "b1");
        template.uploadString("direct/b2.txt", "b2");
        template.deleteBatchAfterCommit(List.of("direct/b1.txt", "direct/b2.txt"));
        assertFalse(template.exists("direct/b1.txt"));
        assertFalse(template.exists("direct/b2.txt"));

        MockMultipartFile mp = new MockMultipartFile("file", "normal.txt", "text/plain", "normal".getBytes(StandardCharsets.UTF_8));
        StorageObject uploaded = template.uploadWithRollbackDelete(mp, "direct/normal.txt");
        assertEquals("direct/normal.txt", uploaded.getPath());
        assertTrue(template.exists("direct/normal.txt"));

        assertDoesNotThrow(() -> template.registerRollbackDelete("direct/normal.txt"));
        assertTrue(template.exists("direct/normal.txt"));
    }

    @Test
    @DisplayName("多存储源切换场景下回调精准锁定注册时捕获的目标存储源（免疫后续上下文切换）")
    void testSourceHandleCapturedAtRegistrationTime(@TempDir Path archiveDir) {
        LocalDriver archiveDriver = new LocalDriver(archiveDir.toString());
        StorageTemplate multiTemplate = new StorageTemplate(driver, Map.of("primary", driver, "archive", archiveDriver));
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        multiTemplate.setApplicationEventPublisher(publisher);

        // 在主源与归档源写入同名路径用于区分验证
        multiTemplate.uploadString("shared/doc.txt", "primary-data");
        multiTemplate.use("archive").uploadString("shared/doc.txt", "archive-data");
        multiTemplate.uploadString("shared/rollback.txt", "primary-rollback-keep");

        // Case A: 在 StorageContextHolder("archive") 下注册提交后删除，随后切回主源后再触发提交
        TransactionSynchronizationManager.initSynchronization();
        StorageContextHolder.runWith("archive", () -> multiTemplate.deleteAfterCommit("shared/doc.txt"));
        org.mockito.Mockito.clearInvocations(publisher);

        // 提交前切换到默认源甚至未绑定态
        StorageContextHolder.clear();
        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        assertFalse(archiveDriver.exists("shared/doc.txt"), "归档源对象应在提交后被删除");
        assertTrue(driver.exists("shared/doc.txt"), "主源同名对象不得被误删");
        ArgumentCaptor<StorageEvent> delCaptor = ArgumentCaptor.forClass(StorageEvent.class);
        verify(publisher, times(1)).publishEvent(delCaptor.capture());
        assertEquals("archive", delCaptor.getValue().getSourceKey());

        // Case B: 在默认源下注册延迟删除，但提交前线程上下文切换到 archive —— 仍应仅删除主源
        TransactionSynchronizationManager.clearSynchronization();
        multiTemplate.use("archive").uploadString("shared/doc2.txt", "archive-keep");
        multiTemplate.uploadString("shared/doc2.txt", "primary-delete");
        TransactionSynchronizationManager.initSynchronization();
        multiTemplate.deleteAfterCommit("shared/doc2.txt");

        StorageContextHolder.set("archive");
        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        StorageContextHolder.clear();

        assertFalse(driver.exists("shared/doc2.txt"), "应删除注册时绑定的主源对象");
        assertTrue(archiveDriver.exists("shared/doc2.txt"), "后续切到 archive 不得影响已注册回调的目标源");

        // Case C: 在 use("archive") 上调用 uploadWithRollbackDelete，随后切回默认源并触发回滚
        TransactionSynchronizationManager.clearSynchronization();
        org.mockito.Mockito.clearInvocations(publisher);
        TransactionSynchronizationManager.initSynchronization();
        byte[] content = "archive-orphan".getBytes(StandardCharsets.UTF_8);
        multiTemplate.use("archive").uploadWithRollbackDelete(
                new ByteArrayInputStream(content), "shared/rollback.txt", "text/plain", content.length);
        assertTrue(archiveDriver.exists("shared/rollback.txt"));

        StorageContextHolder.clear();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertFalse(archiveDriver.exists("shared/rollback.txt"), "归档源孤儿对象应在回滚后清理");
        assertTrue(driver.exists("shared/rollback.txt"), "主源同名对象不得受归档源回滚影响");
        ArgumentCaptor<StorageEvent> rbCaptor = ArgumentCaptor.forClass(StorageEvent.class);
        verify(publisher, times(2)).publishEvent(rbCaptor.capture());
        StorageEvent rollbackDeleteEvent = rbCaptor.getAllValues().get(1);
        assertTrue(rollbackDeleteEvent instanceof StorageDeletedEvent);
        assertEquals("archive", rollbackDeleteEvent.getSourceKey());
    }

    @Test
    @DisplayName("回调异常隔离与非法路径前置快速失败")
    void testCallbackExceptionIsolationAndFailFastValidation() {
        StorageDriver failingDeleteDriver = spy(driver);
        doThrow(new StorageException(StorageResultCode.DELETE_FAILED, "Simulated delete failure"))
                .when(failingDeleteDriver).delete("fail/object.txt");
        StorageTemplate resilientTemplate = new StorageTemplate(failingDeleteDriver);

        // 非法路径在注册前即快速失败
        StorageException badPath1 = assertThrows(StorageException.class, () -> resilientTemplate.deleteAfterCommit("../evil.txt"));
        assertEquals(StorageResultCode.INVALID_PATH, badPath1.getResultCode());
        StorageException badPath2 = assertThrows(StorageException.class, () -> resilientTemplate.registerRollbackDelete("  "));
        assertEquals(StorageResultCode.INVALID_PATH, badPath2.getResultCode());
        StorageException badPath3 = assertThrows(StorageException.class, () -> resilientTemplate.deleteBatchAfterCommit(List.of("ok.txt", "../bad.txt")));
        assertEquals(StorageResultCode.INVALID_PATH, badPath3.getResultCode());

        // 提交与回滚回调内部异常受控隔离，不向上抛破事务收尾流程
        TransactionSynchronizationManager.initSynchronization();
        resilientTemplate.deleteAfterCommit("fail/object.txt");
        resilientTemplate.registerRollbackDelete("fail/object.txt");
        assertDoesNotThrow(() -> {
            TransactionSynchronizationUtils.triggerAfterCommit();
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        });

        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        resilientTemplate.registerRollbackDelete("fail/object.txt");
        assertDoesNotThrow(() -> TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }
}
