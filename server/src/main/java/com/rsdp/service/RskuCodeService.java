package com.rsdp.service;

import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.RskuCodeMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * RSKU 业务编码生成服务。
 *
 * <p>编码规则：{rspu_code}-{factory_code}-{material_code}-{variant_seq}
 * 例：FS-MC-001-M-A004-PE-001
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RskuCodeService {

    private static final long MAX_SEQUENCE = 999L;

    private final RskuCodeMapper rskuCodeMapper;
    private final RspuMapper rspuMapper;
    private final RskuSupplyMapper rskuSupplyMapper;

    /**
     * 生成下一个 RSKU 业务编码。
     *
     * @param rspuCode     RSPU 业务编码
     * @param factoryCode  工厂代码
     * @param materialCode 材质码
     * @return 业务编码
     */
    public String generateNextCode(String rspuCode, String factoryCode, String materialCode) {
        if (!StringUtils.hasText(rspuCode)) {
            throw new BusinessException("RSPU 业务编码不能为空");
        }
        if (!StringUtils.hasText(factoryCode)) {
            throw new BusinessException("工厂代码不能为空");
        }
        if (!StringUtils.hasText(materialCode)) {
            throw new BusinessException("材质码不能为空");
        }
        String effectiveRspuCode = rspuCode.trim().toUpperCase();
        String effectiveFactoryCode = factoryCode.trim().toUpperCase();
        String effectiveMaterialCode = materialCode.trim().toUpperCase();

        Long nextSeq = rskuCodeMapper.allocateSequence(effectiveRspuCode, effectiveFactoryCode, effectiveMaterialCode);
        if (nextSeq == null) {
            throw new BusinessException("无法生成 RSKU 编码流水号");
        }
        if (nextSeq > MAX_SEQUENCE) {
            throw new BusinessException(
                String.format("RSKU 编码流水号已超过最大值 %d，请联系管理员扩容编码规则", MAX_SEQUENCE));
        }
        return String.format("%s-%s-%s-%03d", effectiveRspuCode, effectiveFactoryCode, effectiveMaterialCode, nextSeq);
    }

    /**
     * 为指定 RSKU 生成并写入业务编码。
     *
     * <p>若该 RSKU 已有业务编码，则直接返回；否则基于所属 RSPU 业务编码生成新编码并写入。</p>
     *
     * @param rskuId       RSKU ID
     * @param rspuId       所属 RSPU ID
     * @param factoryCode  工厂代码
     * @param materialCode 材质码
     * @return 生成的业务编码
     */
    @Transactional
    public String assignCode(String rskuId, String rspuId, String factoryCode, String materialCode) {
        if (!StringUtils.hasText(rskuId)) {
            throw new BusinessException("RSKU ID 不能为空");
        }
        RskuSupply rsku = rskuSupplyMapper.selectById(rskuId);
        if (rsku != null && StringUtils.hasText(rsku.getRskuCode())) {
            return rsku.getRskuCode();
        }

        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null) {
            throw new BusinessException("RSPU 不存在: " + rspuId);
        }
        if (!StringUtils.hasText(rspu.getRspuCode())) {
            throw new BusinessException("RSPU 尚未生成业务编码，无法生成 RSKU 编码: " + rspuId);
        }

        String code = generateNextCode(rspu.getRspuCode(), factoryCode, materialCode);
        if (rsku == null) {
            // 调用方尚未持久化，仅返回编码，由调用方写入
            return code;
        }
        rsku.setRskuCode(code);
        rsku.setUpdatedAt(java.time.LocalDateTime.now());
        try {
            rskuSupplyMapper.updateById(rsku);
        } catch (DataIntegrityViolationException e) {
            log.warn("RSKU 业务编码唯一冲突，重试生成，rskuId={}", rskuId);
            code = generateNextCode(rspu.getRspuCode(), factoryCode, materialCode);
            rsku.setRskuCode(code);
            rskuSupplyMapper.updateById(rsku);
        }
        return code;
    }
}
