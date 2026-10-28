package io.github.atengk.boot.rocketmq.helper;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * RocketMQ 消息载荷与辅助工具类
 *
 * <p>提供 GZIP 透明压缩解压、bizKey 多级自适应解析保底以及消息载荷序列化等通用辅助能力。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public final class RocketmqMessageHelper {

    private static final String[] BIZ_KEY_GETTER_NAMES = {"getId", "getBizKey", "getOrderNo", "getCode", "getKey"};

    private RocketmqMessageHelper() {
    }

    /**
     * 将对象载荷转为字节数组（默认纯净 JSON 序列化）
     *
     * @param payload 原始载荷
     * @return 字节数组
     */
    public static byte[] toPayloadBytes(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        if (payload instanceof byte[]) {
            return (byte[]) payload;
        }
        if (payload instanceof String) {
            return ((String) payload).getBytes(StandardCharsets.UTF_8);
        }
        return JsonUtil.toJsonBytes(payload);
    }

    /**
     * 解析业务唯一标识 bizKey（确保 100% 绝对非空）
     *
     * <ol>
     *   <li>显式传参优先；</li>
     *   <li>反射从载荷中提取常见主键属性（getId, getBizKey, getOrderNo 等）；</li>
     *   <li>读取当前线程 MDC traceId 兜底；</li>
     *   <li>最终采用无锁 fastSimpleUUID() 兜底。</li>
     * </ol>
     *
     * @param explicitBizKey 显式传递的 bizKey（可为空）
     * @param payload        消息载荷
     * @return 确定的业务唯一键
     */
    public static String resolveBizKey(String explicitBizKey, Object payload) {
        if (StringUtils.hasText(explicitBizKey)) {
            return explicitBizKey.trim();
        }

        // 尝试从载荷反射提取
        if (payload != null && !(payload instanceof byte[]) && !(payload instanceof String)) {
            for (String getterName : BIZ_KEY_GETTER_NAMES) {
                try {
                    Method method = payload.getClass().getMethod(getterName);
                    Object val = method.invoke(payload);
                    if (val != null && StringUtils.hasText(val.toString())) {
                        return val.toString().trim();
                    }
                } catch (Exception ignored) {
                    // 忽略反射未找到或调用失败
                }
            }
        }

        // 读取 MDC traceId 兜底（对齐 Core MessageContextPropagator 统一规范）
        String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        if (StringUtils.hasText(traceId)) {
            return traceId.trim();
        }

        // 保底生成 UUID
        return IdUtil.fastSimpleUUID();
    }

    /**
     * 从入站 MessageView 中对称提取确定性的业务唯一标识 bizKey
     *
     * <ol>
     *   <li>优先从用户扩展属性 {@code X-Biz-Key} 提取；</li>
     *   <li>若属性不存在，从 RocketMQ 原生 Keys 集合中获取首个有效 Key；</li>
     *   <li>若仍无有效标识，保底返回空字符串（""）。</li>
     * </ol>
     *
     * @param messageView 入站消息视图
     * @return 业务唯一标识（不为 null）
     */
    public static String extractBizKey(MessageView messageView) {
        if (messageView == null) {
            return "";
        }
        Map<String, String> properties = messageView.getProperties();
        if (properties != null) {
            String bizKey = properties.get(MessageHeaderConstants.BIZ_KEY);
            if (StringUtils.hasText(bizKey)) {
                return bizKey.trim();
            }
        }
        if (messageView.getKeys() != null && !messageView.getKeys().isEmpty()) {
            for (String key : messageView.getKeys()) {
                if (StringUtils.hasText(key)) {
                    return key.trim();
                }
            }
        }
        return "";
    }

    /**
     * GZIP 压缩字节数组
     *
     * @param data 原始字节数组
     * @return 压缩后字节数组
     * @throws IOException 压缩异常
     */
    public static byte[] compressGzip(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return data;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(data);
            gzipOut.finish();
        }
        return baos.toByteArray();
    }

    /**
     * GZIP 解压字节数组
     *
     * @param compressedData 压缩字节数组
     * @return 解压后原始字节数组
     * @throws IOException 解压异常
     */
    public static byte[] decompressGzip(byte[] compressedData) throws IOException {
        if (compressedData == null || compressedData.length == 0) {
            return compressedData;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gzipIn = new GZIPInputStream(new ByteArrayInputStream(compressedData))) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipIn.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
        }
        return baos.toByteArray();
    }
}
