package io.github.atengk.boot.ai.rag.splitter;

import dev.langchain4j.model.Tokenizer;

import java.util.List;

/**
 * Token 预算防溢出切片器 (TokenBudgetSplitter)
 * <p>
 * {@link TokenCharacterSplitter} 的语义别名，提供完全一致的 Token 预算截断与滑动窗口切片能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class TokenBudgetSplitter extends TokenCharacterSplitter {

    public TokenBudgetSplitter() {
        super();
    }

    public TokenBudgetSplitter(int maxTokens, int tokenOverlap) {
        super(maxTokens, tokenOverlap);
    }

    public TokenBudgetSplitter(int maxTokens, int tokenOverlap, Tokenizer tokenizer) {
        super(maxTokens, tokenOverlap, tokenizer);
    }

    public TokenBudgetSplitter(int maxTokens, int tokenOverlap, Tokenizer tokenizer, double safetyMargin) {
        super(maxTokens, tokenOverlap, tokenizer, safetyMargin);
    }

    public TokenBudgetSplitter(int maxTokens, int tokenOverlap, Tokenizer tokenizer, double safetyMargin,
                               List<String> separators, boolean keepSeparators) {
        super(maxTokens, tokenOverlap, tokenizer, safetyMargin, separators, keepSeparators);
    }
}
