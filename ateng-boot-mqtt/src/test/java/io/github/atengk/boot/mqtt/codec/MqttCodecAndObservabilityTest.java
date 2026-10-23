package io.github.atengk.boot.mqtt.codec;

import io.github.atengk.boot.mqtt.actuator.MqttHealthIndicator;
import io.github.atengk.boot.mqtt.annotation.MqttHeader;
import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.annotation.MqttTopic;
import io.github.atengk.boot.mqtt.annotation.MqttVariable;
import io.github.atengk.boot.mqtt.listener.MqttListenerMethodAdapter;
import io.github.atengk.boot.mqtt.metrics.MqttMetrics;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.properties.MqttSslProperties;
import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.ssl.MqttSslSocketFactoryBuilder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import javax.net.ssl.SSLSocketFactory;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MQTT 编解码 SPI、安全 SSL/TLS 与云原生可观测性单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class MqttCodecAndObservabilityTest {

    // ==================== 1. Hex 编解码器与实体测试 ====================

    @Test
    @DisplayName("测试 HexPayload 与 HexMqttMessageConverter 十六进制双向转译")
    void testHexMqttMessageConverter() {
        HexMqttMessageConverter converter = new HexMqttMessageConverter();

        // 1. 测试 HexPayload 构造与基础行为
        byte[] rawBytes = new byte[]{0x01, 0x0A, 0x1F, (byte) 0xFF};
        HexPayload payloadFromBytes = HexPayload.of(rawBytes);
        assertEquals("010a1fff", payloadFromBytes.getHexString().toLowerCase());
        assertArrayEquals(rawBytes, payloadFromBytes.getBytes());
        assertEquals("010a1fff", payloadFromBytes.toString());

        HexPayload payloadFromHex = HexPayload.of("010A1FFF");
        assertArrayEquals(rawBytes, payloadFromHex.getBytes());
        assertEquals(payloadFromBytes, payloadFromHex);
        assertEquals(payloadFromBytes.hashCode(), payloadFromHex.hashCode());

        // 2. 测试 Hex 转换器序列化 (toPayload)
        assertTrue(converter.supports(HexPayload.class));
        assertArrayEquals(rawBytes, converter.toPayload(payloadFromHex));

        // 3. 测试 Hex 转换器反序列化 (fromMessage)
        MqttMessage rawMsg = new MqttMessage(rawBytes);
        Object result = converter.fromMessage(rawMsg, HexPayload.class);
        assertInstanceOf(HexPayload.class, result);
        assertEquals("010a1fff", ((HexPayload) result).getHexString().toLowerCase());

        // 4. 空校验与容错防线
        assertEquals(0, HexPayload.of((byte[]) null).getBytes().length);
        assertEquals("", HexPayload.of((String) null).getHexString());
    }

    // ==================== 2. CompositeMqttMessageConverter 综合测试 ====================

    static class DeviceTelemetry {
        private String deviceId;
        private double temperature;

        public DeviceTelemetry() {}

        public DeviceTelemetry(String deviceId, double temperature) {
            this.deviceId = deviceId;
            this.temperature = temperature;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }
    }

    @Test
    @DisplayName("测试 CompositeMqttMessageConverter 多格式（ByteArray/String/Hex/Json）自适应转译")
    void testCompositeMqttMessageConverter() {
        CompositeMqttMessageConverter composite = new CompositeMqttMessageConverter();

        // 1. ByteArray
        byte[] bytes = new byte[]{1, 2, 3};
        assertArrayEquals(bytes, composite.toPayload(bytes));
        assertArrayEquals(bytes, (byte[]) composite.fromMessage(new MqttMessage(bytes), byte[].class));

        // 2. String
        String text = "hello mqtt";
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), composite.toPayload(text));
        assertEquals(text, composite.fromMessage(new MqttMessage(text.getBytes(StandardCharsets.UTF_8)), String.class));

        // 3. HexPayload
        HexPayload hexPayload = HexPayload.of("ABCD");
        assertArrayEquals(new byte[]{(byte) 0xAB, (byte) 0xCD}, composite.toPayload(hexPayload));
        HexPayload convertedHex = (HexPayload) composite.fromMessage(new MqttMessage(new byte[]{(byte) 0xAB, (byte) 0xCD}), HexPayload.class);
        assertEquals("abcd", convertedHex.getHexString().toLowerCase());

        // 4. JSON POJO
        DeviceTelemetry telemetry = new DeviceTelemetry("dev-001", 36.5);
        byte[] jsonBytes = composite.toPayload(telemetry);
        assertNotNull(jsonBytes);
        assertTrue(new String(jsonBytes, StandardCharsets.UTF_8).contains("dev-001"));

        DeviceTelemetry readPojo = (DeviceTelemetry) composite.fromMessage(new MqttMessage(jsonBytes), DeviceTelemetry.class);
        assertNotNull(readPojo);
        assertEquals("dev-001", readPojo.getDeviceId());
        assertEquals(36.5, readPojo.getTemperature());
    }

    // ==================== 3. 监听器形参自适应转译测试 ====================

    static class TestListenerService {
        final AtomicReference<HexPayload> receivedHex = new AtomicReference<>();
        final AtomicReference<DeviceTelemetry> receivedPojo = new AtomicReference<>();
        final AtomicReference<String> receivedTopic = new AtomicReference<>();
        final AtomicReference<String> receivedDevId = new AtomicReference<>();
        final AtomicReference<String> receivedTrace = new AtomicReference<>();

        @MqttListener(topics = "devices/+/hex")
        public void onHex(HexPayload hex, @MqttTopic String topic, @MqttVariable("0") String devId) {
            this.receivedHex.set(hex);
            this.receivedTopic.set(topic);
            this.receivedDevId.set(devId);
        }

        @MqttListener(topics = "devices/telemetry")
        public void onPojo(DeviceTelemetry pojo, @MqttHeader("x-trace-id") String traceId) {
            this.receivedPojo.set(pojo);
            this.receivedTrace.set(traceId);
        }
    }

    @Test
    @DisplayName("测试 MqttListenerMethodAdapter 注入 HexPayload 与 POJO 实体")
    void testMqttListenerMethodAdapter() throws Exception {
        CompositeMqttMessageConverter converter = new CompositeMqttMessageConverter();
        TestListenerService service = new TestListenerService();

        // 1. 测试 Hex 监听方法适配
        Method hexMethod = TestListenerService.class.getMethod("onHex", HexPayload.class, String.class, String.class);
        MqttListenerMethodAdapter hexAdapter = new MqttListenerMethodAdapter(service, hexMethod, converter);

        byte[] hexBytes = new byte[]{0x12, 0x34, 0x56};
        MqttMessage hexMsg = new MqttMessage(hexBytes);
        Map<String, String> variables = Map.of("0", "sensor-99");
        hexAdapter.invoke("devices/sensor-99/hex", hexMsg, variables);

        assertNotNull(service.receivedHex.get());
        assertEquals("123456", service.receivedHex.get().getHexString().toLowerCase());
        assertEquals("devices/sensor-99/hex", service.receivedTopic.get());
        assertEquals("sensor-99", service.receivedDevId.get());

        // 2. 测试 POJO 监听方法适配
        Method pojoMethod = TestListenerService.class.getMethod("onPojo", DeviceTelemetry.class, String.class);
        MqttListenerMethodAdapter pojoAdapter = new MqttListenerMethodAdapter(service, pojoMethod, converter);

        String json = "{\"deviceId\":\"sensor-88\",\"temperature\":28.4}";
        MqttMessage pojoMsg = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
        MqttProperties props = new MqttProperties();
        props.setUserProperties(List.of(new UserProperty("x-trace-id", "trace-xyz-123")));
        pojoMsg.setProperties(props);

        pojoAdapter.invoke("devices/telemetry", pojoMsg, Collections.emptyMap());

        assertNotNull(service.receivedPojo.get());
        assertEquals("sensor-88", service.receivedPojo.get().getDeviceId());
        assertEquals(28.4, service.receivedPojo.get().getTemperature());
        assertEquals("trace-xyz-123", service.receivedTrace.get());
    }

    // ==================== 4. SSL / mTLS 证书工厂测试 ====================

    @Test
    @DisplayName("测试 MqttSslSocketFactoryBuilder 构建 SSLContext 与未启用保底")
    void testSslSocketFactoryBuilderDisabled() throws Exception {
        MqttSslProperties disabledProps = new MqttSslProperties();
        disabledProps.setEnabled(false);
        assertNull(MqttSslSocketFactoryBuilder.build(disabledProps));
        assertNull(MqttSslSocketFactoryBuilder.buildSslContext(disabledProps));
        assertNull(MqttSslSocketFactoryBuilder.build(null));
    }

    @Test
    @DisplayName("测试 MqttSslSocketFactoryBuilder 默认 TLS 上下文构建")
    void testSslSocketFactoryBuilderDefaultTls() throws Exception {
        MqttSslProperties defaultTls = new MqttSslProperties();
        defaultTls.setEnabled(true);
        defaultTls.setProtocol("TLSv1.3");

        assertNotNull(MqttSslSocketFactoryBuilder.buildSslContext(defaultTls));
        SSLSocketFactory socketFactory = MqttSslSocketFactoryBuilder.build(defaultTls);
        assertNotNull(socketFactory);
    }

    @Test
    @DisplayName("测试 MqttSslSocketFactoryBuilder 解析 KeyStore 与 TrustStore 介质")
    void testSslSocketFactoryBuilderKeyStore(@TempDir Path tempDir) throws Exception {
        // 创建一个内存 PKCS12 KeyStore 并落盘
        KeyStore ks = KeyStore.getInstance("PKCS12");
        char[] password = "secretPassword123".toCharArray();
        ks.load(null, password);

        Path keyStorePath = tempDir.resolve("test-keystore.p12");
        try (FileOutputStream fos = new FileOutputStream(keyStorePath.toFile())) {
            ks.store(fos, password);
        }

        MqttSslProperties props = new MqttSslProperties();
        props.setEnabled(true);
        props.setKeyStore(keyStorePath.toAbsolutePath().toString());
        props.setKeyStorePassword("secretPassword123");
        props.setTrustStore(keyStorePath.toAbsolutePath().toString());
        props.setTrustStorePassword("secretPassword123");

        SSLSocketFactory sf = MqttSslSocketFactoryBuilder.build(props);
        assertNotNull(sf);
    }

    // ==================== 5. Actuator 健康探针测试 ====================

    @Test
    @DisplayName("测试 MqttHealthIndicator 在所有客户端连接正常时输出 UP")
    void testHealthIndicatorUp() {
        NamedMqttClientRegistry registry = mock(NamedMqttClientRegistry.class);
        MqttClientHandle handle = mock(MqttClientHandle.class);
        MqttClientProperties clientProps = new MqttClientProperties();
        clientProps.setServerUri("tcp://127.0.0.1:1883");
        clientProps.setAutomaticReconnect(true);

        MqttAsyncClient asyncClient = mock(MqttAsyncClient.class);
        when(asyncClient.getBufferedMessageCount()).thenReturn(5);

        when(handle.isConnected()).thenReturn(true);
        when(handle.getActualClientId()).thenReturn("test-client-123");
        when(handle.getClientProperties()).thenReturn(clientProps);
        when(handle.getAsyncClient()).thenReturn(asyncClient);

        when(registry.getClientHandles()).thenReturn(Map.of("default", handle));

        MqttHealthIndicator healthIndicator = new MqttHealthIndicator(registry);
        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        Map<String, Object> details = health.getDetails();
        assertNotNull(details.get("clients"));
        @SuppressWarnings("unchecked")
        Map<String, Object> clients = (Map<String, Object>) details.get("clients");
        @SuppressWarnings("unchecked")
        Map<String, Object> defaultClient = (Map<String, Object>) clients.get("default");
        assertEquals(true, defaultClient.get("connected"));
        assertEquals("test-client-123", defaultClient.get("clientId"));
        assertEquals("tcp://127.0.0.1:1883", defaultClient.get("serverUri"));
        assertEquals(5, defaultClient.get("bufferedMessages"));
    }

    @Test
    @DisplayName("测试 MqttHealthIndicator 在客户端断连时输出 DOWN")
    void testHealthIndicatorDown() {
        NamedMqttClientRegistry registry = mock(NamedMqttClientRegistry.class);
        MqttClientHandle handle = mock(MqttClientHandle.class);
        MqttClientProperties clientProps = new MqttClientProperties();
        clientProps.setServerUri("tcp://127.0.0.1:1883");

        MqttAsyncClient asyncClient = mock(MqttAsyncClient.class);
        when(asyncClient.getBufferedMessageCount()).thenReturn(0);

        when(handle.isConnected()).thenReturn(false);
        when(handle.getActualClientId()).thenReturn("test-client-down");
        when(handle.getClientProperties()).thenReturn(clientProps);
        when(handle.getAsyncClient()).thenReturn(asyncClient);

        when(registry.getClientHandles()).thenReturn(Map.of("broken-client", handle));

        MqttHealthIndicator healthIndicator = new MqttHealthIndicator(registry);
        Health health = healthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("One or more MQTT clients are disconnected", health.getDetails().get("reason"));
    }

    @Test
    @DisplayName("测试 MqttHealthIndicator 在无客户端注册时输出 UP")
    void testHealthIndicatorEmpty() {
        NamedMqttClientRegistry registry = mock(NamedMqttClientRegistry.class);
        when(registry.getClientHandles()).thenReturn(Collections.emptyMap());

        MqttHealthIndicator healthIndicator = new MqttHealthIndicator(registry);
        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("No MQTT clients configured or running", health.getDetails().get("status"));
    }

    // ==================== 6. Micrometer 度量指标测试 ====================

    @Test
    @DisplayName("测试 MqttMetrics 连接 Gauge、发信 Counter、收信 Counter、耗时 Timer 与死信 Counter")
    void testMqttMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MqttMetrics metrics = new MqttMetrics(meterRegistry);

        // 1. 测试发信 Counter
        metrics.recordPublished("primary", "telemetry/data", 1);
        metrics.recordPublished("primary", "telemetry/data", 1);
        Counter publishCounter = meterRegistry.find("mqtt.message.published")
                .tag("client", "primary")
                .tag("topic", "telemetry/data")
                .tag("qos", "1")
                .counter();
        assertNotNull(publishCounter);
        assertEquals(2.0, publishCounter.count());

        // 2. 测试收信 Counter
        metrics.recordReceived("primary", "devices/alerts");
        Counter receiveCounter = meterRegistry.find("mqtt.message.received")
                .tag("client", "primary")
                .tag("topic", "devices/alerts")
                .counter();
        assertNotNull(receiveCounter);
        assertEquals(1.0, receiveCounter.count());

        // 3. 测试消费耗时 Timer
        metrics.recordConsume("bean#onAlert", "devices/alerts", true, 5_000_000L); // 5ms
        Timer consumeTimer = meterRegistry.find("mqtt.consumer.latency")
                .tag("endpoint", "bean#onAlert")
                .tag("topic", "devices/alerts")
                .tag("status", "success")
                .timer();
        assertNotNull(consumeTimer);
        assertEquals(1, consumeTimer.count());
        assertTrue(consumeTimer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 4.0);

        // 4. 测试死信 DLT Counter
        metrics.recordDlt("bean#onAlert", "devices/alerts");
        Counter dltCounter = meterRegistry.find("mqtt.consumer.dlt")
                .tag("endpoint", "bean#onAlert")
                .tag("topic", "devices/alerts")
                .counter();
        assertNotNull(dltCounter);
        assertEquals(1.0, dltCounter.count());

        // 5. 测试客户端连接 Gauge 绑定
        NamedMqttClientRegistry registry = mock(NamedMqttClientRegistry.class);
        MqttClientHandle handle = mock(MqttClientHandle.class);
        when(handle.isConnected()).thenReturn(true);
        when(registry.getClientHandles()).thenReturn(Map.of("edge-client", handle));

        metrics.bindClientRegistry(registry);
        Gauge gauge = meterRegistry.find("mqtt.client.status")
                .tag("client", "edge-client")
                .gauge();
        assertNotNull(gauge);
        assertEquals(1.0, gauge.value());

        // 模拟断开
        when(handle.isConnected()).thenReturn(false);
        assertEquals(0.0, gauge.value());
    }
}
