package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.event.StorageDeletedEvent;
import io.github.atengk.boot.storage.event.StorageEvent;
import io.github.atengk.boot.storage.event.StorageUploadedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * StorageTemplate Spring 原生存储事件广播与 Fail-Safe 异常隔离测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("StorageTemplate 存储事件广播与隔离测试")
class StorageTemplateEventTest {

    @TempDir
    Path tempDir;

    private StorageTemplate template;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        LocalDriver driver = new LocalDriver(tempDir.toString());
        template = new StorageTemplate(driver);
        eventPublisher = mock(ApplicationEventPublisher.class);
        template.setApplicationEventPublisher(eventPublisher);
    }

    @Test
    @DisplayName("上传对象发布 StorageUploadedEvent 事件")
    void testUploadPublishesStorageUploadedEvent() {
        byte[] data = "Event Payload Data".getBytes(StandardCharsets.UTF_8);
        template.upload("events/test.txt", new ByteArrayInputStream(data));

        ArgumentCaptor<StorageEvent> captor = ArgumentCaptor.forClass(StorageEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());

        StorageEvent captured = captor.getValue();
        assertTrue(captured instanceof StorageUploadedEvent);
        StorageUploadedEvent uploadedEvent = (StorageUploadedEvent) captured;
        assertEquals("events/test.txt", uploadedEvent.getPath());
        assertEquals((long) data.length, uploadedEvent.getSize());
        assertNotNull(uploadedEvent.getSourceKey());
    }

    @Test
    @DisplayName("删除对象发布 StorageDeletedEvent 事件")
    void testDeletePublishesStorageDeletedEvent() {
        template.uploadString("events/delete.txt", "To Be Deleted");
        template.delete("events/delete.txt");

        ArgumentCaptor<StorageEvent> captor = ArgumentCaptor.forClass(StorageEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        List<StorageEvent> allEvents = captor.getAllValues();
        assertEquals(2, allEvents.size());
        assertTrue(allEvents.get(0) instanceof StorageUploadedEvent);
        assertTrue(allEvents.get(1) instanceof StorageDeletedEvent);
        assertEquals("events/delete.txt", allEvents.get(1).getPath());
    }

    @Test
    @DisplayName("移动对象分别发布目标上传与源删除事件")
    void testMovePublishesUploadedAndDeletedEvents() {
        template.uploadString("move/src.txt", "Moving content");
        template.move("move/src.txt", "move/target.txt");

        ArgumentCaptor<StorageEvent> captor = ArgumentCaptor.forClass(StorageEvent.class);
        // upload (1) + move目标upload (2) + move源delete (3)
        verify(eventPublisher, times(3)).publishEvent(captor.capture());

        List<StorageEvent> events = captor.getAllValues();
        assertEquals("move/src.txt", events.get(0).getPath());
        assertTrue(events.get(0) instanceof StorageUploadedEvent);

        assertEquals("move/target.txt", events.get(1).getPath());
        assertTrue(events.get(1) instanceof StorageUploadedEvent);

        assertEquals("move/src.txt", events.get(2).getPath());
        assertTrue(events.get(2) instanceof StorageDeletedEvent);
    }

    @Test
    @DisplayName("Fail-Safe 异常隔离：外部事件监听器异常不阻断存储核心操作")
    void testFailSafeWhenListenerThrowsException() {
        doThrow(new RuntimeException("Simulated external listener error"))
                .when(eventPublisher).publishEvent(any());

        // 上传操作正常执行，不抛出异常
        assertDoesNotThrow(() ->
                template.uploadString("failsafe/safe.txt", "Fail safe test")
        );
        assertTrue(template.exists("failsafe/safe.txt"));

        // 删除操作正常执行，不抛出异常
        assertDoesNotThrow(() ->
                template.delete("failsafe/safe.txt")
        );
    }

    @Test
    @DisplayName("未注入 ApplicationEventPublisher 时静默正常运行")
    void testNullEventPublisherSafety() {
        LocalDriver driver = new LocalDriver(tempDir.toString());
        StorageTemplate isolatedTemplate = new StorageTemplate(driver);
        // 故意不注入 eventPublisher

        assertDoesNotThrow(() -> {
            isolatedTemplate.uploadString("isolated/doc.txt", "No publisher");
            isolatedTemplate.delete("isolated/doc.txt");
        });
    }
}
