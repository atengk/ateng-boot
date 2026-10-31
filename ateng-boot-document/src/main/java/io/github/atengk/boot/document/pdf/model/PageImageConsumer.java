package io.github.atengk.boot.document.pdf.model;

import java.awt.image.BufferedImage;

/**
 * PDF 逐页渲染图像流式消费回调函数式接口
 *
 * @author Ateng
 * @since 2026-10-31
 */
@FunctionalInterface
public interface PageImageConsumer {

    /**
     * 消费当前页渲染出的内存位图图像
     *
     * @param pageNumber 当前页码（1-based 物理页码）
     * @param image      渲染出的 BufferedImage（消费完毕后由外层流式引擎自动 flush 销毁）
     * @throws Exception 业务消费处理异常
     */
    void accept(int pageNumber, BufferedImage image) throws Exception;
}
