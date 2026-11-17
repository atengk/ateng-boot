package io.github.atengk.boot.sample.flyway.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 隔离式 SM4 加密 DDL 凭据瞬态执行演练响应视图对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IsolatedCredentialDemoResponse {

    private String targetKey;
    private String maskedCiphertext;
    private boolean validationPassed;
    private int validateCount;
    private int activeTransientPoolCount;
    private long closedTransientPoolCount;
}
