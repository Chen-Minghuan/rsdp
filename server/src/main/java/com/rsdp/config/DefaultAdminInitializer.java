package com.rsdp.config;

import com.rsdp.entity.SysUser;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.service.UserRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import com.rsdp.util.IdGenerator;

/**
 * 默认管理员初始化。
 *
 * <p>当 {@code sys_user} 表为空时，自动创建默认管理员账号 {@code admin}。
 * 密码优先从 {@code RSDP_INITIAL_ADMIN_PASSWORD} 环境变量读取；未配置时生成
 * 16 位随机强密码并打印到日志。生产环境必须在启动前通过环境变量显式配置。</p>
 */
@Slf4j
@Component
public class DefaultAdminInitializer implements CommandLineRunner {

    private static final String RANDOM_PASSWORD_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
    private static final int RANDOM_PASSWORD_LENGTH = 16;

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserRoleService userRoleService;
    private final String configuredPassword;

    public DefaultAdminInitializer(SysUserMapper sysUserMapper,
                                   PasswordEncoder passwordEncoder,
                                   UserRoleService userRoleService,
                                   @Value("${rsdp.security.initial-admin-password:}") String configuredPassword) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.userRoleService = userRoleService;
        this.configuredPassword = configuredPassword;
    }

    @Override
    public void run(String... args) {
        try {
            long count = sysUserMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
            if (count > 0) {
                return;
            }

            String rawPassword = resolveInitialPassword();
            SysUser admin = new SysUser();
            admin.setUserId(IdGenerator.userId());
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode(rawPassword));
            admin.setNickname("系统管理员");
            admin.setStatus("active");
            admin.setCreatedAt(LocalDateTime.now());
            admin.setUpdatedAt(LocalDateTime.now());
            sysUserMapper.insert(admin);

            userRoleService.assignRoleByCode(admin.getUserId(), "ADMIN");

            if (StringUtils.hasText(configuredPassword)) {
                log.info("已创建默认管理员账号：admin（密码来自 RSDP_INITIAL_ADMIN_PASSWORD 环境变量）");
            } else {
                log.warn("未配置 RSDP_INITIAL_ADMIN_PASSWORD，已创建默认管理员账号：admin / {}，请尽快修改默认密码", rawPassword);
            }
        } catch (Exception e) {
            log.error("默认管理员初始化失败（可能是 sys_user 表尚未创建）: {}", e.getMessage());
        }
    }

    /**
     * 解析初始管理员密码。优先使用显式配置，未配置时生成随机强密码。
     *
     * @return 明文初始密码
     */
    private String resolveInitialPassword() {
        if (StringUtils.hasText(configuredPassword)) {
            return configuredPassword;
        }
        return generateRandomPassword(RANDOM_PASSWORD_LENGTH);
    }

    private static String generateRandomPassword(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM_PASSWORD_CHARS.charAt(random.nextInt(RANDOM_PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
