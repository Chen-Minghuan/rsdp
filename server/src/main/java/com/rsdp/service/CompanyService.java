package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.dto.request.CompanyOwnerRequest;
import com.rsdp.dto.request.CompanyRequest;
import com.rsdp.dto.response.CompanyResponse;
import com.rsdp.entity.Company;
import com.rsdp.entity.MemberGroup;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ForbiddenException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.CompanyMapper;
import com.rsdp.mapper.MemberGroupMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 企业服务：企业 CRUD、管理员变更、企业级折扣率解析。
 *
 * <p>企业不是角色：归属由 {@code sys_user.company_id} 表达；
 * 企业写操作仅企业管理员（owner）或平台 ADMIN 可执行。</p>
 */
@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyMapper companyMapper;
    private final MemberGroupMapper memberGroupMapper;
    private final SysUserMapper sysUserMapper;
    private final AuditLogService auditLogService;

    /**
     * 查询当前用户的企业；无企业时返回 {@code null}。
     *
     * @return 企业信息，未归属企业时为 null
     */
    public CompanyResponse getMyCompany() {
        Company company = getCompanyOfUser(SecurityOperatorContext.currentUserId());
        return company != null ? toResponse(company) : null;
    }

    /**
     * 查询指定用户归属的企业实体。
     *
     * @param userId 用户 ID
     * @return 企业实体，用户不存在或未归属企业时为 null
     */
    public Company getCompanyOfUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || !StringUtils.hasText(user.getCompanyId())) {
            return null;
        }
        return companyMapper.selectById(user.getCompanyId());
    }

    /**
     * 解析用户的订单折扣率：归属企业时返回企业 price_ratio，否则返回 {@code null}
     * （调用方回退全局 order.price_rate）。
     *
     * @param userId 用户 ID
     * @return 企业折扣率；无企业归属时为 null
     */
    public BigDecimal resolveOrderPriceRate(String userId) {
        Company company = getCompanyOfUser(userId);
        return company != null && company.getPriceRatio() != null ? company.getPriceRatio() : null;
    }

    /**
     * 创建企业：当前用户成为企业管理员。已归属企业的用户不能再创建。
     *
     * @param request 创建请求
     * @return 创建后的企业
     */
    @Transactional
    public CompanyResponse createMyCompany(CompanyRequest request) {
        String userId = currentUserIdRequired();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("当前用户不存在");
        }
        if (StringUtils.hasText(user.getCompanyId())) {
            throw new BusinessException("您已归属企业，不能重复创建");
        }

        Company company = new Company();
        company.setCompanyId(IdGenerator.generate("COM"));
        company.setCompanyName(request.getCompanyName().trim());
        company.setLogoImageId(StringUtils.hasText(request.getLogoImageId()) ? request.getLogoImageId() : null);
        company.setPriceRatio(request.getPriceRatio() != null ? request.getPriceRatio() : BigDecimal.ONE);
        company.setOwnerId(userId);
        company.setStatus("active");
        company.setCreatedAt(LocalDateTime.now());
        company.setUpdatedAt(LocalDateTime.now());
        companyMapper.insert(company);

        // 归属当前用户（同步轻量文本字段，保持 project.company_name 回退等旧逻辑可用）
        user.setCompanyId(company.getCompanyId());
        user.setCompanyName(company.getCompanyName());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);

        auditLogService.logCreate("company", company.getCompanyId(), company, currentUsername());
        return toResponse(company);
    }

    /**
     * 更新当前用户的企业（名称/Logo/折扣率）。仅企业管理员或平台 ADMIN。
     *
     * @param request 更新请求（仅更新非空字段）
     * @return 更新后的企业
     */
    @Transactional
    public CompanyResponse updateMyCompany(CompanyRequest request) {
        Company company = getMyCompanyRequired();
        assertCompanyAdmin(company);
        Company oldSnapshot = snapshot(company);

        if (StringUtils.hasText(request.getCompanyName())) {
            company.setCompanyName(request.getCompanyName().trim());
        }
        if (request.getLogoImageId() != null) {
            company.setLogoImageId(StringUtils.hasText(request.getLogoImageId()) ? request.getLogoImageId() : null);
        }
        if (request.getPriceRatio() != null) {
            company.setPriceRatio(request.getPriceRatio());
        }
        company.setUpdatedAt(LocalDateTime.now());
        companyMapper.updateById(company);

        // 企业改名同步成员轻量文本字段
        if (StringUtils.hasText(request.getCompanyName())) {
            sysUserMapper.update(null, new UpdateWrapper<SysUser>()
                .eq("company_id", company.getCompanyId())
                .set("company_name", company.getCompanyName())
                .set("updated_at", LocalDateTime.now()));
        }

        auditLogService.logUpdate("company", company.getCompanyId(), oldSnapshot, company, currentUsername());
        return toResponse(company);
    }

    /**
     * 变更企业管理员。仅当前管理员或平台 ADMIN；新管理员必须是本企业成员。
     *
     * @param request 变更请求
     * @return 更新后的企业
     */
    @Transactional
    public CompanyResponse transferOwner(CompanyOwnerRequest request) {
        Company company = getMyCompanyRequired();
        assertCompanyAdmin(company);
        Company oldSnapshot = snapshot(company);

        String newOwnerId = request.getNewOwnerId();
        if (newOwnerId.equals(company.getOwnerId())) {
            throw new BusinessException("新管理员与当前管理员相同");
        }
        SysUser newOwner = sysUserMapper.selectById(newOwnerId);
        if (newOwner == null || !company.getCompanyId().equals(newOwner.getCompanyId())) {
            throw new BusinessException("新管理员必须是本企业成员");
        }

        company.setOwnerId(newOwnerId);
        company.setUpdatedAt(LocalDateTime.now());
        companyMapper.updateById(company);
        auditLogService.logUpdate("company", company.getCompanyId(), oldSnapshot, company, currentUsername());
        return toResponse(company);
    }

    /**
     * 软删除当前用户的企业：成员企业/分组归属清空，分组一并软删。仅企业管理员或平台 ADMIN。
     */
    @Transactional
    public void deleteMyCompany() {
        Company company = getMyCompanyRequired();
        assertCompanyAdmin(company);
        Company oldSnapshot = snapshot(company);

        companyMapper.deleteById(company.getCompanyId());
        memberGroupMapper.delete(new QueryWrapper<MemberGroup>()
            .eq("company_id", company.getCompanyId()));
        sysUserMapper.update(null, new UpdateWrapper<SysUser>()
            .eq("company_id", company.getCompanyId())
            .set("company_id", null)
            .set("group_id", null)
            .set("updated_at", LocalDateTime.now()));
        auditLogService.logDelete("company", company.getCompanyId(), oldSnapshot, currentUsername());
    }

    /**
     * 查询当前用户的企业，不存在时抛业务异常。
     *
     * @return 企业实体
     */
    public Company getMyCompanyRequired() {
        Company company = getCompanyOfUser(SecurityOperatorContext.currentUserId());
        if (company == null) {
            throw new BusinessException("您尚未归属任何企业");
        }
        return company;
    }

    /**
     * 校验当前用户是否可管理该企业（企业管理员或平台 ADMIN）。
     *
     * @param company 企业实体
     */
    public void assertCompanyAdmin(Company company) {
        if (!SecurityOperatorContext.isCurrentUserAdmin()
            && !company.getOwnerId().equals(SecurityOperatorContext.currentUserId())) {
            throw new ForbiddenException("仅企业管理员可执行该操作");
        }
    }

    private String currentUserIdRequired() {
        String userId = SecurityOperatorContext.currentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException("无法获取当前用户 ID");
        }
        return userId;
    }

    private String currentUsername() {
        String username = SecurityOperatorContext.currentUsername();
        return StringUtils.hasText(username) ? username : "unknown";
    }

    private Company snapshot(Company source) {
        Company copy = new Company();
        copy.setCompanyId(source.getCompanyId());
        copy.setCompanyName(source.getCompanyName());
        copy.setLogoImageId(source.getLogoImageId());
        copy.setPriceRatio(source.getPriceRatio());
        copy.setOwnerId(source.getOwnerId());
        copy.setStatus(source.getStatus());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private CompanyResponse toResponse(Company company) {
        CompanyResponse response = new CompanyResponse();
        response.setCompanyId(company.getCompanyId());
        response.setCompanyName(company.getCompanyName());
        response.setLogoImageId(company.getLogoImageId());
        response.setPriceRatio(company.getPriceRatio());
        response.setOwnerId(company.getOwnerId());
        response.setStatus(company.getStatus());
        response.setCreatedAt(company.getCreatedAt());
        response.setUpdatedAt(company.getUpdatedAt());
        SysUser owner = sysUserMapper.selectById(company.getOwnerId());
        response.setOwnerNickname(owner != null ? owner.getNickname() : null);
        Long memberCount = sysUserMapper.selectCount(new QueryWrapper<SysUser>()
            .eq("company_id", company.getCompanyId()));
        response.setMemberCount(memberCount != null ? memberCount.intValue() : 0);
        return response;
    }
}
