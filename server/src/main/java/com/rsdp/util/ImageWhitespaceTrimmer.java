package com.rsdp.util;

import com.rsdp.dto.ProductBoundingBox;
import lombok.extern.slf4j.Slf4j;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * 产品图白边精修器。
 *
 * <p>AI 返回的 bbox 是粗估坐标，直接裁剪会带页边距、白边或切到文字。
 * 本类对裁剪结果做二次精修：背景色估计 + 四边空白扫描收紧 + 统一留白，
 * 让主图紧贴产品主体，显著提升准确度和观感。</p>
 *
 * <p>收紧策略分两种（{@link TrimOptions}）：</p>
 * <ul>
 *   <li>{@link TrimOptions#legacy()}：不限收紧幅度、无保护的原始行为，仅保留作对照，
 *   各业务链路均已切到保守模式；</li>
 *   <li>{@link TrimOptions#conservative()}：业务链路统一使用的保守模式——背景一致性门槛
 *   （场景背景不收紧）、每边收紧幅度上限、连续非背景段（细腿）保护，
 *   宁可多留边也绝不切到产品。</li>
 * </ul>
 */
@Slf4j
public final class ImageWhitespaceTrimmer {

    /**
     * 判定像素与背景色"接近"的每通道容差。
     */
    private static final int COLOR_TOLERANCE = 12;

    /**
     * 一行/列中背景色像素占比超过该值时视为空白行/列（legacy 默认）。
     */
    private static final double BLANK_RATIO = 0.995;

    /**
     * 背景色估计时取角落采样块的边长（像素）。
     */
    private static final int CORNER_SAMPLE = 5;

    /**
     * 保守模式下，行/列内连续非背景像素段达到该长度即视为内容（细腿保护）。
     */
    private static final int CONTENT_RUN_LENGTH = 3;

    /**
     * 内容重裁：行内最长连续内容段占行宽超过该比例视为产品主体致密行
     * （文字行笔画零散、细腿行连续段短，均不达标）。
     */
    private static final double DENSE_ROW_RATIO = 0.45;

    /**
     * 内容重裁：底部文字带的最大高度占比（超过则不冒险裁剪）。
     */
    private static final double TEXT_BAND_MAX_RATIO = 0.40;

    /**
     * 内容重裁：边缘相邻图切片带的最大宽度占比。
     */
    private static final double SLICE_BAND_MAX_RATIO = 0.30;

    /**
     * 边缘切片切除：行向亮度梯度达到该值视为纹理行（照片纹理丰富；白底板/纯色产品几乎无梯度）。
     */
    private static final int COLUMN_TEXTURE_GRADIENT = 15;

    /**
     * 边缘切片切除：切片带列的纹理行平均占比下限（照片几乎逐行有纹理）。
     */
    private static final double SLICE_COLUMN_MIN_TEXTURE = 0.5;

    /**
     * 边缘切片切除：空白隔断列的纹理行占比上限（近乎纯色）。
     */
    private static final double GAP_COLUMN_MAX_TEXTURE = 0.02;

    /**
     * 收紧策略选项。
     *
     * @param blankRatio                  空白行/列判定阈值（背景色像素占比）
     * @param maxTrimRatio                每边最多收紧该边长度的比例（1.0 表示不限制）
     * @param protectContentRun           是否启用连续非背景段保护（细腿/细部件防切）
     * @param cornerConsistencyTolerance  四角背景色一致性容差（每通道），>0 时四角颜色
     *                                    差异超过该值判定为场景背景并跳过收紧；<=0 不启用
     * @param recropContent               是否启用内容重裁：纯色背景下分析行/列密度，
     *                                    裁掉卡片底部的说明文字带和边缘的相邻图切片
     *                                    （PDF 画册"图+文字同卡片"版式专用）
     */
    public record TrimOptions(double blankRatio, double maxTrimRatio,
                              boolean protectContentRun, int cornerConsistencyTolerance,
                              boolean recropContent) {

        /** 不限幅度、无保护的原始收紧行为（仅保留作对照，业务链路勿用）。 */
        public static TrimOptions legacy() {
            return new TrimOptions(BLANK_RATIO, 1.0, false, 0, false);
        }

        /** 业务链路统一使用的保守收紧行为：宁可多留边也绝不切到产品。 */
        public static TrimOptions conservative() {
            return new TrimOptions(0.999, 0.25, true, 30, false);
        }

        /** PDF 文档导入专用：保守收紧 + 内容重裁（去底部文字带/边缘相邻图切片）。 */
        public static TrimOptions document() {
            return new TrimOptions(0.999, 0.25, true, 30, true);
        }
    }

    private ImageWhitespaceTrimmer() {
    }

    /**
     * 裁剪 + 白边精修 + 留白，输出 JPEG 字节（legacy 收紧行为，仅保留作对照，业务链路请用
     * 带 {@link TrimOptions} 参数的重载并传入 {@link TrimOptions#conservative()}）。
     *
     * @param page         渲染页面图
     * @param bbox         AI 返回的相对坐标框（应已经过 {@link ProductBoxRefiner} 清洗）
     * @param expandRatio  裁剪前外扩比例（相对页面宽高），如 0.03
     * @param padRatio     收紧后留白比例（相对内容宽高），如 0.02
     * @param quality      JPEG 质量，0.0 ~ 1.0
     * @return 精修后的 JPEG 字节
     * @throws IOException 裁剪或编码失败
     */
    public static byte[] cropRefineToJpeg(BufferedImage page, ProductBoundingBox bbox,
                                          double expandRatio, double padRatio, float quality) throws IOException {
        return cropRefineToJpeg(page, bbox, expandRatio, padRatio, quality, TrimOptions.legacy());
    }

    /**
     * 裁剪 + 白边精修 + 留白，输出 JPEG 字节（可指定收紧策略）。
     *
     * <p>流程：bbox 按比例外扩（保证不缺边）→ 裁剪 → 角落估计背景色 →
     * 四边扫描空白行/列收紧到内容包围盒 → 按留白比例补齐背景色边距 → JPEG 编码。</p>
     *
     * @param page         原始图片
     * @param bbox         AI 返回的相对坐标框（应已经过 {@link ProductBoxRefiner} 清洗）
     * @param expandRatio  裁剪前外扩比例（相对图片宽高）
     * @param padRatio     收紧后留白比例（相对内容宽高）
     * @param quality      JPEG 质量，0.0 ~ 1.0
     * @param options      收紧策略
     * @return 精修后的 JPEG 字节
     * @throws IOException 裁剪或编码失败
     */
    public static byte[] cropRefineToJpeg(BufferedImage page, ProductBoundingBox bbox,
                                          double expandRatio, double padRatio, float quality,
                                          TrimOptions options) throws IOException {
        return cropRefineToJpeg(page, bbox, expandRatio, padRatio, quality, options, null);
    }

    /**
     * 裁剪 + 白边精修 + 留白，输出 JPEG 字节（可指定收紧策略与核心框）。
     *
     * <p>流程：bbox 按比例外扩（保证不缺边）→ 裁剪 → 角落估计背景色 →
     * 四边扫描空白行/列收紧到内容包围盒 → 按留白比例补齐背景色边距 → JPEG 编码。</p>
     *
     * <p>核心框（{@code coreBox}，通常是未经外扩的 AI 原始框）是"可信内容区"：
     * 白边收紧最多收到核心框边缘为止，绝不切入框内（浅色产品身体不再被当白边吃掉）；
     * 底部文字带重裁也只处理核心框底边以下的区域（浅色产品下部不再被误判为文字带，
     * 外扩边距里混入的说明文字仍会被清掉）。为 null 时不启用锚定（原行为）。</p>
     *
     * @param page         原始图片
     * @param bbox         AI 返回的相对坐标框（应已经过 {@link ProductBoxRefiner} 清洗）
     * @param expandRatio  裁剪前外扩比例（相对图片宽高）
     * @param padRatio     收紧后留白比例（相对内容宽高）
     * @param quality      JPEG 质量，0.0 ~ 1.0
     * @param options      收紧策略
     * @param coreBox      核心框（相对坐标），可为 null
     * @return 精修后的 JPEG 字节
     * @throws IOException 裁剪或编码失败
     */
    public static byte[] cropRefineToJpeg(BufferedImage page, ProductBoundingBox bbox,
                                          double expandRatio, double padRatio, float quality,
                                          TrimOptions options, ProductBoundingBox coreBox) throws IOException {
        if (page == null) {
            throw new IllegalArgumentException("页面图不能为空");
        }
        if (bbox == null || !bbox.isValid()) {
            throw new IllegalArgumentException("裁剪框不合法");
        }
        if (options == null) {
            options = TrimOptions.legacy();
        }

        // 1. 外扩 bbox 并换算像素坐标（边界钳制）
        double expandedX = Math.max(0.0, bbox.getX() - expandRatio);
        double expandedY = Math.max(0.0, bbox.getY() - expandRatio);
        double expandedW = Math.min(1.0 - expandedX, bbox.getWidth() + 2 * expandRatio);
        double expandedH = Math.min(1.0 - expandedY, bbox.getHeight() + 2 * expandRatio);

        int x = (int) Math.round(expandedX * page.getWidth());
        int y = (int) Math.round(expandedY * page.getHeight());
        int width = Math.min((int) Math.round(expandedW * page.getWidth()), page.getWidth() - x);
        int height = Math.min((int) Math.round(expandedH * page.getHeight()), page.getHeight() - y);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("裁剪后尺寸无效");
        }

        // 2. 裁剪为独立的 RGB 图（避免共享 raster，统一色彩模型）
        BufferedImage cropped = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = cropped.createGraphics();
        g.drawImage(page.getSubimage(x, y, width, height), 0, 0, null);
        g.dispose();

        // 2.4 核心框（AI 原始框）到裁剪图四边的距离（像素）；锚定收紧/文字带重裁的禁区
        int coreTopPx = -1;
        int coreBottomPx = -1;
        int coreLeftPx = -1;
        int coreRightPx = -1;
        if (coreBox != null) {
            coreTopPx = (int) Math.round(Math.max(0.0, coreBox.getY() - expandedY) * page.getHeight());
            coreBottomPx = (int) Math.round(Math.max(0.0,
                (expandedY + expandedH) - (coreBox.getY() + coreBox.getHeight())) * page.getHeight());
            coreLeftPx = (int) Math.round(Math.max(0.0, coreBox.getX() - expandedX) * page.getWidth());
            coreRightPx = (int) Math.round(Math.max(0.0,
                (expandedX + expandedW) - (coreBox.getX() + coreBox.getWidth())) * page.getWidth());
        }

        // 2.5 边缘邻图切片切除（document 模式）：基于列纹理/均匀度，不依赖全局背景色。
        // 必须在背景估计之前执行——混框（白底图块+邻图切片）时四角天然不一致，
        // 不先切掉切片，后续背景估计/白边收紧/文字带重裁全会被一致性闸门挡下
        SliceResult sliceResult = removeEdgeSlices(cropped, options);
        BufferedImage presliced = sliceResult.image();
        // 左缘被切时，核心框左边距同步左移（右缘切除不影响左原点坐标）
        if (coreLeftPx >= 0) {
            coreLeftPx = Math.max(0, coreLeftPx - sliceResult.leftCut());
        }

        // 3. 背景色估计（在裁剪图上、收紧前进行，确保拿到的是页边背景而非产品色）
        int[] cornerMeans = estimateCornerMeans(presliced);
        int bgRgb = medianOf(cornerMeans);

        // 4. 白边收紧（锚定核心框：最多收到核心框边缘，不切入框内）
        TrimResult trimResult = trimBlankEdges(presliced, bgRgb, cornerMeans, options,
            coreTopPx, coreBottomPx, coreLeftPx, coreRightPx);
        BufferedImage trimmed = trimResult.image();

        // 4.5 底部文字带重裁（document 模式）：只处理核心框底边以下的区域
        int coreBottomRow = coreBottomPx >= 0
            ? (presliced.getHeight() - coreBottomPx) - trimResult.top() : -1;
        BufferedImage recropped = recropBottomTextBand(trimmed, bgRgb, cornerMeans, options, coreBottomRow);

        // 5. 留白 padding（使用页面背景色）
        BufferedImage padded = pad(recropped, padRatio, bgRgb);

        return ImageCropper.encodeJpeg(padded, quality);
    }

    /**
     * 白边收紧结果：收紧后图像 + 其在原图中的顶行/左列偏移（用于核心框坐标换算）。
     */
    private record TrimResult(BufferedImage image, int top, int left) {
    }

    /**
     * 扫描四边空白行/列并收紧到内容包围盒；图像整体近乎纯色时原样返回。
     *
     * <p>保守模式下：四角背景色不一致（场景背景）时跳过收紧；
     * 每边收紧幅度不超过 {@code maxTrimRatio}；行/列内存在连续非背景段时
     * 不视为空白（细腿保护）。</p>
     *
     * <p>核心框锚定：对应方向的距离参数 ≥0 时，该方向收紧不得超过核心框边缘
     * （与 maxTrimRatio 取较小者），保证不切入 AI 原始框内部。</p>
     */
    private static TrimResult trimBlankEdges(BufferedImage image, int bgRgb, int[] cornerMeans,
                                             TrimOptions options,
                                             int coreTopPx, int coreBottomPx, int coreLeftPx, int coreRightPx) {
        int width = image.getWidth();
        int height = image.getHeight();

        // 背景一致性门槛：四角颜色差异大说明是场景/渐变背景，收紧不可靠，直接跳过
        if (options.cornerConsistencyTolerance() > 0 && !cornersConsistent(cornerMeans, options.cornerConsistencyTolerance())) {
            log.debug("四角背景色不一致，跳过白边收紧（场景背景）");
            return new TrimResult(image, 0, 0);
        }

        int maxTrimX = (int) Math.floor(width * options.maxTrimRatio());
        int maxTrimY = (int) Math.floor(height * options.maxTrimRatio());
        // 核心框锚定：收紧上限与核心框边距取较小者
        int limitTop = coreTopPx >= 0 ? Math.min(maxTrimY, coreTopPx) : maxTrimY;
        int limitBottom = coreBottomPx >= 0 ? Math.min(maxTrimY, coreBottomPx) : maxTrimY;
        int limitLeft = coreLeftPx >= 0 ? Math.min(maxTrimX, coreLeftPx) : maxTrimX;
        int limitRight = coreRightPx >= 0 ? Math.min(maxTrimX, coreRightPx) : maxTrimX;

        int top = 0;
        while (top < height && top < limitTop && isBlankRow(image, top, 0, width, bgRgb, options)) {
            top++;
        }
        int bottom = height - 1;
        int bottomTrimmed = 0;
        while (bottom > top && bottomTrimmed < limitBottom && isBlankRow(image, bottom, 0, width, bgRgb, options)) {
            bottom--;
            bottomTrimmed++;
        }
        int left = 0;
        while (left < width && left < limitLeft && isBlankColumn(image, left, top, bottom, bgRgb, options)) {
            left++;
        }
        int right = width - 1;
        int rightTrimmed = 0;
        while (right > left && rightTrimmed < limitRight && isBlankColumn(image, right, top, bottom, bgRgb, options)) {
            right--;
            rightTrimmed++;
        }

        int contentW = right - left + 1;
        int contentH = bottom - top + 1;
        // 内容框无效或几乎没有可裁空间（纯色图）时保持原图
        if (contentW <= 0 || contentH <= 0 || (left == 0 && top == 0 && right == width - 1 && bottom == height - 1)) {
            return new TrimResult(image, 0, 0);
        }
        return new TrimResult(image.getSubimage(left, top, contentW, contentH), top, left);
    }

    /**
     * 底部文字带重裁：在纯色背景的裁剪图上，裁掉画册"图+文字同卡片"版式中
     * 卡片底部的说明文字带（白边收紧无法处理——文字是"内容"而非空白）。
     *
     * <p>仅在 {@link TrimOptions#recropContent()} 开启且四角背景一致（非场景背景）时执行；
     * 裁剪结果小于原图 50% 时放弃，宁可保留多余内容也不切产品。</p>
     *
     * <p>核心框锚定：{@code coreBottomRow} ≥0 时，只处理该行以下的隔断——
     * 核心框（AI 原始框）内部的"浅色产品下部 + 下方文字/底脚"结构不会被误判为文字带；
     * 核心框底边以下（外扩边距）混入的说明文字仍会被清掉。</p>
     */
    private static BufferedImage recropBottomTextBand(BufferedImage image, int bgRgb, int[] cornerMeans,
                                                      TrimOptions options, int coreBottomRow) {
        if (!options.recropContent()) {
            return image;
        }
        if (options.cornerConsistencyTolerance() > 0
            && !cornersConsistent(cornerMeans, options.cornerConsistencyTolerance())) {
            log.debug("四角背景色不一致，跳过文字带重裁（场景背景）");
            return image;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int[] rowContent = new int[height];
        int[] rowMaxRun = new int[height];
        for (int y = 0; y < height; y++) {
            int run = 0;
            for (int x = 0; x < width; x++) {
                if (!isNearBackground(image.getRGB(x, y), bgRgb)) {
                    rowContent[y]++;
                    run++;
                    rowMaxRun[y] = Math.max(rowMaxRun[y], run);
                } else {
                    run = 0;
                }
            }
        }

        int bottom = cutBottomTextBand(rowContent, rowMaxRun, width, height, coreBottomRow);

        // 防御：重裁后高度不足原图一半时放弃（极端情况宁可多留也不切产品）
        if (bottom < height / 2) {
            log.debug("文字带重裁幅度异常，放弃重裁");
            return image;
        }
        if (bottom == height - 1) {
            return image;
        }
        return image.getSubimage(0, 0, width, bottom + 1);
    }

    /**
     * 计算底部文字带的截断行（含该行，即新的最后一行）；无文字带时返回最后一行索引。
     *
     * <p>从上往下找第一个"合格空白隔断"：隔断下方区域有内容（确为文字带而非空白）、
     * 无致密行带（连续 ≥3 行的致密行才是产品主体；分隔线等孤立长行不算；
     * 致密 = 最长连续内容段达标，文字行笔画零散不达标）、且最长连续内容行段很短
     * （文字行是短簇，细腿/支架是贯穿长段的连续结构，以此区分防止误切产品的腿）。</p>
     *
     * <p>{@code coreBottomRow} ≥0 时，起始行在核心框底边以上的隔断直接跳过。</p>
     */
    private static int cutBottomTextBand(int[] rowContent, int[] rowMaxRun, int width, int height,
                                         int coreBottomRow) {
        int bottom = height - 1;
        while (bottom > 0 && rowContent[bottom] == 0) {
            bottom--;
        }
        int minGap = Math.max(3, (int) Math.round(height * 0.01));
        int denseThreshold = (int) Math.ceil(DENSE_ROW_RATIO * width);
        int maxTextRun = Math.max(30, (int) Math.round(0.08 * height));
        int maxBand = (int) (TEXT_BAND_MAX_RATIO * height);

        int y = 0;
        while (y < bottom) {
            if (rowContent[y] != 0) {
                y++;
                continue;
            }
            int runStart = y;
            while (y <= bottom && rowContent[y] == 0) {
                y++;
            }
            int runEnd = y - 1;
            if (runEnd - runStart + 1 < minGap) {
                continue;
            }
            // 核心框锚定：核心框底边以上的隔断不处理（保护框内浅色产品下部）
            if (coreBottomRow >= 0 && runStart < coreBottomRow) {
                continue;
            }
            // 隔断下方区域分析：文字带 = 有内容 + 无致密行带 + 内容行短簇
            int zoneHeight = bottom - runEnd;
            if (zoneHeight <= 0 || zoneHeight > maxBand) {
                continue;
            }
            boolean hasContent = false;
            boolean hasDenseBand = false;
            int maxRun = 0;
            int cur = 0;
            int denseRun = 0;
            for (int z = runEnd + 1; z <= bottom; z++) {
                if (rowContent[z] > 0) {
                    hasContent = true;
                    cur++;
                    maxRun = Math.max(maxRun, cur);
                    // 产品主体是连续多行的致密带；分隔线等孤立长行不算（防误挡文字带切除）
                    if (rowMaxRun[z] >= denseThreshold) {
                        denseRun++;
                        hasDenseBand = hasDenseBand || denseRun >= 3;
                    } else {
                        denseRun = 0;
                    }
                } else {
                    cur = 0;
                    denseRun = 0;
                }
            }
            if (hasContent && !hasDenseBand && maxRun <= maxTextRun) {
                log.debug("裁掉底部文字带：高 {}px", height - runStart);
                return runStart - 1;
            }
        }
        return bottom;
    }

    /**
     * 边缘切片切除结果：切除后图像 + 左缘切除宽度（用于核心框左边距换算）。
     */
    private record SliceResult(BufferedImage image, int leftCut) {
    }

    /**
     * 边缘邻图切片切除（document 模式，在背景估计之前执行）。
     *
     * <p>不依赖全局背景色，改用列纹理判据：逐列统计"行向亮度梯度行数"——
     * 照片切片几乎逐行有纹理，白底板/纯色产品/页边几乎无纹理。从左右边缘向内，
     * 跳过近纯色列（页边/底板），若遇到"高纹理带（≤30% 宽）+ 近纯色隔断 + 内侧仍有内容"
     * 的结构，判定为 AI 框越界带入的邻图切片并截断。纯色产品与图边缘相连时
     * 找不到隔断，纹理产品贴近边缘时带宽超限，均不会误切。</p>
     */
    private static SliceResult removeEdgeSlices(BufferedImage image, TrimOptions options) {
        if (!options.recropContent()) {
            return new SliceResult(image, 0);
        }
        int width = image.getWidth();
        int height = image.getHeight();
        // 行采样加速：梯度统计按步长抽行，分辨率足够区分照片纹理与纯色
        int step = Math.max(1, height / 400);
        int samples = 0;
        double[] texture = new double[width];
        double[] colLumMean = new double[width];
        for (int y = step; y < height; y += step) {
            samples++;
            for (int x = 0; x < width; x++) {
                int lum = luminance(image.getRGB(x, y));
                colLumMean[x] += lum;
                if (Math.abs(lum - luminance(image.getRGB(x, y - step))) >= COLUMN_TEXTURE_GRADIENT) {
                    texture[x]++;
                }
            }
        }
        if (samples == 0) {
            return new SliceResult(image, 0);
        }
        for (int x = 0; x < width; x++) {
            colLumMean[x] /= samples;
        }

        int left = cutEdgeByTexture(texture, colLumMean, samples, width, true);
        int right = cutEdgeByTexture(texture, colLumMean, samples, width, false);
        // 防御：切除后宽度不足原图一半时放弃
        if (right - left + 1 < width / 2) {
            log.debug("边缘切片切除幅度异常，放弃");
            return new SliceResult(image, 0);
        }
        if (left == 0 && right == width - 1) {
            return new SliceResult(image, 0);
        }
        return new SliceResult(image.getSubimage(left, 0, right - left + 1, height), left);
    }

    /**
     * 单侧边缘切片截断列；无切片时返回原边缘（左 0 / 右 width-1）。
     *
     * @param texture  每列纹理行数（行向亮度梯度计数）
     * @param samples  行采样数，用于归一化
     * @param fromLeft true 处理左边缘，false 处理右边缘
     */
    private static int cutEdgeByTexture(double[] texture, double[] colLumMean, int samples, int width,
                                        boolean fromLeft) {
        int minGap = Math.max(3, (int) Math.round(width * 0.01));
        int edge = fromLeft ? 0 : width - 1;
        int step = fromLeft ? 1 : -1;
        double sliceMin = SLICE_COLUMN_MIN_TEXTURE * samples;
        double gapMax = GAP_COLUMN_MAX_TEXTURE * samples;

        // 跳过近纯色列（页边/白底板），定位最外侧纹理列
        int outer = edge;
        while (outer >= 0 && outer < width && texture[outer] <= gapMax) {
            outer += step;
        }
        if (outer < 0 || outer >= width) {
            return edge; // 整图近乎纯色，无切片
        }
        // 越过纹理带（候选切片）
        int x = outer;
        while (x >= 0 && x < width && texture[x] > gapMax) {
            x += step;
        }
        int bandEnd = x - step; // 纹理带内侧最后一列
        // 量出隔断：近纯色且颜色与隔断起始列一致（纯色产品列颜色不同，行走停止）
        double gapLum = x >= 0 && x < width ? colLumMean[x] : -1;
        int gap = 0;
        while (x >= 0 && x < width && texture[x] <= gapMax
            && Math.abs(colLumMean[x] - gapLum) <= COLUMN_TEXTURE_GRADIENT) {
            gap++;
            x += step;
        }
        if (x < 0 || x >= width || gap < minGap) {
            return edge; // 无隔断或隔断太窄：与主体相连或是纯色产品，不裁
        }
        int bandWidth = fromLeft ? bandEnd + 1 : width - bandEnd;
        if (bandWidth <= 0 || bandWidth > SLICE_BAND_MAX_RATIO * width) {
            return edge; // 带宽超限：是产品主体而非切片
        }
        // 切片带纹理校验：照片切片平均纹理行占比达标（只统计纹理带本身，不含外侧页边）
        int texFrom = Math.min(outer, bandEnd);
        int texTo = Math.max(outer, bandEnd);
        double bandTexture = 0;
        for (int bx = texFrom; bx <= texTo; bx++) {
            bandTexture += texture[bx];
        }
        if (bandTexture / (texTo - texFrom + 1) < sliceMin) {
            return edge;
        }
        log.debug("切除{}边缘邻图切片：宽 {}px", fromLeft ? "左" : "右", bandWidth);
        // x 指向隔断内侧（主内容侧）的第一列内容，即新的图边缘
        return x;
    }

    /**
     * 像素亮度（Rec.601）。
     */
    private static int luminance(int rgb) {
        return (299 * ((rgb >> 16) & 0xFF) + 587 * ((rgb >> 8) & 0xFF) + 114 * (rgb & 0xFF)) / 1000;
    }

    /**
     * 按内容宽高比例补背景色边距。
     */
    private static BufferedImage pad(BufferedImage image, double padRatio, int bgRgb) {
        if (padRatio <= 0.0) {
            return image;
        }
        int padX = (int) Math.round(image.getWidth() * padRatio);
        int padY = (int) Math.round(image.getHeight() * padRatio);
        if (padX <= 0 && padY <= 0) {
            return image;
        }
        BufferedImage padded = new BufferedImage(
            image.getWidth() + 2 * padX, image.getHeight() + 2 * padY, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = padded.createGraphics();
        g.setColor(new java.awt.Color(bgRgb));
        g.fillRect(0, 0, padded.getWidth(), padded.getHeight());
        g.drawImage(image, padX, padY, null);
        g.dispose();
        return padded;
    }

    /**
     * 估计四个角落采样块各自的平均颜色（抗非白底页面）。
     *
     * @return 四角均值 RGB，顺序：左上、右上、左下、右下
     */
    private static int[] estimateCornerMeans(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int sample = Math.min(CORNER_SAMPLE, Math.min(width, height));
        int[][] corners = {{0, 0}, {width - sample, 0}, {0, height - sample}, {width - sample, height - sample}};
        int[] means = new int[4];
        for (int i = 0; i < corners.length; i++) {
            long r = 0;
            long g = 0;
            long b = 0;
            int count = 0;
            for (int dy = 0; dy < sample; dy++) {
                for (int dx = 0; dx < sample; dx++) {
                    int rgb = image.getRGB(corners[i][0] + dx, corners[i][1] + dy);
                    r += (rgb >> 16) & 0xFF;
                    g += (rgb >> 8) & 0xFF;
                    b += rgb & 0xFF;
                    count++;
                }
            }
            means[i] = ((int) (r / count) << 16) | ((int) (g / count) << 8) | (int) (b / count);
        }
        return means;
    }

    /** 四角均值的逐通道中位数（抗离群：某个角落落在文字/装饰上时不带偏背景色估计）。 */
    private static int medianOf(int[] cornerMeans) {
        int n = cornerMeans.length;
        int[] rs = new int[n];
        int[] gs = new int[n];
        int[] bs = new int[n];
        for (int i = 0; i < n; i++) {
            rs[i] = (cornerMeans[i] >> 16) & 0xFF;
            gs[i] = (cornerMeans[i] >> 8) & 0xFF;
            bs[i] = cornerMeans[i] & 0xFF;
        }
        java.util.Arrays.sort(rs);
        java.util.Arrays.sort(gs);
        java.util.Arrays.sort(bs);
        // 偶数个样本取中间两值的平均
        int r = (rs[(n - 1) / 2] + rs[n / 2]) / 2;
        int g = (gs[(n - 1) / 2] + gs[n / 2]) / 2;
        int b = (bs[(n - 1) / 2] + bs[n / 2]) / 2;
        return (r << 16) | (g << 8) | b;
    }

    /** 四角颜色两两差异均不超过容差时视为纯色背景。 */
    private static boolean cornersConsistent(int[] cornerMeans, int tolerance) {
        for (int i = 0; i < cornerMeans.length; i++) {
            for (int j = i + 1; j < cornerMeans.length; j++) {
                if (!channelsNear(cornerMeans[i], cornerMeans[j], tolerance)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean channelsNear(int rgb1, int rgb2, int tolerance) {
        return Math.abs(((rgb1 >> 16) & 0xFF) - ((rgb2 >> 16) & 0xFF)) <= tolerance
            && Math.abs(((rgb1 >> 8) & 0xFF) - ((rgb2 >> 8) & 0xFF)) <= tolerance
            && Math.abs((rgb1 & 0xFF) - (rgb2 & 0xFF)) <= tolerance;
    }

    private static boolean isBlankRow(BufferedImage image, int y, int fromX, int toX, int bgRgb,
                                      TrimOptions options) {
        int total = toX - fromX;
        int blank = 0;
        int nonBgRun = 0;
        int maxNonBgRun = 0;
        for (int x = fromX; x < toX; x++) {
            if (isNearBackground(image.getRGB(x, y), bgRgb)) {
                blank++;
                nonBgRun = 0;
            } else {
                nonBgRun++;
                maxNonBgRun = Math.max(maxNonBgRun, nonBgRun);
            }
        }
        if (options.protectContentRun() && maxNonBgRun >= CONTENT_RUN_LENGTH) {
            return false;
        }
        return (double) blank / total >= options.blankRatio();
    }

    private static boolean isBlankColumn(BufferedImage image, int x, int fromY, int toY, int bgRgb,
                                         TrimOptions options) {
        int total = toY - fromY + 1;
        int blank = 0;
        int nonBgRun = 0;
        int maxNonBgRun = 0;
        for (int y = fromY; y <= toY; y++) {
            if (isNearBackground(image.getRGB(x, y), bgRgb)) {
                blank++;
                nonBgRun = 0;
            } else {
                nonBgRun++;
                maxNonBgRun = Math.max(maxNonBgRun, nonBgRun);
            }
        }
        if (options.protectContentRun() && maxNonBgRun >= CONTENT_RUN_LENGTH) {
            return false;
        }
        return (double) blank / total >= options.blankRatio();
    }

    private static boolean isNearBackground(int rgb, int bgRgb) {
        return Math.abs(((rgb >> 16) & 0xFF) - ((bgRgb >> 16) & 0xFF)) <= COLOR_TOLERANCE
            && Math.abs(((rgb >> 8) & 0xFF) - ((bgRgb >> 8) & 0xFF)) <= COLOR_TOLERANCE
            && Math.abs((rgb & 0xFF) - (bgRgb & 0xFF)) <= COLOR_TOLERANCE;
    }
}
