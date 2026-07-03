package com.rsdp.config;

import com.rsdp.entity.SysUser;
import com.rsdp.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 默认管理员初始化。
 *
 * <p>当 {@code sys_user} 表为空时，自动创建默认管理员账号 {@code admin / admin123}。
 * 仅用于首次部署或开发环境，生产环境应在初始化后尽快修改默认密码。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultAdminInitializer implements CommandLineRunner {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        try {
            long count = sysUserMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
            if (count > 0) {
                return;
            }

            SysUser admin = new SysUser();
            admin.setUserId("USER-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode("admin123"));
            admin.setNickname("系统管理员");
            admin.setRole("ADMIN");
            admin.setStatus("active");
            admin.setCreatedAt(LocalDateTime.now());
            admin.setUpdatedAt(LocalDateTime.now());
            sysUserMapper.insert(admin);

            log.warn("已创建默认管理员账号：admin / admin123，请尽快修改默认密码");
        } catch (Exception e) {
            log.error("默认管理员初始化失败（可能是 sys_user 表尚未创建）: {}", e.getMessage());
        }
    }
}
