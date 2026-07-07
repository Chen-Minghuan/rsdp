package com.rsdp.service;

import com.rsdp.entity.SysUser;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring Security 用户详情服务。
 */
@Service
@RequiredArgsConstructor
public class SecurityUserDetailsService implements UserDetailsService {

    private final SysUserMapper sysUserMapper;
    private final PermissionService permissionService;
    private final UserRoleService userRoleService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = sysUserMapper.selectByUsername(username);
        if (user == null || !"active".equals(user.getStatus())) {
            throw new UsernameNotFoundException("用户不存在或已禁用: " + username);
        }

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();

        // 保留角色前缀，兼容现有基于角色的配置
        userRoleService.getRoleCodesByUserId(user.getUserId()).forEach(roleCode ->
            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleCode))
        );

        authorities.addAll(permissionService.getPermissionsByUserId(user.getUserId()).stream()
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toList()));

        return new SecurityUser(
            user.getUserId(),
            user.getUsername(),
            user.getPasswordHash(),
            authorities
        );
    }
}
