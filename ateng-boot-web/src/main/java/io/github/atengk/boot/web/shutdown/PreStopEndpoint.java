package io.github.atengk.boot.web.shutdown;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.Map;

/**
 * Kubernetes 容器生命周期 PreStop 主动排空 Actuator 端点
 * <p>
 * 供 Kubernetes Pod lifecycle preStop 钩子调用（默认路径：/actuator/pre-stop）。
 * 兼容 GET 与 POST 调用，触发就绪探针置为 REFUSING_TRAFFIC 并执行静默流量排空。
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Endpoint(id = "preStop")
public class PreStopEndpoint {

    private final AtengGracefulShutdownHandler gracefulShutdownHandler;

    public PreStopEndpoint(AtengGracefulShutdownHandler gracefulShutdownHandler) {
        this.gracefulShutdownHandler = gracefulShutdownHandler;
    }

    /**
     * HTTP GET 端点响应
     *
     * @return 排空结果摘要
     */
    @ReadOperation
    public Map<String, Object> preStopGet() {
        return gracefulShutdownHandler.drain().toMap();
    }

    /**
     * HTTP POST 端点响应
     *
     * @return 排空结果摘要
     */
    @WriteOperation
    public Map<String, Object> preStopPost() {
        return gracefulShutdownHandler.drain().toMap();
    }
}
