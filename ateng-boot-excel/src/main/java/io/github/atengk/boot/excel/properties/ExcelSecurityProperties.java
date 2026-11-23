package io.github.atengk.boot.excel.properties;

import lombok.Data;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Excel 安全防护与公式注入防御配置属性
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
public class ExcelSecurityProperties {

    /**
     * 是否开启公式注入安全防御（默认开启，导出时对以 =, +, -, @, \t, \r 开头的字符串前置单引号，导入时自动逆向还原）
     */
    private boolean formulaProtection = true;

    /**
     * 单张多媒体图片最大允许字节容量（默认 10MB，防止超大图片或恶意分块响应导致 OOM）
     */
    private DataSize maxImageSize = DataSize.ofMegabytes(10);

    /**
     * 远程图片 HTTP 连接超时时间（默认 3s）
     */
    private Duration imageConnectTimeout = Duration.ofSeconds(3);

    /**
     * 远程图片 HTTP 读取超时时间（默认 10s）
     */
    private Duration imageReadTimeout = Duration.ofSeconds(10);

    /**
     * 是否拦截内网、环回地址与云元数据链路本地地址的图片 URL（默认 true，防御 SSRF）
     */
    private boolean blockInternalImageUrls = true;

    /**
     * 是否允许从本地文件系统加载 @ExcelImage 本地路径图片（默认 true）
     */
    private boolean allowLocalImageFile = true;

    /**
     * 本地图片允许访问的受管基目录（留空则仅阻断路径穿越与系统敏感目录；配置后强制校验路径位于该基目录下）
     */
    private String allowedImageBaseDir;
}
