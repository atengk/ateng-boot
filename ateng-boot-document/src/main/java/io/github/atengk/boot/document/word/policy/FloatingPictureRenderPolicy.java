package io.github.atengk.boot.document.word.policy;

import com.deepoove.poi.data.PictureRenderData;
import com.deepoove.poi.policy.PictureRenderPolicy;
import com.deepoove.poi.render.RenderContext;
import io.github.atengk.boot.document.word.model.FloatingPicture;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTNonVisualDrawingProps;
import org.openxmlformats.schemas.drawingml.x2006.main.CTPoint2D;
import org.openxmlformats.schemas.drawingml.x2006.main.CTPositiveSize2D;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTAnchor;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTEffectExtent;
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
    private final STRelFromH.Enum defaultRelativeFromH;
    private final STRelFromV.Enum defaultRelativeFromV;

    public FloatingPictureRenderPolicy() {
        this(0, 0, true, STRelFromH.CHARACTER, STRelFromV.PARAGRAPH);
    }

    public FloatingPictureRenderPolicy(int defaultDx, int defaultDy) {
        this(defaultDx, defaultDy, true, STRelFromH.CHARACTER, STRelFromV.PARAGRAPH);
    }

    public FloatingPictureRenderPolicy(int defaultDx, int defaultDy, boolean defaultAboveText) {
        this(defaultDx, defaultDy, defaultAboveText, STRelFromH.CHARACTER, STRelFromV.PARAGRAPH);
    }

    public FloatingPictureRenderPolicy(int defaultDx, int defaultDy, boolean defaultAboveText,
                                       STRelFromH.Enum defaultRelativeFromH, STRelFromV.Enum defaultRelativeFromV) {
        this.defaultDx = defaultDx;
        this.defaultDy = defaultDy;
        this.defaultAboveText = defaultAboveText;
        this.defaultRelativeFromH = defaultRelativeFromH != null ? defaultRelativeFromH : STRelFromH.CHARACTER;
        this.defaultRelativeFromV = defaultRelativeFromV != null ? defaultRelativeFromV : STRelFromV.PARAGRAPH;
    }

    @Override
    public PictureRenderData cast(Object data) throws Exception {
        currentFloating.remove();
        if (data == null) {
            return null;
        }
        if (data instanceof FloatingPicture fp) {
            currentFloating.set(fp);
            PictureRenderData picData = fp.getPictureData();
            if (!isPictureDataValid(picData)) {
                log.warn("FloatingPicture 图片数据为空或不可读，将优雅清空占位符");
                currentFloating.remove();
                return null;
            }
            return picData;
        }
        try {
            PictureRenderData picData = super.cast(data);
            if (!isPictureDataValid(picData)) {
                return null;
            }
            return picData;
        } catch (Exception e) {
            log.warn("转换图片渲染数据异常，将优雅清空占位符: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isPictureDataValid(PictureRenderData picData) {
        if (picData == null) {
            return false;
        }
        try {
            byte[] bytes = picData.readPictureData();
            return bytes != null && bytes.length > 0;
        } catch (Exception e) {
            log.warn("读取图片数据异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 显式清理当前线程绑定的浮动图片上下文
     */
    public void clear() {
        currentFloating.remove();
    }

    @Override
    public void doRender(RenderContext<PictureRenderData> context) throws Exception {
        if (context == null || context.getData() == null) {
            currentFloating.remove();
            return;
        }
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
            STRelFromH.Enum relativeFromH = defaultRelativeFromH;
            STRelFromV.Enum relativeFromV = defaultRelativeFromV;

            FloatingPicture fp = currentFloating.get();
            if (fp != null) {
                dx = fp.getDx();
                dy = fp.getDy();
                aboveText = fp.isAboveText();
                if (fp.getRelativeFromH() != null) {
                    relativeFromH = fp.getRelativeFromH();
                }
                if (fp.getRelativeFromV() != null) {
                    relativeFromV = fp.getRelativeFromV();
                }
            }

            convertToAnchor(run, dx, dy, aboveText, relativeFromH, relativeFromV);
        } finally {
            currentFloating.remove();
        }
    }

    private void convertToAnchor(XWPFRun run, int dx, int dy, boolean aboveText,
                                 STRelFromH.Enum relativeFromH, STRelFromV.Enum relativeFromV) {
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

        // 1. 设置 CTAnchor 规范属性（根据 ECMA-376 规范，distT/B/L/R 须明确赋值）
        anchor.setDistT(0L);
        anchor.setDistB(0L);
        anchor.setDistL(0L);
        anchor.setDistR(0L);
        anchor.setSimplePos2(false);
        anchor.setRelativeHeight(251658240L);
        anchor.setBehindDoc(!aboveText);
        anchor.setLocked(false);
        anchor.setLayoutInCell(true);
        anchor.setAllowOverlap(true);

        // 2. 严格依照 OpenXML dml-wordprocessingDrawing.xsd 序列（Sequence）组装子节点
        // ① simplePos（必填首节点，即使 simplePos="false" 也必须出现）
        CTPoint2D simplePos = anchor.addNewSimplePos();
        simplePos.setX(0L);
        simplePos.setY(0L);

        // ② positionH 水平相对定位
        CTPosH posH = anchor.addNewPositionH();
        posH.setRelativeFrom(relativeFromH);
        posH.setPosOffset(Units.toEMU(dx));

        // ③ positionV 垂直相对定位
        CTPosV posV = anchor.addNewPositionV();
        posV.setRelativeFrom(relativeFromV);
        posV.setPosOffset(Units.toEMU(dy));

        // ④ extent 尺寸定义
        if (extent != null) {
            CTPositiveSize2D anchorExtent = anchor.addNewExtent();
            anchorExtent.setCx(extent.getCx());
            anchorExtent.setCy(extent.getCy());
        }

        // ⑤ effectExtent 边缘扩展
        CTEffectExtent effectExtent = anchor.addNewEffectExtent();
        effectExtent.setL(0L);
        effectExtent.setT(0L);
        effectExtent.setR(0L);
        effectExtent.setB(0L);

        // ⑥ wrapNone 无环绕浮动图层
        anchor.addNewWrapNone();

        // ⑦ docPr 非视觉绘图属性
        if (docPr != null) {
            CTNonVisualDrawingProps anchorDocPr = anchor.addNewDocPr();
            anchorDocPr.setId(docPr.getId());
            anchorDocPr.setName(docPr.getName());
            if (docPr.isSetDescr()) {
                anchorDocPr.setDescr(docPr.getDescr());
            }
        }

        // ⑧ cNvGraphicFramePr 框架锁定属性
        if (inline.isSetCNvGraphicFramePr()) {
            anchor.setCNvGraphicFramePr(inline.getCNvGraphicFramePr());
        } else {
            anchor.addNewCNvGraphicFramePr();
        }

        // ⑨ graphic 图形载荷数据（置于序列末尾）
        anchor.setGraphic(graphic);

        drawing.setAnchorArray(new CTAnchor[]{anchor});
        drawing.removeInline(0);
        log.debug("成功将 Run 中的图片转换为浮动 CTAnchor 节点 [dx={}, dy={}, aboveText={}]", dx, dy, aboveText);
    }
}
