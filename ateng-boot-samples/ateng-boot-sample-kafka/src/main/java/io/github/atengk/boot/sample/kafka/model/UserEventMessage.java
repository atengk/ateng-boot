package io.github.atengk.boot.sample.kafka.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户行为事件实体
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserEventMessage implements Serializable {

    private String userId;
    private String eventType;
    private Long timestamp;
}
