package io.github.atengk.boot.ai.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 深度推理大模型思考链分流状态机单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class ReasonerTokenExtractorTest {

    @Test
    @DisplayName("普通无思考链文本直接路由至 TEXT 通道")
    void testPureTextStream() {
        ReasonerTokenExtractor extractor = new ReasonerTokenExtractor();

        List<StreamToken> tokens1 = extractor.feed("你好，");
        List<StreamToken> tokens2 = extractor.feed("世界！");
        List<StreamToken> finishTokens = extractor.finish();

        assertThat(extractor.hasThinking()).isFalse();
        assertThat(tokens1).containsExactly(StreamToken.text("你好，"));
        assertThat(tokens2).containsExactly(StreamToken.text("世界！"));
        assertThat(finishTokens).isEmpty();
    }

    @Test
    @DisplayName("单分片完整包含思考链与回答正文")
    void testSingleChunkCompleteThinking() {
        ReasonerTokenExtractor extractor = new ReasonerTokenExtractor();

        List<StreamToken> tokens = extractor.feed("<think>让我先分析一下</think>这是最终答案。");
        List<StreamToken> finishTokens = extractor.finish();

        assertThat(extractor.hasThinking()).isTrue();
        assertThat(tokens).containsExactly(
                StreamToken.reasoning("让我先分析一下"),
                StreamToken.text("这是最终答案。")
        );
        assertThat(finishTokens).isEmpty();
    }

    @Test
    @DisplayName("开始标签跨 Chunk 分割：<th + ink> 正确拼接识别")
    void testStartTagSplittedAcrossChunks() {
        ReasonerTokenExtractor extractor = new ReasonerTokenExtractor();

        List<StreamToken> t1 = extractor.feed("<th");
        assertThat(t1).isEmpty(); // 缓冲区等待后续分片

        List<StreamToken> t2 = extractor.feed("ink>深度推理开始");
        assertThat(extractor.hasThinking()).isTrue();
        assertThat(t2).containsExactly(StreamToken.reasoning("深度推理开始"));

        List<StreamToken> t3 = extractor.feed("</think>回答。");
        assertThat(t3).containsExactly(StreamToken.text("回答。"));

        List<StreamToken> finishTokens = extractor.finish();
        assertThat(finishTokens).isEmpty();
    }

    @Test
    @DisplayName("闭合标签跨 Chunk 分割：</th + ink> 正确缓冲并不泄露闭合标签")
    void testEndTagSplittedAcrossChunks() {
        ReasonerTokenExtractor extractor = new ReasonerTokenExtractor();

        extractor.feed("<think>正在计算结果...");
        List<StreamToken> t1 = extractor.feed("核心推演</th");
        // "</th" 是 </think> 的前缀，应该被保留在缓冲区，只发射安全内容
        assertThat(t1).containsExactly(StreamToken.reasoning("核心推演"));

        List<StreamToken> t2 = extractor.feed("ink>计算完毕，答案为42");
        assertThat(t2).containsExactly(StreamToken.text("计算完毕，答案为42"));

        List<StreamToken> finishTokens = extractor.finish();
        assertThat(finishTokens).isEmpty();
    }

    @Test
    @DisplayName("思考链中包含伪标签前缀字符如 '<' 并在后续分片证伪")
    void testFalseAlarmTagInThinking() {
        ReasonerTokenExtractor extractor = new ReasonerTokenExtractor();

        extractor.feed("<think>");
        // 缓冲区输入类似数学表达式 "a <"
        List<StreamToken> t1 = extractor.feed("如果 a <");
        // "<" 可能是 "</think>" 的前缀，被临时留在 buffer，"如果 a " 被发射
        assertThat(t1).containsExactly(StreamToken.reasoning("如果 a "));

        // 后续紧跟 " b，那么成立"
        List<StreamToken> t2 = extractor.feed(" b，那么成立");
        // 证伪后，buffer 中的 "<" 与新分片拼接后发射
        assertThat(t2).containsExactly(StreamToken.reasoning("< b，那么成立"));

        // 正常结束思考链
        List<StreamToken> t3 = extractor.feed("</think>因此答案为真");
        assertThat(t3).containsExactly(StreamToken.text("因此答案为真"));
    }

    @Test
    @DisplayName("未闭合且尾部含有疑似标签前缀的思考链在 finish 时排空为 reasoning")
    void testUnclosedThinkingFlushedAtFinish() {
        ReasonerTokenExtractor extractor = new ReasonerTokenExtractor();

        List<StreamToken> t1 = extractor.feed("<think>正在思考</th");
        assertThat(t1).containsExactly(StreamToken.reasoning("正在思考"));

        List<StreamToken> finishTokens = extractor.finish();
        assertThat(extractor.hasThinking()).isTrue();
        assertThat(finishTokens).containsExactly(StreamToken.reasoning("</th"));
    }

    @Test
    @DisplayName("未匹配完全的开始标签前缀在 finish 时作为正文排空")
    void testIncompleteStartTagFlushedAtFinish() {
        ReasonerTokenExtractor extractor = new ReasonerTokenExtractor();

        List<StreamToken> t1 = extractor.feed("<th");
        assertThat(t1).isEmpty();

        List<StreamToken> finishTokens = extractor.finish();
        assertThat(extractor.hasThinking()).isFalse();
        assertThat(finishTokens).containsExactly(StreamToken.text("<th"));
    }

    @Test
    @DisplayName("以非 think 标签开头的 XML 文本如 <div> 直接路由为正文")
    void testNonThinkXmlTagRoutesToText() {
        ReasonerTokenExtractor extractor = new ReasonerTokenExtractor();

        List<StreamToken> tokens = extractor.feed("<div>网页内容</div>");
        List<StreamToken> finishTokens = extractor.finish();

        assertThat(extractor.hasThinking()).isFalse();
        assertThat(tokens).containsExactly(StreamToken.text("<div>网页内容</div>"));
        assertThat(finishTokens).isEmpty();
    }

    @Test
    @DisplayName("空分片安全防卫")
    void testEmptyChunk() {
        ReasonerTokenExtractor extractor = new ReasonerTokenExtractor();

        List<StreamToken> tokens1 = extractor.feed(null);
        List<StreamToken> tokens2 = extractor.feed("");

        assertThat(tokens1).isEmpty();
        assertThat(tokens2).isEmpty();
        assertThat(extractor.finish()).isEmpty();
    }
}
