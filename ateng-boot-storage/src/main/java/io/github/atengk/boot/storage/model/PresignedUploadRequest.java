package io.github.atengk.boot.storage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.unit.DataSize;

import java.io.Serializable;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * 客户端预签名直传外链请求模型
 * 封装目标存储路径、授权时效、限定 MIME 类型、最大字节限制与必须附加的 HTTP 请求头
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresignedUploadRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标存储相对路径（必填）
     */
    private String path;

    /**
     * 预签名有效时长（可选，默认 15 分钟）
     */
    @Builder.Default
    private Duration duration = Duration.ofMinutes(15);

    /**
     * 严格限定客户端直传的 MIME 内容类型（可选，若提供则服务端与云存储均强校验该类型）
     */
    private String contentType;

    /**
     * 允许客户端上传的最大文件字节大小（可选，<= 0 表示不限制大小）
     */
    @Builder.Default
    private long maxSizeBytes = -1;

    /**
     * 目标存储源名称（可选，多存储源场景下指定）
     */
    private String source;

    /**
     * 客户端发起直传时必须携带的 HTTP Headers 报头映射（可选）
     */
    @Builder.Default
    private Map<String, String> headers = Collections.emptyMap();

    /**
     * 快捷设置最大上传文件体积（采用 Spring 强类型 DataSize）
     *
     * @param dataSize 容量规格
     */
    public void setMaxPayloadSize(DataSize dataSize) {
        this.maxSizeBytes = dataSize != null ? dataSize.toBytes() : -1;
    }

    /**
     * 获取强类型 DataSize 容量规格
     *
     * @return DataSize 对象，若未设置或 <= 0 则返回 null
     */
    public DataSize getMaxPayloadSize() {
        return this.maxSizeBytes > 0 ? DataSize.ofBytes(this.maxSizeBytes) : null;
    }

    public static class PresignedUploadRequestBuilder {
        public PresignedUploadRequestBuilder maxPayloadSize(DataSize dataSize) {
            this.maxSizeBytes$value = dataSize != null ? dataSize.toBytes() : -1;
            this.maxSizeBytes$set = true;
            return this;
        }
    }
}
