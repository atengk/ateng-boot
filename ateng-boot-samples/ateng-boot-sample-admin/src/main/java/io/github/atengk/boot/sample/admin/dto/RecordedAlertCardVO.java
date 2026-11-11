package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 离线录制告警卡片快照响应 VO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
public class RecordedAlertCardVO {

    /**
     * 告警卡片标题
     */
    private String title;

    /**
     * 脱敏后的告警富文本正文
     */
    private String content;

    /**
     * 卡片录制时间戳
     */
    private Instant recordedAt;
}
