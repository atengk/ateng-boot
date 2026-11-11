package io.github.atengk.boot.sample.admin.support;

import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 样例实例状态文本与 SBA StatusInfo 转换辅助工具
 *
 * @author Ateng
 * @since 2026-11-11
 */
public final class SampleStatusConverter {

    private SampleStatusConverter() {
    }

    /**
     * 将状态名称字符串映射为标准 SBA StatusInfo（空值默认 UP）
     *
     * @param rawStatus 状态文本（UP/DOWN/OFFLINE/UNKNOWN）
     * @return 对应的 StatusInfo 实例
     */
    public static StatusInfo toStatusInfo(String rawStatus) {
        if (!StringUtils.hasText(rawStatus)) {
            return StatusInfo.ofUp();
        }
        return switch (rawStatus.trim().toUpperCase(Locale.ROOT)) {
            case "DOWN" -> StatusInfo.ofDown();
            case "OFFLINE" -> StatusInfo.ofOffline();
            case "UNKNOWN" -> StatusInfo.ofUnknown();
            default -> StatusInfo.ofUp();
        };
    }
}
