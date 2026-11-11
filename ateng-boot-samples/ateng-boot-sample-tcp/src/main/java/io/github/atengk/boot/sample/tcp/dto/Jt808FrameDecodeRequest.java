package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * JT/T 808 十六进制原始报文解码与验签请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Jt808FrameDecodeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 含首尾 0x7E 标识位的 JT/T 808 十六进制帧串
     */
    @NotBlank(message = "十六进制报文 hexFrame 不能为空")
    private String hexFrame;
}
