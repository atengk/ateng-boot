package io.github.atengk.boot.web.shutdown;

import io.github.atengk.boot.core.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Kubernetes 容器生命周期 PreStop 轻量级 Spring MVC 降级控制器
 * <p>
 * 在未引入 Spring Boot Actuator 或显式开启 MVC 端点时注册，提供 /pre-stop HTTP 端点，
 * 供 Kubernetes Pod lifecycle preStop 钩子调用。
 *
 * @author Ateng
 * @since 2026-10-18
 */
@RestController
@RequestMapping("${ateng.boot.web.shutdown.mvc-endpoint-path:/pre-stop}")
public class PreStopController {

    private final AtengGracefulShutdownHandler gracefulShutdownHandler;

    public PreStopController(AtengGracefulShutdownHandler gracefulShutdownHandler) {
        this.gracefulShutdownHandler = gracefulShutdownHandler;
    }

    /**
     * HTTP GET 请求处理
     *
     * @return 统一封装的排空结果响应
     */
    @GetMapping
    public Result<Map<String, Object>> preStopGet() {
        return Result.success(gracefulShutdownHandler.drain().toMap());
    }

    /**
     * HTTP POST 请求处理
     *
     * @return 统一封装的排空结果响应
     */
    @PostMapping
    public Result<Map<String, Object>> preStopPost() {
        return Result.success(gracefulShutdownHandler.drain().toMap());
    }
}
