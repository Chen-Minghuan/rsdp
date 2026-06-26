package com.rsdp.service;

import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.RspuCodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RSPU 业务编码生成服务。
 *
 * <p>编码规则：{category_code}-{style_code}-{sequence}-{size_code}
 * 例：FS-MC-001-M
 *
 * <p>当未提供 size_code 时，使用 "X" 占位。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RspuCodeService {

    private final RspuCodeMapper rspuCodeMapper;

    /**
     * 生成下一个 RSPU 业务编码。
     *
     * @param categoryCode 品类码，如 FS
     * @param styleCode    风格码，如 MC
     * @param sizeCode     尺寸码，如 M；为空时使用 X
     * @return 业务编码，如 FS-MC-001-M
     */
    public String generateNextCode(String categoryCode, String styleCode, String sizeCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            throw new BusinessException("品类码不能为空");
        }
        if (styleCode == null || styleCode.isBlank()) {
            throw new BusinessException("风格码不能为空");
        }
        String effectiveSizeCode = (sizeCode == null || sizeCode.isBlank()) ? "X" : sizeCode;
        String effectiveStyleCode = styleCode.trim().toUpperCase();
        String effectiveCategoryCode = categoryCode.trim().toUpperCase();

        Long nextSeq = rspuCodeMapper.allocateSequence(effectiveCategoryCode, effectiveStyleCode);
        if (nextSeq == null) {
            throw new BusinessException("无法生成 RSPU 编码流水号");
        }
        return String.format("%s-%s-%03d-%s", effectiveCategoryCode, effectiveStyleCode, nextSeq, effectiveSizeCode);
    }
}
