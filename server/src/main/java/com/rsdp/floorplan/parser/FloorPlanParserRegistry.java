package com.rsdp.floorplan.parser;

import com.rsdp.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 户型图解析器注册表（CAD 户型导入 P3）：按文件类型路由到具体解析器——
 * dwg/dxf → {@link CadFloorPlanParser}，图片/PDF → {@link VisionFloorPlanParser}。
 */
@Component
public class FloorPlanParserRegistry {

    private final List<FloorPlanParser> parsers;

    public FloorPlanParserRegistry(List<FloorPlanParser> parsers) {
        this.parsers = parsers;
    }

    /**
     * 按文件类型解析对应解析器。
     *
     * @param fileType 文件类型
     * @return 支持的解析器
     * @throws BusinessException 无可用解析器时抛出（中文提示）
     */
    public FloorPlanParser resolve(FloorPlanFileType fileType) {
        return parsers.stream()
            .filter(parser -> parser.supports(fileType))
            .findFirst()
            .orElseThrow(() -> new BusinessException("不支持的户型文件类型: " + fileType));
    }

    /**
     * 按扩展名路由（如存储对象键 "images/xxx.dwg" → CAD 解析器）。
     *
     * @param extension 扩展名（不含点，大小写不敏感）
     * @return 支持的解析器
     */
    public FloorPlanParser resolveByExtension(String extension) {
        return resolve(FloorPlanFileType.fromExtension(extension));
    }
}
