package io.github.atengk.boot.ai.streaming;

/**
 * 流式输出分片模型
 *
 * @param channel 归属通道 (REASONING / TEXT)
 * @param text    增量分片文本
 * @author Ateng
 * @since 2026-11-03
 */
public record StreamToken(TokenChannel channel, String text) {

    public static StreamToken reasoning(String text) {
        return new StreamToken(TokenChannel.REASONING, text);
    }

    public static StreamToken text(String text) {
        return new StreamToken(TokenChannel.TEXT, text);
    }
}
