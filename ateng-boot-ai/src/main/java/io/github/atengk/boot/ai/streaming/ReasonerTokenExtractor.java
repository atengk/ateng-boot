package io.github.atengk.boot.ai.streaming;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 深度推理大模型思考链分流状态机
 * 精准识别并解耦 DeepSeek-R1 等模型的 {@code <think> ... </think>} 思考过程与正文内容，
 * 防御分片跨越 Chunk 边界导致的标签残片泄露
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class ReasonerTokenExtractor {

    private static final String START_TAG = "<think>";
    private static final String END_TAG = "</think>";

    private enum State {
        INITIAL,
        IN_THINKING,
        IN_TEXT
    }

    private State state = State.INITIAL;
    private final StringBuilder buffer = new StringBuilder();
    private boolean encounteredThinking = false;

    /**
     * 接收流式增量 Chunk 分片并执行状态机解耦
     *
     * @param chunk 增量字符分片
     * @return 解析出的分流 Token 列表
     */
    public List<StreamToken> feed(String chunk) {
        if (!StringUtils.hasLength(chunk)) {
            return Collections.emptyList();
        }

        buffer.append(chunk);
        List<StreamToken> tokens = new ArrayList<>();

        while (buffer.length() > 0) {
            if (state == State.INITIAL) {
                if (buffer.toString().startsWith(START_TAG)) {
                    buffer.delete(0, START_TAG.length());
                    state = State.IN_THINKING;
                    encounteredThinking = true;
                    continue;
                }
                if (buffer.length() < START_TAG.length() && START_TAG.startsWith(buffer.toString())) {
                    // 可能是 <think> 的前缀片段，等待后续分片确认
                    break;
                }
                // 非思考链开头，直接切入正文状态并排空当前缓冲区
                state = State.IN_TEXT;
                tokens.add(StreamToken.text(buffer.toString()));
                buffer.setLength(0);
                break;
            }

            if (state == State.IN_THINKING) {
                int endIdx = buffer.indexOf(END_TAG);
                if (endIdx >= 0) {
                    if (endIdx > 0) {
                        tokens.add(StreamToken.reasoning(buffer.substring(0, endIdx)));
                    }
                    buffer.delete(0, endIdx + END_TAG.length());
                    state = State.IN_TEXT;
                    continue;
                }

                // 检查缓冲区尾部是否存在 </think> 的不完整前缀
                int matchLength = findLongestSuffixMatchingPrefix(buffer.toString(), END_TAG);
                if (matchLength > 0) {
                    int safeLen = buffer.length() - matchLength;
                    if (safeLen > 0) {
                        tokens.add(StreamToken.reasoning(buffer.substring(0, safeLen)));
                        buffer.delete(0, safeLen);
                    }
                    break;
                }

                // 无潜在闭合标签前缀，安全发射当前缓冲区全部内容
                tokens.add(StreamToken.reasoning(buffer.toString()));
                buffer.setLength(0);
                break;
            }

            if (state == State.IN_TEXT) {
                tokens.add(StreamToken.text(buffer.toString()));
                buffer.setLength(0);
                break;
            }
        }

        return tokens;
    }

    /**
     * 流式传输结束时安全排空缓冲区残留字符
     *
     * @return 最终残留的 Token 列表
     */
    public List<StreamToken> finish() {
        if (buffer.length() == 0) {
            return Collections.emptyList();
        }

        String remaining = buffer.toString();
        buffer.setLength(0);

        if (state == State.IN_THINKING) {
            return Collections.singletonList(StreamToken.reasoning(remaining));
        } else {
            return Collections.singletonList(StreamToken.text(remaining));
        }
    }

    /**
     * 是否检测到思考链内容
     *
     * @return 若包含思考链返回 true，否则返回 false
     */
    public boolean hasThinking() {
        return encounteredThinking;
    }

    /**
     * 查找 text 的最长后缀，该后缀同时是 target 的真前缀
     */
    private static int findLongestSuffixMatchingPrefix(String text, String target) {
        int maxLen = Math.min(text.length(), target.length() - 1);
        for (int len = maxLen; len >= 1; len--) {
            String suffix = text.substring(text.length() - len);
            if (target.startsWith(suffix)) {
                return len;
            }
        }
        return 0;
    }
}
