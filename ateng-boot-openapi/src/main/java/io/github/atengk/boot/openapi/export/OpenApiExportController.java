package io.github.atengk.boot.openapi.export;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * OpenAPI 离线契约导出受管 REST 控制器
 * <p>
 * 提供受管 HTTP 端点用于下载格式化的 OpenAPI 规范（JSON / YAML）：
 * <ul>
 *     <li>{@code GET /docs/export/json}：导出默认全量分组的 JSON 规范文件；</li>
 *     <li>{@code GET /docs/export/yaml}：导出默认全量分组的 YAML 规范文件；</li>
 *     <li>{@code GET /docs/export/{group}.json}：导出指定分组的 JSON 规范文件；</li>
 *     <li>{@code GET /docs/export/{group}.yaml}：导出指定分组的 YAML 规范文件。</li>
 * </ul>
 * 响应自动挂载 {@code Content-Disposition: attachment; filename="openapi-{group}.json"} 触发浏览器一键下载。
 * 此端点受 {@link io.github.atengk.boot.openapi.guard.OpenApiGuardFilter} 统一安全守卫拦截管辖。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@RestController
@RequestMapping("/docs/export")
public class OpenApiExportController {

    private final OpenApiExportHelper exportHelper;

    /**
     * 构造 OpenAPI 离线导出控制器
     *
     * @param exportHelper 离线契约导出门面助手
     */
    public OpenApiExportController(OpenApiExportHelper exportHelper) {
        this.exportHelper = Objects.requireNonNull(exportHelper, "exportHelper must not be null");
    }

    /**
     * 下载默认分组的 OpenAPI JSON 规范
     *
     * @param request HTTP 请求
     * @return 附件文件响应实体
     */
    @GetMapping(value = "/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportDefaultJson(HttpServletRequest request) {
        return doExport(null, OpenApiFormat.JSON, request);
    }

    /**
     * 下载默认分组的 OpenAPI YAML 规范
     *
     * @param request HTTP 请求
     * @return 附件文件响应实体
     */
    @GetMapping(value = "/yaml", produces = "application/yaml;charset=UTF-8")
    public ResponseEntity<byte[]> exportDefaultYaml(HttpServletRequest request) {
        return doExport(null, OpenApiFormat.YAML, request);
    }

    /**
     * 下载指定分组的 OpenAPI JSON 规范
     *
     * @param group   业务分组名称
     * @param request HTTP 请求
     * @return 附件文件响应实体
     */
    @GetMapping(value = "/{group}.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportGroupJson(@PathVariable("group") String group, HttpServletRequest request) {
        return doExport(group, OpenApiFormat.JSON, request);
    }

    /**
     * 下载指定分组的 OpenAPI YAML 规范
     *
     * @param group   业务分组名称
     * @param request HTTP 请求
     * @return 附件文件响应实体
     */
    @GetMapping(value = "/{group}.yaml", produces = "application/yaml;charset=UTF-8")
    public ResponseEntity<byte[]> exportGroupYaml(@PathVariable("group") String group, HttpServletRequest request) {
        return doExport(group, OpenApiFormat.YAML, request);
    }

    private ResponseEntity<byte[]> doExport(String group, OpenApiFormat format, HttpServletRequest request) {
        try {
            String targetGroup = StringUtils.hasText(group) ? group.trim() : "default";
            byte[] bytes = exportHelper.exportBytes(group, format, request);
            String filename = "openapi-" + targetGroup + "." + format.getExtension();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(format.getMediaType())
                    .body(bytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}
