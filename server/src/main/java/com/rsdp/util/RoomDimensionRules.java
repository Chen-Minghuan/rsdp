package com.rsdp.util;

import com.rsdp.config.properties.FloorPlanRulesProperties;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 空间尺寸硬规则引擎（户型图链路 v3.0 §5；P2 起支持客厅/餐厅/卧室多空间模板）。
 *
 * <p>纯静态工具类，不依赖任何 Mapper/Service：候选产品的事实数据（尺寸、场景、报价、
 * 风格分）由 {@code FloorPlanMatchingService} 装配成 {@link CandidateProduct} 传入，
 * 规则阈值全部来自 {@link FloorPlanRulesProperties}（{@code rsdp.floor-plan.rules}），
 * 双端（管理端/官网）共用本类，任何一端不允许私写规则副本。</p>
 *
 * <p>规则按优先级依次执行（§5.2）：</p>
 * <ul>
 *   <li>R4 品类过滤：rspu_scene 含 LIVING 且品类命中客厅模板；</li>
 *   <li>R5 数据质量：尺寸可解析（dimensions 或 sizeText）且 ≥1 条有效 RSKU 报价；</li>
 *   <li>R1 面积分档：&lt;12㎡ 沙发 ≤2200 且不推 L 型；12~20㎡ 三人位为主（≤2400）；&gt;20㎡ 可 L 型/组合；</li>
 *   <li>R2 沙发长度 ≤ min(沙发墙长 × sofaWallRatio, 沙发墙长 - walkwayMinMm)
 *       （沙发墙朝向可指定 width/depth，默认 width=开间方向墙，§8 P1）；</li>
 *   <li>R3 链式校验（沿沙发墙垂直方向：沙发深 + 通道 + 茶几深 + 过人通道 + 电视柜深），
 *       不满足依次降级：去电视柜 → 换窄茶几 → 换小沙发。</li>
 * </ul>
 *
 * <p>输出：每品类 ≤{@value #MAX_CANDIDATES_PER_CATEGORY} 条、总量 ≤{@value #MAX_TOTAL_CANDIDATES}
 * （对齐 AiMatchingService.MAX_CANDIDATES），品类内按风格匹配分降序 + 创建时间降序。</p>
 */
public final class RoomDimensionRules {

    /** 品类码：沙发。 */
    public static final String CATEGORY_SOFA = "SF";
    /** 品类码：茶几。 */
    public static final String CATEGORY_TEA_TABLE = "TB";
    /** 品类码：电视柜（柜类）。 */
    public static final String CATEGORY_TV_CABINET = "FC";
    /** 品类码：休闲椅（座椅）。 */
    public static final String CATEGORY_LEISURE_CHAIR = "FS";
    /** 品类码：餐桌。 */
    public static final String CATEGORY_DINING_TABLE = "DT";
    /** 品类码：床。 */
    public static final String CATEGORY_BED = "BD";

    /** 客厅场景字典码。 */
    public static final String SCENE_LIVING = "LIVING";
    /** 卧室场景字典码。 */
    public static final String SCENE_BEDROOM = "BEDROOM";

    /** 客厅品类组合模板（§5.1，按输出顺序）。 */
    public static final List<String> TEMPLATE_CATEGORIES =
        List.of(CATEGORY_SOFA, CATEGORY_TEA_TABLE, CATEGORY_TV_CABINET, CATEGORY_LEISURE_CHAIR);

    /** 必选品类。 */
    public static final Set<String> REQUIRED_CATEGORIES = Set.of(CATEGORY_SOFA);

    /** 方案每品类最大选品数（§5.1 maxPerCategory）。 */
    public static final Map<String, Integer> MAX_PER_CATEGORY = Map.of(
        CATEGORY_SOFA, 1,
        CATEGORY_TEA_TABLE, 1,
        CATEGORY_TV_CABINET, 1,
        CATEGORY_LEISURE_CHAIR, 2
    );

    /**
     * 空间模板（v3.0 §8 P2 多空间批量搭配）：品类组合 + 必选品类 + 每品类选品上限
     * + 场景字典码（null 表示该空间无对应 scene 字典码，R4 退化为仅品类过滤）。
     *
     * @param templateKey        模板键（LIVING/DINING/BEDROOM）
     * @param categories         品类组合（按输出顺序）
     * @param requiredCategories 必选品类
     * @param maxPerCategory     方案每品类最大选品数
     * @param sceneCode          rspu_scene 场景字典码，可空
     */
    public record RoomTemplate(String templateKey, List<String> categories,
                               Set<String> requiredCategories,
                               Map<String, Integer> maxPerCategory, String sceneCode) {
    }

    /** 客厅模板（§5.1）。 */
    public static final RoomTemplate LIVING_TEMPLATE = new RoomTemplate(
        "LIVING", TEMPLATE_CATEGORIES, REQUIRED_CATEGORIES, MAX_PER_CATEGORY, SCENE_LIVING);

    /** 餐厅模板（P2）：必选 DT 餐桌，可选 FS 餐椅（≤4）；scene 字典无 DINING 码，仅品类过滤。 */
    public static final RoomTemplate DINING_TEMPLATE = new RoomTemplate(
        "DINING",
        List.of(CATEGORY_DINING_TABLE, CATEGORY_LEISURE_CHAIR),
        Set.of(CATEGORY_DINING_TABLE),
        Map.of(CATEGORY_DINING_TABLE, 1, CATEGORY_LEISURE_CHAIR, 4),
        null);

    /** 卧室模板（P2）：必选 BD 床，可选 FC 柜类（≤2）；场景码 BEDROOM。 */
    public static final RoomTemplate BEDROOM_TEMPLATE = new RoomTemplate(
        "BEDROOM",
        List.of(CATEGORY_BED, CATEGORY_TV_CABINET),
        Set.of(CATEGORY_BED),
        Map.of(CATEGORY_BED, 1, CATEGORY_TV_CABINET, 2),
        SCENE_BEDROOM);

    /**
     * room_type 字典码（floor_plan_room.room_type，如 LIVING_ROOM/DINING_ROOM/BEDROOM）
     * → 空间模板；不支持的空间类型返回 null（由服务层转 400 中文提示）。
     *
     * @param roomType 空间类型字典码（兼容 LIVING/DINING 简写）
     * @return 空间模板，不支持返回 null
     */
    public static RoomTemplate templateForRoomType(String roomType) {
        if (roomType == null) {
            return null;
        }
        return switch (roomType.trim().toUpperCase()) {
            case "LIVING", "LIVING_ROOM" -> LIVING_TEMPLATE;
            case "DINING", "DINING_ROOM" -> DINING_TEMPLATE;
            case "BEDROOM" -> BEDROOM_TEMPLATE;
            default -> null;
        };
    }

    /** 每品类候选上限。 */
    public static final int MAX_CANDIDATES_PER_CATEGORY = 6;

    /** 候选总量上限（对齐 AiMatchingService.MAX_CANDIDATES）。 */
    public static final int MAX_TOTAL_CANDIDATES = 30;

    /** 沙发墙朝向：开间方向墙（默认）。 */
    public static final String SOFA_WALL_WIDTH = "width";

    /** 沙发墙朝向：进深方向墙。 */
    public static final String SOFA_WALL_DEPTH = "depth";

    /** R1 中档客厅（12~20㎡）沙发长度上限 mm（三人位 1800~2400 的上沿，§5.2 文本值）。 */
    public static final int MID_LIVING_SOFA_MAX_MM = 2400;

    private static final Set<String> TEMPLATE_CATEGORY_SET = Set.copyOf(TEMPLATE_CATEGORIES);

    private RoomDimensionRules() {
        // 工具类禁止实例化
    }

    /**
     * 规则引擎输入：单个候选产品的事实数据（由服务层装配）。
     *
     * @param rspuId        RSPU ID
     * @param categoryCode  品类码（SF/TB/FC/FS/DT/BD 等）
     * @param widthMm       产品长度（沿墙方向，mm），null 表示尺寸不可解析（R5 不通过）
     * @param depthMm       产品深度（mm），SF/TB/FC 参与 R3 链式校验时必填
     * @param lType         是否 L 型/转角/贵妃类沙发
     * @param sceneMatched  rspu_scene 是否命中模板场景码（模板无场景码时恒 true，规则侧不检查）
     * @param hasValidRsku  是否有 ≥1 条有效 RSKU 报价（未软删且有出厂价）
     * @param styleScore    风格匹配分（product_style_match.overall_score，无偏好或无数据为 0）
     * @param createdAt     创建时间（排序兜底）
     */
    public record CandidateProduct(
        String rspuId,
        String categoryCode,
        Integer widthMm,
        Integer depthMm,
        boolean lType,
        boolean sceneMatched,
        boolean hasValidRsku,
        double styleScore,
        LocalDateTime createdAt
    ) {
    }

    /**
     * 筛选结果：按品类分组的候选 + R3 降级说明。
     *
     * @param categoryOrder         品类输出顺序（模板品类顺序）
     * @param candidatesByCategory  按品类分组的候选
     * @param degradations          降级说明
     */
    public record FilterResult(
        List<String> categoryOrder,
        Map<String, List<CandidateProduct>> candidatesByCategory,
        List<String> degradations
    ) {
        /**
         * 兼容构造：客厅模板顺序（{@link #TEMPLATE_CATEGORIES}）。
         */
        public FilterResult(Map<String, List<CandidateProduct>> candidatesByCategory,
                            List<String> degradations) {
            this(TEMPLATE_CATEGORIES, candidatesByCategory, degradations);
        }

        /**
         * 按模板品类顺序展开全部候选。
         *
         * @return 候选列表（总量 ≤ {@link #MAX_TOTAL_CANDIDATES}）
         */
        public List<CandidateProduct> flatCandidates() {
            List<CandidateProduct> result = new ArrayList<>();
            for (String category : categoryOrder) {
                result.addAll(candidatesByCategory.getOrDefault(category, List.of()));
            }
            return result;
        }
    }

    /**
     * 计算客厅面积（㎡）。
     *
     * @param widthMm 开间 mm
     * @param depthMm 进深 mm
     * @return 面积（㎡）
     */
    public static double areaM2(int widthMm, int depthMm) {
        return (double) widthMm * depthMm / 1_000_000.0;
    }

    /**
     * R1 面积分档的沙发长度上限（mm）；大客厅无分档上限返回 {@link Integer#MAX_VALUE}。
     *
     * @param areaM2 客厅面积（㎡）
     * @param rules  规则配置
     * @return 分档上限
     */
    public static int tierSofaMaxMm(double areaM2, FloorPlanRulesProperties rules) {
        if (areaM2 < rules.getSmallLivingAreaM2()) {
            return rules.getSmallLivingSofaMaxMm();
        }
        if (areaM2 <= rules.getLargeLivingAreaM2()) {
            return MID_LIVING_SOFA_MAX_MM;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * R1 + R2 合并后的沙发长度上限（mm，沙发墙=开间方向假设）：
     * min(面积分档上限, 开间 × sofaWallRatio, 开间 - walkwayMinMm)。
     *
     * @param widthMm 开间 mm
     * @param depthMm 进深 mm
     * @param rules   规则配置
     * @return 沙发长度上限
     */
    public static int sofaMaxLengthMm(int widthMm, int depthMm, FloorPlanRulesProperties rules) {
        return sofaMaxLengthMm(widthMm, depthMm, SOFA_WALL_WIDTH, rules);
    }

    /**
     * R1 + R2 合并后的沙发长度上限（mm）：
     * min(面积分档上限, 沙发墙长 × sofaWallRatio, 沙发墙长 - walkwayMinMm)。
     *
     * <p>沙发墙朝向（v3.0 §8 P1）：{@link #SOFA_WALL_WIDTH}（默认）墙长取开间，
     * {@link #SOFA_WALL_DEPTH} 墙长取进深；未知值按 width 处理（防御性归一，
     * 入口校验在服务/DTO 层）。</p>
     *
     * @param widthMm  开间 mm
     * @param depthMm  进深 mm
     * @param sofaWall 沙发墙朝向（width/depth，null 按 width）
     * @param rules    规则配置
     * @return 沙发长度上限
     */
    public static int sofaMaxLengthMm(int widthMm, int depthMm, String sofaWall,
                                      FloorPlanRulesProperties rules) {
        int tierCap = tierSofaMaxMm(areaM2(widthMm, depthMm), rules);
        int wallMm = SOFA_WALL_DEPTH.equals(sofaWall) ? depthMm : widthMm;
        int wallCap = (int) (wallMm * rules.getSofaWallRatio());
        int walkwayCap = wallMm - rules.getWalkwayMinMm();
        return Math.min(tierCap, Math.min(wallCap, walkwayCap));
    }

    /**
     * 执行 R1~R5 全部硬规则筛选（沙发墙=开间方向假设，与
     * {@link #filter(int, int, String, List, FloorPlanRulesProperties)} 传 width 等价）。
     *
     * @param widthMm    客厅开间 mm
     * @param depthMm    客厅进深 mm
     * @param candidates 候选产品事实数据（服务层装配）
     * @param rules      规则配置
     * @return 按品类分组的候选 + R3 降级说明
     */
    public static FilterResult filter(int widthMm, int depthMm,
                                      List<CandidateProduct> candidates,
                                      FloorPlanRulesProperties rules) {
        return filter(widthMm, depthMm, SOFA_WALL_WIDTH, candidates, rules);
    }

    /**
     * 执行 R1~R5 全部硬规则筛选。
     *
     * <p>沙发墙朝向（v3.0 §8 P1）：width（默认）时 R2 沙发/电视柜上限按开间墙长、
     * R3 链式校验沿进深方向；depth 时两者方向对调（R2 按进深墙长，R3 沿开间方向核算）。
     * null/未知朝向按 width 处理（防御性归一，保证无朝向时行为完全不变）。</p>
     *
     * @param widthMm    客厅开间 mm
     * @param depthMm    客厅进深 mm
     * @param sofaWall   沙发墙朝向（width/depth，null 按 width）
     * @param candidates 候选产品事实数据（服务层装配）
     * @param rules      规则配置
     * @return 按品类分组的候选 + R3 降级说明
     */
    public static FilterResult filter(int widthMm, int depthMm, String sofaWall,
                                      List<CandidateProduct> candidates,
                                      FloorPlanRulesProperties rules) {
        List<String> degradations = new ArrayList<>();
        boolean sofaOnDepthWall = SOFA_WALL_DEPTH.equals(sofaWall);
        // 沙发墙方向的墙长（R2）；链式校验方向与之垂直（R3）
        int wallMm = sofaOnDepthWall ? depthMm : widthMm;
        int chainMm = sofaOnDepthWall ? widthMm : depthMm;

        // R4 品类过滤 + R5 数据质量过滤
        Map<String, List<CandidateProduct>> byCategory = new LinkedHashMap<>();
        for (String category : TEMPLATE_CATEGORIES) {
            byCategory.put(category, new ArrayList<>());
        }
        for (CandidateProduct candidate : candidates) {
            if (!candidate.sceneMatched() || !TEMPLATE_CATEGORY_SET.contains(candidate.categoryCode())) {
                continue; // R4
            }
            if (candidate.widthMm() == null || candidate.widthMm() <= 0 || !candidate.hasValidRsku()) {
                continue; // R5
            }
            byCategory.get(candidate.categoryCode()).add(candidate);
        }

        double area = areaM2(widthMm, depthMm);
        int sofaMax = sofaMaxLengthMm(widthMm, depthMm, sofaWall, rules);

        // R1 面积分档 + R2 沙发长度约束（仅沙发品类；L 型仅大客厅可推）
        boolean lTypeAllowed = area > rules.getLargeLivingAreaM2();
        List<CandidateProduct> sofas = byCategory.get(CATEGORY_SOFA)
            .stream()
            .filter(c -> c.widthMm() <= sofaMax)
            .filter(c -> lTypeAllowed || !c.lType())
            .toList();
        byCategory.put(CATEGORY_SOFA, new ArrayList<>(sofas));

        // 电视柜长度 ≤ 沙发墙对面墙长 × tvCabinetWallRatio（电视柜墙与沙发墙同向平行）
        int tvCabinetMax = (int) (wallMm * rules.getTvCabinetWallRatio());
        List<CandidateProduct> tvCabinets = byCategory.get(CATEGORY_TV_CABINET)
            .stream()
            .filter(c -> c.widthMm() <= tvCabinetMax)
            .toList();
        byCategory.put(CATEGORY_TV_CABINET, new ArrayList<>(tvCabinets));

        // R3 链式校验（含降级链）：沿沙发墙的垂直方向核算（默认 width 朝向时即进深方向）
        applyDepthChain(chainMm, byCategory, rules, degradations);

        return sortAndTruncate(byCategory, TEMPLATE_CATEGORIES, degradations);
    }

    /**
     * 餐厅规则筛选（v3.0 §8 P2，{@link #DINING_TEMPLATE}：必选 DT 餐桌 + 可选 FS 餐椅 ≤4）。
     *
     * <p>复用 R1/R2/R4/R5 骨架的合理简化（简化假设）：</p>
     * <ul>
     *   <li>R4 退化为仅品类过滤：scene 字典无 DINING 场景码（V1 种子仅
     *       LIVING/STUDY/BEDROOM/CAFE/OFFICE/HOTEL），餐厅场景以品类码（DT/FS）表达；</li>
     *   <li>R2 简化：假设餐桌靠开间方向墙摆放（或居中按最不利靠墙核算），
     *       餐桌长度 ≤ min(开间 × sofaWallRatio, 开间 - walkwayMinMm)（至少一侧过人通道）；</li>
     *   <li>餐椅绕桌摆放不单独占墙，不做尺寸约束；</li>
     *   <li>不做 R1 面积分档与 R3 链式校验（餐厅无"沙发-茶几-电视柜"纵深链）。</li>
     * </ul>
     *
     * @param widthMm    餐厅开间 mm
     * @param depthMm    餐厅进深 mm
     * @param candidates 候选产品事实数据（服务层装配）
     * @param rules      规则配置
     * @return 按品类分组的候选（DT → FS）
     */
    public static FilterResult filterDining(int widthMm, int depthMm,
                                            List<CandidateProduct> candidates,
                                            FloorPlanRulesProperties rules) {
        Map<String, List<CandidateProduct>> byCategory =
            filterByTemplate(candidates, DINING_TEMPLATE);
        // R2 简化：餐桌长度 ≤ min(开间 × sofaWallRatio, 开间 - walkwayMinMm)
        int tableMax = Math.min((int) (widthMm * rules.getSofaWallRatio()),
            widthMm - rules.getWalkwayMinMm());
        byCategory.put(CATEGORY_DINING_TABLE, byCategory.get(CATEGORY_DINING_TABLE).stream()
            .filter(c -> c.widthMm() <= tableMax)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        return sortAndTruncate(byCategory, DINING_TEMPLATE.categories(), List.of());
    }

    /**
     * 卧室规则筛选（v3.0 §8 P2，{@link #BEDROOM_TEMPLATE}：必选 BD 床 + 可选 FC 柜类 ≤2）。
     *
     * <p>复用 R1/R2/R4/R5 骨架的合理简化（简化假设）：</p>
     * <ul>
     *   <li>R4：品类过滤（BD/FC）+ rspu_scene 含 BEDROOM（scene 字典有 BEDROOM 码）；</li>
     *   <li>R2 简化：假设床靠开间方向墙摆放、床长沿墙，床长 ≤ 开间 - walkwayMinMm
     *       （床侧至少保留一条过人通道）；</li>
     *   <li>柜类可靠其他墙摆放，不做尺寸约束；</li>
     *   <li>不做 R1 面积分档与 R3 链式校验（卧室无纵深摆放链）。</li>
     * </ul>
     *
     * @param widthMm    卧室开间 mm
     * @param depthMm    卧室进深 mm
     * @param candidates 候选产品事实数据（服务层装配）
     * @param rules      规则配置
     * @return 按品类分组的候选（BD → FC）
     */
    public static FilterResult filterBedroom(int widthMm, int depthMm,
                                             List<CandidateProduct> candidates,
                                             FloorPlanRulesProperties rules) {
        Map<String, List<CandidateProduct>> byCategory =
            filterByTemplate(candidates, BEDROOM_TEMPLATE);
        // R2 简化：床长 ≤ 开间 - 过道
        int bedMax = widthMm - rules.getWalkwayMinMm();
        byCategory.put(CATEGORY_BED, byCategory.get(CATEGORY_BED).stream()
            .filter(c -> c.widthMm() <= bedMax)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        return sortAndTruncate(byCategory, BEDROOM_TEMPLATE.categories(), List.of());
    }

    /**
     * 模板化 R4 品类过滤 + R5 数据质量过滤（多空间模板共用）：
     * 品类命中模板（模板有场景码时还要求命中场景），尺寸可解析且有 ≥1 条有效 RSKU 报价。
     */
    private static Map<String, List<CandidateProduct>> filterByTemplate(
        List<CandidateProduct> candidates, RoomTemplate template) {
        Set<String> categorySet = Set.copyOf(template.categories());
        Map<String, List<CandidateProduct>> byCategory = new LinkedHashMap<>();
        for (String category : template.categories()) {
            byCategory.put(category, new ArrayList<>());
        }
        for (CandidateProduct candidate : candidates) {
            if (!categorySet.contains(candidate.categoryCode())) {
                continue; // R4 品类
            }
            if (template.sceneCode() != null && !candidate.sceneMatched()) {
                continue; // R4 场景（模板有场景码时）
            }
            if (candidate.widthMm() == null || candidate.widthMm() <= 0 || !candidate.hasValidRsku()) {
                continue; // R5
            }
            byCategory.get(candidate.categoryCode()).add(candidate);
        }
        return byCategory;
    }

    /**
     * 排序 + 每品类截断（≤{@value #MAX_CANDIDATES_PER_CATEGORY}）+ 总量截断
     * （≤{@value #MAX_TOTAL_CANDIDATES}），按模板品类顺序输出；品类内按风格匹配分降序
     * + 创建时间降序。
     */
    private static FilterResult sortAndTruncate(Map<String, List<CandidateProduct>> byCategory,
                                                List<String> categoryOrder,
                                                List<String> degradations) {
        Map<String, List<CandidateProduct>> result = new LinkedHashMap<>();
        int remaining = MAX_TOTAL_CANDIDATES;
        for (String category : categoryOrder) {
            List<CandidateProduct> sorted = byCategory.getOrDefault(category, List.of()).stream()
                .sorted(Comparator.comparingDouble(CandidateProduct::styleScore).reversed()
                    .thenComparing(c -> c.createdAt() != null ? c.createdAt() : LocalDateTime.MIN,
                        Comparator.reverseOrder()))
                .limit(Math.min(MAX_CANDIDATES_PER_CATEGORY, remaining))
                .toList();
            result.put(category, sorted);
            remaining -= sorted.size();
        }
        return new FilterResult(categoryOrder, result, degradations);
    }

    /**
     * R3 链式校验：沙发深 + 通道(sofaTeaMinMm) + 茶几深 + 过人通道(walkwayMinMm) + 电视柜深
     * ≤ 链式方向可用尺寸。
     *
     * <p>链式方向与沙发墙垂直：沙发墙=开间方向（默认）时沿进深核算，沙发墙=进深方向时
     * 沿开间核算（v3.0 §8 P1）。沙发/茶几/电视柜深度取各自品类的最小值（最优情形，
     * 能否放下的几何判定）；不满足时依次降级：去电视柜 → 换窄茶几 → 换小沙发（§5.2 R3）。</p>
     *
     * @param chainMm 链式方向可用尺寸（mm）
     */
    private static void applyDepthChain(int chainMm,
                                        Map<String, List<CandidateProduct>> byCategory,
                                        FloorPlanRulesProperties rules,
                                        List<String> degradations) {
        List<CandidateProduct> sofas = byCategory.get(CATEGORY_SOFA);
        if (sofas.isEmpty()) {
            return;
        }
        List<CandidateProduct> teaTables = byCategory.get(CATEGORY_TEA_TABLE);
        List<CandidateProduct> tvCabinets = byCategory.get(CATEGORY_TV_CABINET);

        Integer sofaDepth = minDepth(sofas);
        Integer teaDepth = minDepth(teaTables);
        Integer tvDepth = minDepth(tvCabinets);
        if (sofaDepth == null) {
            // 沙发深度缺失，无法进行链式校验，保守放行（宽度规则已约束）
            return;
        }

        // 完整链：沙发 + 通道 + 茶几 + 过人通道 + 电视柜
        if (teaDepth != null && tvDepth != null) {
            long fullChain = (long) sofaDepth + rules.getSofaTeaMinMm() + teaDepth
                + rules.getWalkwayMinMm() + tvDepth;
            if (fullChain <= chainMm) {
                return;
            }
            // 降级 1：去电视柜
            byCategory.put(CATEGORY_TV_CABINET, new ArrayList<>());
            degradations.add("纵深不足以同时容纳沙发+茶几+电视柜，已按规则去掉电视柜");
        }

        // 无电视柜链：沙发（最浅） + 通道 + 茶几 ≤ 链式方向尺寸，按最优沙发逐候选判定茶几可行性
        if (!teaTables.isEmpty() && teaDepth != null) {
            int maxTeaDepth = chainMm - sofaDepth - rules.getSofaTeaMinMm();
            List<CandidateProduct> feasibleTeaTables = teaTables.stream()
                .filter(c -> c.depthMm() == null || c.depthMm() <= maxTeaDepth)
                .toList();
            if (feasibleTeaTables.isEmpty()) {
                byCategory.put(CATEGORY_TEA_TABLE, new ArrayList<>());
                degradations.add("纵深不足，没有可放下的茶几，已去掉茶几");
                teaDepth = null;
            } else {
                if (feasibleTeaTables.size() < teaTables.size()) {
                    // 降级 2：换窄茶几
                    degradations.add("纵深不足，已按规则筛选更窄的茶几");
                }
                byCategory.put(CATEGORY_TEA_TABLE, new ArrayList<>(feasibleTeaTables));
                teaDepth = minDepth(feasibleTeaTables);
            }
        }

        // 降级 3：换小沙发（按剩余纵深反推沙发深度上限：链式方向尺寸 - 通道 - 茶几）
        int reserved = teaDepth != null ? rules.getSofaTeaMinMm() + teaDepth : 0;
        int maxSofaDepth = chainMm - reserved;
        List<CandidateProduct> smallerSofas = byCategory.get(CATEGORY_SOFA).stream()
            .filter(c -> c.depthMm() == null || c.depthMm() <= maxSofaDepth)
            .toList();
        if (smallerSofas.size() != byCategory.get(CATEGORY_SOFA).size()) {
            degradations.add("纵深不足，已按规则筛选进深更小的沙发");
        }
        byCategory.put(CATEGORY_SOFA, new ArrayList<>(smallerSofas));
    }

    /** 取品类候选中的最小深度（mm）；无候选或全部深度缺失返回 null。 */
    private static Integer minDepth(List<CandidateProduct> candidates) {
        return candidates.stream()
            .map(CandidateProduct::depthMm)
            .filter(d -> d != null && d > 0)
            .min(Comparator.naturalOrder())
            .orElse(null);
    }
}
