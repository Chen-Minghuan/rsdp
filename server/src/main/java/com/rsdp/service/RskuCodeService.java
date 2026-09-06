package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

import java.util.List;

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
    @Transactional(noRollbackFor = BusinessException.class)
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

    /**
     * 容错发号：为指定 RSKU 生成并写入业务编码，失败不抛异常。
     *
     * <p>内部调用 {@link #assignCode}，捕获 {@link BusinessException} 后记录告警并返回 null，
     * 供创建链路使用（如 Excel AI 导入时所属 RSPU 尚未发号的容错场景）。</p>
     *
     * @param rskuId       RSKU ID
     * @param rspuId       所属 RSPU ID
     * @param factoryCode  工厂代码
     * @param materialCode 材质码
     * @return 生成的业务编码；返回 null 表示暂无法发号，rsku_code 留空待补发
     *         （由 {@link #backfillCodesByRspu} 在 RSPU 补码后联动补发）
     */
    public String tryAssignCode(String rskuId, String rspuId, String factoryCode, String materialCode) {
        try {
            return assignCode(rskuId, rspuId, factoryCode, materialCode);
        } catch (BusinessException e) {
            log.warn("RSKU 业务编码暂无法发号，留空待补发，rskuId={}，rspuId={}，factoryCode={}，原因={}",
                rskuId, rspuId, factoryCode, e.getMessage());
            return null;
        }
    }

    /**
     * 为指定 RSPU 下所有未软删且 rsku_code 为空的 RSKU 补发业务编码。
     *
     * <p>用于 RSPU 补发 rspu_code（如 AI 异步识别补全风格/尺寸后发号）后的联动补偿：
     * 此前因 RSPU 无码而以"无码创建"方式入库的工厂报价，在此逐个补发并落库。
     * 单条补发失败仅记录告警、继续处理其余记录，不中断整体补偿。
     * 该方法在调用方事务内执行，保持与 {@link #assignCode} 一致的
     * noRollbackFor = BusinessException 语义（单条失败不回滚调用方事务）。</p>
     *
     * @param rspuId RSPU ID
     * @return 实际补发成功的 RSKU 数量
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public int backfillCodesByRspu(String rspuId) {
        List<RskuSupply> pending = rskuSupplyMapper.selectList(new QueryWrapper<RskuSupply>()
            .eq("rspu_id", rspuId)
            .isNull("rsku_code"));
        int backfilled = 0;
        for (RskuSupply rsku : pending) {
            try {
                assignCode(rsku.getRskuId(), rspuId, rsku.getFactoryCode(), rsku.getMaterialCode());
                backfilled++;
            } catch (BusinessException e) {
                // 单条失败不阻断：记录告警后继续补发其余记录
                log.warn("RSKU 业务编码补发失败，跳过该条，rskuId={}，rspuId={}，原因={}",
                    rsku.getRskuId(), rspuId, e.getMessage());
            }
        }
        return backfilled;
    }
}
