package com.rsdp.service;

import com.rsdp.entity.SysUser;
import com.rsdp.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@link SecurityUserDetailsService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class SecurityUserDetailsServiceTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @InjectMocks
    private SecurityUserDetailsService userDetailsService;

    @Test
    void loadUserByUsername_activeUser_shouldReturnUserDetails() {
        SysUser user = new SysUser();
        user.setUsername("admin");
        user.setPasswordHash("hashed");
        user.setRole("ADMIN");
        user.setStatus("active");

        when(sysUserMapper.selectByUsername("admin")).thenReturn(user);

        UserDetails details = userDetailsService.loadUserByUsername("admin");

        assertThat(details.getUsername()).isEqualTo("admin");
        assertThat(details.getPassword()).isEqualTo("hashed");
        assertThat(details.getAuthorities())
            .anyMatch(auth -> "ROLE_ADMIN".equals(auth.getAuthority()));
    }

    @Test
    void loadUserByUsername_inactiveUser_shouldThrow() {
        SysUser user = new SysUser();
        user.setUsername("admin");
        user.setStatus("inactive");

        when(sysUserMapper.selectByUsername("admin")).thenReturn(user);

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("admin"))
            .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void loadUserByUsername_notFound_shouldThrow() {
        when(sysUserMapper.selectByUsername("admin")).thenReturn(null);

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("admin"))
            .isInstanceOf(UsernameNotFoundException.class);
    }
}
