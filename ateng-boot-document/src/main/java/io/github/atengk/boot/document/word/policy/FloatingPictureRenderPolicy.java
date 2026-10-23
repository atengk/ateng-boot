package io.github.atengk.boot.document.word.policy;

import com.deepoove.poi.data.PictureRenderData;
import com.deepoove.poi.policy.PictureRenderPolicy;
import com.deepoove.poi.render.RenderContext;
import io.github.atengk.boot.document.word.model.FloatingPicture;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTNonVisualDrawingProps;
import org.openxmlformats.schemas.drawingml.x2006.main.CTPositiveSize2D;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTAnchor;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTPosH;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTPosV;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.STRelFromH;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.STRelFromV;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 浮动图层电子公章与手写签名专属渲染策略
 * <p>
 * 将标准 poi-tl 行内图片（CTInline）转换为浮动锚点（CTAnchor），
 * 支持在普通段落与表格单元格中实现“浮于文字上方”（覆盖落款或防篡改）与自定义坐标微调偏移。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class FloatingPictureRenderPolicy extends PictureRenderPolicy {

    private static final Logger log = LoggerFactory.getLogger(FloatingPictureRenderPolicy.class);

    private final ThreadLocal<FloatingPicture> currentFloating = new ThreadLocal<>();

    private final int defaultDx;
    private final int defaultDy;
    private final boolean defaultAboveText;

    public FloatingPictureRenderPolicy() {
        this(0, 0, true);
    }

    public FloatingPictureRenderPolicy(int defaultDx, int defaultDy) {
        this(defaultDx, defaultDy, true);
    }

    public FloatingPictureRenderPolicy(int defaultDx, int defaultDy, boolean defaultAboveText) {
        this.defaultDx = defaultDx;
        this.defaultDy = defaultDy;
        this.defaultAboveText = defaultAboveText;
    }

    @Override
    public PictureRenderData cast(Object data) throws Exception {
        currentFloating.remove();
        if (data instanceof FloatingPicture fp) {
            currentFloating.set(fp);
            try {
                return fp.getPictureData();
            } catch (Exception e) {
                currentFloating.remove();
                throw e;
            }
        }
        return super.cast(data);
    }

    /**
     * 显式清理当前线程绑定的浮动图片上下文
     */
    public void clear() {
        currentFloating.remove();
    }

    @Override
    public void doRender(RenderContext<PictureRenderData> context) throws Exception {
        try {
            // 1. 先通过父类逻辑将图片写入当前 Run（此时为默认 CTInline 行内模式）
            super.doRender(context);

            // 2. 转换为 CTAnchor 浮动锚点
            XWPFRun run = context.getRun();
            if (run == null || run.getCTR() == null) {
                return;
            }

            int dx = defaultDx;
            int dy = defaultDy;
            boolean aboveText = defaultAboveText;

            FloatingPicture fp = currentFloating.get();
            if (fp != null) {
                dx = fp.getDx();
                dy = fp.getDy();
                aboveText = fp.isAboveText();
            }

            convertToAnchor(run, dx, dy, aboveText);
        } finally {
            currentFloating.remove();
        }
    }

    private void convertToAnchor(XWPFRun run, int dx, int dy, boolean aboveText) {
        if (run.getCTR().sizeOfDrawingArray() == 0) {
            return;
        }
        CTDrawing drawing = run.getCTR().getDrawingArray(0);
        if (drawing == null || drawing.sizeOfInlineArray() == 0) {
            return;
        }

        CTInline inline = drawing.getInlineArray(0);
        CTGraphicalObject graphic = inline.getGraphic();
        CTPositiveSize2D extent = inline.getExtent();
        CTNonVisualDrawingProps docPr = inline.getDocPr();

        CTAnchor anchor = CTAnchor.Factory.newInstance();
        anchor.setGraphic(graphic);
        if (extent != null) {
            anchor.setExtent(extent);
        }
        if (docPr != null) {
            anchor.setDocPr(docPr);
        }
        anchor.setBehindDoc(!aboveText);
        anchor.setLayoutInCell(true);
        anchor.setAllowOverlap(true);
        anchor.setLocked(false);
        anchor.setSimplePos2(false);
        anchor.setRelativeHeight(251658240L);

        CTPosH posH = anchor.addNewPositionH();
        posH.setRelativeFrom(STRelFromH.COLUMN);
        posH.setPosOffset(Units.toEMU(dx));

        CTPosV posV = anchor.addNewPositionV();
        posV.setRelativeFrom(STRelFromV.PARAGRAPH);
        posV.setPosOffset(Units.toEMU(dy));

        anchor.addNewWrapNone();

        drawing.setAnchorArray(new CTAnchor[]{anchor});
        drawing.removeInline(0);
        log.debug("成功将 Run 中的图片转换为浮动 CTAnchor 节点 [dx={}, dy={}, aboveText={}]", dx, dy, aboveText);
    }
}
