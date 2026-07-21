package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.dto.request.CompanyOwnerRequest;
import com.rsdp.dto.request.CompanyRequest;
import com.rsdp.dto.response.CompanyResponse;
import com.rsdp.entity.Company;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ForbiddenException;
import com.rsdp.mapper.CompanyMapper;
import com.rsdp.mapper.MemberGroupMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CompanyService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    @Mock
    private CompanyMapper companyMapper;

    @Mock
    private MemberGroupMapper memberGroupMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private CompanyService companyService;

    private SysUser userWithoutCompany() {
        SysUser user = new SysUser();
        user.setUserId("user-1");
        user.setUsername("designer");
        user.setNickname("设计师");
        return user;
    }

    private Company companyOf(String ownerId) {
        Company company = new Company();
        company.setCompanyId("COM-1");
        company.setCompanyName("示例设计工作室");
        company.setPriceRatio(new BigDecimal("0.9"));
        company.setOwnerId(ownerId);
        company.setStatus("active");
        return company;
    }

    private CompanyRequest createRequest() {
        CompanyRequest request = new CompanyRequest();
        request.setCompanyName("新企业");
        request.setPriceRatio(new BigDecimal("0.85"));
        return request;
    }

    @Test
    void getMyCompanyShouldReturnNullWhenNoCompany() {
        when(sysUserMapper.selectById("user-1")).thenReturn(userWithoutCompany());

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            assertThat(companyService.getMyCompany()).isNull();
        }
    }

    @Test
    void createMyCompanyShouldInsertAndBindOwner() {
        when(sysUserMapper.selectById("user-1")).thenReturn(userWithoutCompany());
        when(sysUserMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("designer");

            CompanyResponse response = companyService.createMyCompany(createRequest());

            assertThat(response.getCompanyName()).isEqualTo("新企业");
            assertThat(response.getPriceRatio()).isEqualByComparingTo("0.85");
            assertThat(response.getOwnerId()).isEqualTo("user-1");
            verify(companyMapper).insert(any(Company.class));
            verify(sysUserMapper).updateById(any(SysUser.class));
            verify(auditLogService).logCreate(eq("company"), anyString(), any(Company.class), eq("designer"));
        }
    }

    @Test
    void createMyCompanyShouldRejectWhenAlreadyInCompany() {
        SysUser user = userWithoutCompany();
        user.setCompanyId("COM-1");
        when(sysUserMapper.selectById("user-1")).thenReturn(user);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            assertThatThrownBy(() -> companyService.createMyCompany(createRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已归属企业");
            verify(companyMapper, never()).insert(any(Company.class));
        }
    }

    @Test
    void updateMyCompanyShouldRejectNonOwner() {
        SysUser user = userWithoutCompany();
        user.setCompanyId("COM-1");
        when(sysUserMapper.selectById("user-1")).thenReturn(user);
        when(companyMapper.selectById("COM-1")).thenReturn(companyOf("owner-2"));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            assertThatThrownBy(() -> companyService.updateMyCompany(createRequest()))
                .isInstanceOf(ForbiddenException.class);
            verify(companyMapper, never()).updateById(any(Company.class));
        }
    }

    @Test
    void updateMyCompanyShouldAllowOwnerAndSyncMemberText() {
        SysUser owner = userWithoutCompany();
        owner.setUserId("owner-1");
        owner.setCompanyId("COM-1");
        when(sysUserMapper.selectById("owner-1")).thenReturn(owner);
        when(companyMapper.selectById("COM-1")).thenReturn(companyOf("owner-1"));
        when(sysUserMapper.selectCount(any(QueryWrapper.class))).thenReturn(3L);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("owner-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("owner");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            CompanyResponse response = companyService.updateMyCompany(createRequest());

            assertThat(response.getCompanyName()).isEqualTo("新企业");
            assertThat(response.getPriceRatio()).isEqualByComparingTo("0.85");
            verify(companyMapper).updateById(any(Company.class));
            // 企业改名同步成员轻量文本字段
            verify(sysUserMapper).update(any(), any(UpdateWrapper.class));
            verify(auditLogService).logUpdate(eq("company"), eq("COM-1"), any(), any(Company.class), eq("owner"));
        }
    }

    @Test
    void transferOwnerShouldRejectNonMember() {
        SysUser owner = userWithoutCompany();
        owner.setUserId("owner-1");
        owner.setCompanyId("COM-1");
        when(sysUserMapper.selectById("owner-1")).thenReturn(owner);
        when(sysUserMapper.selectById("user-9")).thenReturn(userWithoutCompany());
        when(companyMapper.selectById("COM-1")).thenReturn(companyOf("owner-1"));

        CompanyOwnerRequest request = new CompanyOwnerRequest();
        request.setNewOwnerId("user-9");

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("owner-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            assertThatThrownBy(() -> companyService.transferOwner(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("本企业成员");
            verify(companyMapper, never()).updateById(any(Company.class));
        }
    }

    @Test
    void transferOwnerShouldSucceedForMember() {
        SysUser owner = userWithoutCompany();
        owner.setUserId("owner-1");
        owner.setCompanyId("COM-1");
        SysUser member = userWithoutCompany();
        member.setUserId("user-9");
        member.setCompanyId("COM-1");
        when(sysUserMapper.selectById("owner-1")).thenReturn(owner);
        when(sysUserMapper.selectById("user-9")).thenReturn(member);
        when(companyMapper.selectById("COM-1")).thenReturn(companyOf("owner-1"));
        when(sysUserMapper.selectCount(any(QueryWrapper.class))).thenReturn(2L);

        CompanyOwnerRequest request = new CompanyOwnerRequest();
        request.setNewOwnerId("user-9");

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("owner-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("owner");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            CompanyResponse response = companyService.transferOwner(request);

            assertThat(response.getOwnerId()).isEqualTo("user-9");
            verify(companyMapper).updateById(any(Company.class));
        }
    }

    @Test
    void deleteMyCompanyShouldClearMembers() {
        SysUser owner = userWithoutCompany();
        owner.setUserId("owner-1");
        owner.setCompanyId("COM-1");
        when(sysUserMapper.selectById("owner-1")).thenReturn(owner);
        when(companyMapper.selectById("COM-1")).thenReturn(companyOf("owner-1"));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("owner-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("owner");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            companyService.deleteMyCompany();

            verify(companyMapper).deleteById("COM-1");
            verify(memberGroupMapper).delete(any(QueryWrapper.class));
            verify(sysUserMapper).update(any(), any(UpdateWrapper.class));
            verify(auditLogService).logDelete(eq("company"), eq("COM-1"), any(), eq("owner"));
        }
    }

    @Test
    void resolveOrderPriceRateShouldReturnCompanyRatio() {
        SysUser user = userWithoutCompany();
        user.setCompanyId("COM-1");
        when(sysUserMapper.selectById("user-1")).thenReturn(user);
        when(companyMapper.selectById("COM-1")).thenReturn(companyOf("owner-1"));

        assertThat(companyService.resolveOrderPriceRate("user-1")).isEqualByComparingTo("0.9");
    }

    @Test
    void resolveOrderPriceRateShouldReturnNullWhenNoCompany() {
        lenient().when(sysUserMapper.selectById("user-1")).thenReturn(userWithoutCompany());

        assertThat(companyService.resolveOrderPriceRate("user-1")).isNull();
        assertThat(companyService.resolveOrderPriceRate(null)).isNull();
    }
}
