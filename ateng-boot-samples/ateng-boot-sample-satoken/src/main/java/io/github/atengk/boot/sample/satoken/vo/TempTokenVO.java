package io.github.atengk.boot.sample.satoken.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 临时业务令牌签发与核验响应视图对象
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TempTokenVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 生成的临时业务令牌凭据
     */
    private String token;

    /**
     * 剩余有效时间（秒）
     */
    private Long ttlSeconds;

    /**
     * 还原后的强类型业务载荷
     */
    private TempTokenTicketVO payload;
}
