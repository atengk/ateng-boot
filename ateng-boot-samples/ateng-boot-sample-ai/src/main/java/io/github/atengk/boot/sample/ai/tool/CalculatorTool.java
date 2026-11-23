package io.github.atengk.boot.sample.ai.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 示例计算器受管工具组件
 * <p>
 * 用于演示动态函数调用 (Dynamic Tools) 与受管沙箱执行
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Component
public class CalculatorTool {

    private static final Logger log = LoggerFactory.getLogger(CalculatorTool.class);

    /**
     * 计算两个整数乘积
     *
     * @param a 乘数 a
     * @param b 乘数 b
     * @return 乘积结果
     */
    @Tool("计算两个整数的乘积并返回结果数值")
    public long multiply(@P("第一个整数乘数 a") long a, @P("第二个整数乘数 b") long b) {
        log.info("[TOOL-INVOKE] 执行精确乘法计算: {} * {}", a, b);
        return a * b;
    }

    /**
     * 计算两个数值的和
     *
     * @param a 加数 a
     * @param b 加数 b
     * @return 求和结果
     */
    @Tool("计算两个数值的加法求和")
    public double add(@P("第一个加数 a") double a, @P("第二个加数 b") double b) {
        log.info("[TOOL-INVOKE] 执行精确加法计算: {} + {}", a, b);
        return a + b;
    }
}
