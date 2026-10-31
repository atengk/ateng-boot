package io.github.atengk.boot.job.snail.actuator;

import com.aizuda.snailjob.client.common.rpc.client.grpc.GrpcChannel;
import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SnailJobHealthIndicator 健康探针单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobHealthIndicatorTest {

    private SnailJobProperties properties;
    private GracefulDrainStateMachine drainStateMachine;
    private SnailJobHealthIndicator indicator;

    @BeforeEach
    public void setUp() {
        properties = new SnailJobProperties();
        properties.setEnabled(true);
        properties.setServer("127.0.0.1:17888");
        properties.setGroup("test-group");
        properties.setNamespace("test-namespace");
        properties.setPort(17889);

        drainStateMachine = new GracefulDrainStateMachine(properties);
        indicator = new SnailJobHealthIndicator(properties, drainStateMachine);
    }

    @AfterEach
    public void tearDown() {
        resetGrpcChannel();
    }

    @Test
    public void testHealthWhenDisabled() {
        properties.setEnabled(false);
        Health health = indicator.health();
        assertNotNull(health);
        assertEquals(Status.UP, health.getStatus());
        assertEquals("DISABLED", health.getDetails().get("status"));
    }

    @Test
    public void testHealthWhenBypassed() {
        properties.setServer("");
        Health health = indicator.health();
        assertNotNull(health);
        assertEquals(Status.UNKNOWN, health.getStatus());
        assertEquals("BYPASSED", health.getDetails().get("status"));
    }

    @Test
    public void testHealthDownWhenServerUnreachableAndChannelNull() {
        properties.setServer("127.0.0.1:59999"); // unreachable port
        resetGrpcChannel();

        Health health = indicator.health();
        assertNotNull(health);
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(false, health.getDetails().get("serverReachable"));
        assertEquals(false, health.getDetails().get("channelActive"));
        assertEquals("test-group", health.getDetails().get("group"));
        assertEquals("test-namespace", health.getDetails().get("namespace"));
    }

    @Test
    public void testHealthUpWhenChannelActive() throws Exception {
        properties.setServer("127.0.0.1:59999");
        ManagedChannel mockChannel = Mockito.mock(ManagedChannel.class);
        Mockito.when(mockChannel.isShutdown()).thenReturn(false);
        Mockito.when(mockChannel.isTerminated()).thenReturn(false);

        Field channelField = GrpcChannel.class.getDeclaredField("channel");
        channelField.setAccessible(true);
        channelField.set(null, mockChannel);

        Health health = indicator.health();
        assertNotNull(health);
        assertEquals(Status.UP, health.getStatus());
        assertEquals(true, health.getDetails().get("channelActive"));
        assertEquals(false, health.getDetails().get("draining"));
    }

    @Test
    public void testHealthReflectsDrainingState() throws Exception {
        drainStateMachine.incrementInFlightJob();
        drainStateMachine.incrementInFlightRetry();
        drainStateMachine.setDraining(true);

        ManagedChannel mockChannel = Mockito.mock(ManagedChannel.class);
        Mockito.when(mockChannel.isShutdown()).thenReturn(false);
        Mockito.when(mockChannel.isTerminated()).thenReturn(false);

        Field channelField = GrpcChannel.class.getDeclaredField("channel");
        channelField.setAccessible(true);
        channelField.set(null, mockChannel);

        Health health = indicator.health();
        assertNotNull(health);
        assertEquals(true, health.getDetails().get("draining"));
        assertEquals(1, health.getDetails().get("inFlightJobs"));
        assertEquals(1, health.getDetails().get("inFlightRetries"));
    }

    private void resetGrpcChannel() {
        try {
            Field channelField = GrpcChannel.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            channelField.set(null, null);
        } catch (Throwable ignored) {
        }
    }
}
