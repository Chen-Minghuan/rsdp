package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.InviteRecordResponse;
import com.rsdp.entity.InviteRecord;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.InviteRecordMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InviteService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class InviteServiceTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private InviteRecordMapper inviteRecordMapper;

    @InjectMocks
    private InviteService inviteService;

    @Test
    void generateUniqueInviteCodeShouldReturn8CharCode() {
        when(sysUserMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        String code = inviteService.generateUniqueInviteCode();

        assertThat(code).hasSize(8).matches("[A-HJ-NP-Z2-9]{8}");
    }

    @Test
    void generateUniqueInviteCodeShouldRetryOnConflict() {
        // 第一次冲突，第二次唯一
        when(sysUserMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L, 0L);

        String code = inviteService.generateUniqueInviteCode();

        assertThat(code).hasSize(8);
        verify(sysUserMapper, org.mockito.Mockito.times(2)).selectCount(any(QueryWrapper.class));
    }

    @Test
    void bindInviterShouldSetInvitedByAndInsertRecord() {
        SysUser inviter = new SysUser();
        inviter.setUserId("USER-INVITER");
        inviter.setInviteCode("ABCD2345");
        when(sysUserMapper.selectOne(any(QueryWrapper.class))).thenReturn(inviter);

        SysUser invitee = new SysUser();
        invitee.setUserId("USER-NEW");
        inviteService.bindInviter(invitee, "ABCD2345");

        assertThat(invitee.getInvitedBy()).isEqualTo("USER-INVITER");
        ArgumentCaptor<InviteRecord> captor = ArgumentCaptor.forClass(InviteRecord.class);
        verify(inviteRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getInviterId()).isEqualTo("USER-INVITER");
        assertThat(captor.getValue().getInviteeId()).isEqualTo("USER-NEW");
        assertThat(captor.getValue().getInviteCode()).isEqualTo("ABCD2345");
    }

    @Test
    void bindInviterShouldRejectInvalidCode() {
        when(sysUserMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        SysUser invitee = new SysUser();
        invitee.setUserId("USER-NEW");
        assertThatThrownBy(() -> inviteService.bindInviter(invitee, "BADCODE1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("邀请码无效");
    }

    @Test
    void bindInviterShouldRejectSelfInvite() {
        SysUser inviter = new SysUser();
        inviter.setUserId("USER-SAME");
        when(sysUserMapper.selectOne(any(QueryWrapper.class))).thenReturn(inviter);

        SysUser invitee = new SysUser();
        invitee.setUserId("USER-SAME");
        assertThatThrownBy(() -> inviteService.bindInviter(invitee, "ABCD2345"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("自己的邀请码");
    }

    @Test
    void listMyInvitesShouldMapInviteeInfo() {
        InviteRecord record = new InviteRecord();
        record.setId(1L);
        record.setInviterId("USER-INVITER");
        record.setInviteeId("USER-NEW");
        record.setInviteCode("ABCD2345");
        record.setCreatedAt(LocalDateTime.now());
        when(inviteRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(record));

        SysUser invitee = new SysUser();
        invitee.setUserId("USER-NEW");
        invitee.setUsername("newbie");
        invitee.setNickname("新人");
        when(sysUserMapper.selectBatchIds(anyList())).thenReturn(List.of(invitee));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("USER-INVITER");

            List<InviteRecordResponse> invites = inviteService.listMyInvites();

            assertThat(invites).hasSize(1);
            assertThat(invites.get(0).getInviteeUsername()).isEqualTo("newbie");
            assertThat(invites.get(0).getInviteeNickname()).isEqualTo("新人");
        }
    }

    @Test
    void listMyInvitesShouldReturnEmptyWhenNoRecords() {
        when(inviteRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("USER-INVITER");

            assertThat(inviteService.listMyInvites()).isEmpty();
        }
    }
}
