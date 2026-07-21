package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.InviteRecordResponse;
import com.rsdp.entity.InviteRecord;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.InviteRecordMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 邀请裂变服务：永久邀请码生成、注册归因绑定、邀请记录查询。
 *
 * <p>邀请码仅用于注册归因（invited_by），不携带权限；
 * 与订单邀请的 HMAC token 机制（OrderInviteService）互不混用。</p>
 */
@Service
@RequiredArgsConstructor
public class InviteService {

    /** 邀请码字符集（去除易混淆字符 0/O/1/I/L）。 */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /** 邀请码长度。 */
    private static final int CODE_LENGTH = 8;

    /** 唯一冲突重试上限。 */
    private static final int MAX_GENERATE_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SysUserMapper sysUserMapper;
    private final InviteRecordMapper inviteRecordMapper;

    /**
     * 生成全局唯一的 8 位永久邀请码（冲突时重试）。
     *
     * @return 邀请码
     */
    public String generateUniqueInviteCode() {
        for (int attempt = 0; attempt < MAX_GENERATE_ATTEMPTS; attempt++) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            String candidate = code.toString();
            Long count = sysUserMapper.selectCount(new QueryWrapper<SysUser>().eq("invite_code", candidate));
            if (count == null || count == 0) {
                return candidate;
            }
        }
        throw new BusinessException("邀请码生成失败，请重试");
    }

    /**
     * 注册归因：按邀请码绑定邀请人并写入邀请记录。
     *
     * @param invitee    被邀请人（新注册用户，会被设置 invited_by，需调用方持久化）
     * @param inviteCode 注册时填写的邀请码
     */
    public void bindInviter(SysUser invitee, String inviteCode) {
        SysUser inviter = sysUserMapper.selectOne(
            new QueryWrapper<SysUser>().eq("invite_code", inviteCode.trim()));
        if (inviter == null) {
            throw new BusinessException("邀请码无效");
        }
        if (inviter.getUserId().equals(invitee.getUserId())) {
            throw new BusinessException("不能使用自己的邀请码");
        }
        invitee.setInvitedBy(inviter.getUserId());

        InviteRecord record = new InviteRecord();
        record.setInviterId(inviter.getUserId());
        record.setInviteeId(invitee.getUserId());
        record.setInviteCode(inviter.getInviteCode());
        record.setCreatedAt(LocalDateTime.now());
        inviteRecordMapper.insert(record);
    }

    /**
     * 查询当前用户的邀请记录（按时间倒序）。
     *
     * @return 邀请记录列表
     */
    public List<InviteRecordResponse> listMyInvites() {
        String userId = SecurityOperatorContext.currentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException("无法获取当前用户 ID");
        }
        List<InviteRecord> records = inviteRecordMapper.selectList(new QueryWrapper<InviteRecord>()
            .eq("inviter_id", userId)
            .orderByDesc("created_at"));
        if (records.isEmpty()) {
            return List.of();
        }
        List<String> inviteeIds = records.stream().map(InviteRecord::getInviteeId).distinct().toList();
        Map<String, SysUser> inviteeMap = sysUserMapper.selectBatchIds(inviteeIds).stream()
            .collect(Collectors.toMap(SysUser::getUserId, Function.identity(), (a, b) -> a));
        return records.stream().map(record -> {
            InviteRecordResponse response = new InviteRecordResponse();
            response.setId(record.getId());
            response.setInviteeId(record.getInviteeId());
            SysUser invitee = inviteeMap.get(record.getInviteeId());
            response.setInviteeUsername(invitee != null ? invitee.getUsername() : null);
            response.setInviteeNickname(invitee != null ? invitee.getNickname() : null);
            response.setCreatedAt(record.getCreatedAt());
            return response;
        }).toList();
    }
}
