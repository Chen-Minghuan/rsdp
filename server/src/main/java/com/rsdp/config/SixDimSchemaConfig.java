package com.rsdp.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 六维标签按产品类别的维度定义。
 *
 * <p>原始六维体系是按座椅/沙发设计的（A轮廓 B靠背 C扶手 D腿部 E材质 F软包），
 * 但柜类、桌几类、吧椅类等产品的关键形态维度不同。本配置为常见类别提供对应的维度语义，
 * 使 AI 识别结果和前端展示都能按类别正确表达。</p>
 */
public final class SixDimSchemaConfig {

    private SixDimSchemaConfig() {
    }

    /**
     * 单类别维度定义：保持 A-F 六个键，每个键对应该类别的语义标签与说明。
     */
    public record SixDimSchema(
        String categoryCode,
        String categoryName,
        Map<String, DimDefinition> dims
    ) {
    }

    public record DimDefinition(String label, String description) {
    }

    private static final Map<String, SixDimSchema> SCHEMAS = new LinkedHashMap<>();

    static {
        register("FS", "座椅/沙发", Map.of(
            "A", new DimDefinition("轮廓形态", "整体造型，如弧形、方盒形、蛋形、模块化组合"),
            "B", new DimDefinition("靠背/背部特征", "靠背高度、包裹性、编织镂空等"),
            "C", new DimDefinition("扶手特征", "扶手形态，如无扶手、环形扶手、实木扶手"),
            "D", new DimDefinition("腿部/底座特征", "腿部形态，如细腿、落地底座、金属框架"),
            "E", new DimDefinition("表面材质", "实木、皮革、布艺、金属等表面材质"),
            "F", new DimDefinition("软包填充形态", "软包饱满度、绗缝、拉扣等填充形态")
        ));
        register("SF", "沙发", Map.of(
            "A", new DimDefinition("轮廓形态", "整体造型，如L型、弧形、一字型、模块化组合"),
            "B", new DimDefinition("靠背/背部特征", "靠背高度、倾斜角度、包裹性"),
            "C", new DimDefinition("扶手特征", "扶手形态，如无扶手、低扶手、宽厚扶手"),
            "D", new DimDefinition("腿部/底座特征", "落地式、细腿、金属脚、悬浮底座"),
            "E", new DimDefinition("表面材质", "皮革、布艺、羊羔绒、天鹅绒等"),
            "F", new DimDefinition("软包填充形态", "坐垫/靠背填充饱满度、绗缝、拉扣")
        ));
        register("TB", "茶几", Map.of(
            "A", new DimDefinition("整体造型/轮廓", "茶几整体形态，如圆形、方形、异形、组合式"),
            "B", new DimDefinition("台面形态", "台面形状、厚度、悬浮/内嵌设计"),
            "C", new DimDefinition("台面边缘/连接部", "台面边缘处理、与支撑结构的连接方式"),
            "D", new DimDefinition("桌腿/底座", "桌腿形态，如细腿、敦实柱腿、金属框架、悬浮底座"),
            "E", new DimDefinition("表面材质", "大理石、玻璃、实木、金属等台面/框架材质"),
            "F", new DimDefinition("收纳/功能件", "抽屉、层板、旋转功能件等附加功能")
        ));
        register("FC", "柜类", Map.of(
            "A", new DimDefinition("整体造型/轮廓", "柜体整体形态，如高柜、矮柜、组合柜、悬浮柜"),
            "B", new DimDefinition("门板/抽屉特征", "门板分割方式、抽屉排列、开放格/封闭格比例"),
            "C", new DimDefinition("拉手/五金特征", "拉手形态，如无拉手、明装拉手、隐藏拉手、金属拉手"),
            "D", new DimDefinition("底座/支脚", "落地式、高脚、金属支脚、悬浮挂墙"),
            "E", new DimDefinition("表面材质", "实木、板材、岩板、藤编、烤漆等表面材质"),
            "F", new DimDefinition("内部结构/功能分区", "内部隔层、抽屉、灯带、视听设备位等功能分区")
        ));
        register("BS", "吧椅", Map.of(
            "A", new DimDefinition("座面轮廓", "座面形状，如圆形、方形、马蹄形"),
            "B", new DimDefinition("靠背/背部特征", "靠背高度、包裹性，无靠背/低靠背/高靠背"),
            "C", new DimDefinition("扶手特征", "扶手形态，如无扶手、小扶手、环形扶手"),
            "D", new DimDefinition("底座/升降杆", "固定底座、三脚/四脚底座、气压升降杆"),
            "E", new DimDefinition("表面材质", "皮革、金属、实木、塑料等"),
            "F", new DimDefinition("软包填充形态", "座面/靠背软包形态、厚度、绗缝")
        ));
        register("OF", "办公家具", Map.of(
            "A", new DimDefinition("整体造型/轮廓", "家具整体形态，如班台、职员桌、会议桌、文件柜"),
            "B", new DimDefinition("工作面/背部特征", "台面/工作面形态，或柜类背板/门板特征"),
            "C", new DimDefinition("侧部/连接部", "侧板、挡板、线槽、扶手/侧翼结构"),
            "D", new DimDefinition("支撑/底座", "桌腿、桌架、柜脚、人体工学底盘"),
            "E", new DimDefinition("表面材质", "实木皮、板材、金属、网布、皮革等"),
            "F", new DimDefinition("功能件/软包", "抽屉、线槽、升降机构、坐垫软包等功能件")
        ));
        register("GENERIC", "通用", Map.of(
            "A", new DimDefinition("整体造型/轮廓", "产品整体外观形态"),
            "B", new DimDefinition("上部/背部特征", "座椅靠背、柜类背板/门板、桌类台面"),
            "C", new DimDefinition("侧部/连接部", "扶手、侧板、台面边缘、连接结构"),
            "D", new DimDefinition("支撑/底座", "腿部、底座、支脚、底盘"),
            "E", new DimDefinition("表面材质", "主要表面材质与纹理"),
            "F", new DimDefinition("功能/填充件", "软包填充、抽屉、层板等功能件")
        ));
    }

    private static void register(String categoryCode, String categoryName, Map<String, DimDefinition> dims) {
        SCHEMAS.put(categoryCode, new SixDimSchema(categoryCode, categoryName, dims));
    }

    /**
     * 根据品类码获取六维定义；未知品类返回通用定义。
     */
    public static SixDimSchema getSchema(String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            return SCHEMAS.get("GENERIC");
        }
        return SCHEMAS.getOrDefault(categoryCode.toUpperCase(), SCHEMAS.get("GENERIC"));
    }

    /**
     * 获取 prompt 中使用的六维说明文本。
     */
    public static String buildPromptDescription(String categoryCode) {
        SixDimSchema schema = getSchema(categoryCode);
        StringBuilder sb = new StringBuilder();
        sb.append("本产品的六维标签定义如下（请严格按 A-F 输出，键名不变）：\n");
        schema.dims().forEach((key, def) ->
            sb.append("  ").append(key).append(" = ").append(def.label())
              .append("：").append(def.description()).append("\n")
        );
        return sb.toString();
    }

    /**
     * 获取所有支持的品类定义（用于测试或枚举展示）。
     */
    public static List<SixDimSchema> getAllSchemas() {
        return List.copyOf(SCHEMAS.values());
    }
}
