package com.rsdp.config;

import com.rsdp.entity.SysUser;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.service.UserRoleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import com.rsdp.util.IdGenerator;

/**
 * 默认管理员初始化。
 *
 * <p>当 {@code sys_user} 表为空时，自动创建默认管理员账号 {@code admin}。
 * 密码优先从 {@code RSDP_INITIAL_ADMIN_PASSWORD} 环境变量读取；未配置时生成
 * 16 位随机强密码，<b>绝不写入日志/控制台</b>（启动脚本会把控制台输出重定向到
 * logs/backend.log 长期留存），而是写入独立凭据文件 {@code data/initial-admin-credentials.txt}，
 * 并在日志中提示文件路径，首次登录修改密码后应立即删除该文件。
 * 生产环境必须在启动前通过环境变量显式配置。</p>
 */
@Slf4j
@Component
public class DefaultAdminInitializer implements CommandLineRunner {

    private static final String RANDOM_PASSWORD_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
    private static final int RANDOM_PASSWORD_LENGTH = 16;
    /** 初始凭据文件（相对工作目录，独立于日志文件，便于首次登录后删除） */
    private static final Path CREDENTIALS_FILE = Path.of("data", "initial-admin-credentials.txt");

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
                writeCredentialsFile(rawPassword);
                log.warn("未配置 RSDP_INITIAL_ADMIN_PASSWORD，已创建默认管理员账号 admin，随机初始密码已写入 {}，"
                    + "首次登录修改密码后请立即删除该文件", CREDENTIALS_FILE.toAbsolutePath());
            }
        } catch (BadSqlGrammarException e) {
            // sys_user 表尚未创建（开发期首次启动、迁移未执行）：本次跳过，下次启动会重试
            log.warn("默认管理员初始化跳过（sys_user 表可能尚未创建）: {}", e.getMessage());
        }
        // 其他异常（DB 连接、权限不足等）不吞掉：直接抛出使启动失败，
        // 避免系统在"无任何可用账号"的死锁状态下静默运行
    }

    /**
     * 将随机初始密码写入独立凭据文件（不经过日志框架，避免随日志长期留存）。
     *
     * @param rawPassword 明文初始密码
     */
    private void writeCredentialsFile(String rawPassword) {
        try {
            Files.createDirectories(CREDENTIALS_FILE.getParent());
            String content = "RSDP 初始管理员凭据（首次登录修改密码后请立即删除本文件）\n"
                + "username: admin\n"
                + "password: " + rawPassword + "\n";
            Files.writeString(CREDENTIALS_FILE, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 文件写失败时不能让密码只存在于内存：抛出让启动失败，提示改用环境变量
            throw new IllegalStateException(
                "初始管理员密码写入凭据文件失败，请改用 RSDP_INITIAL_ADMIN_PASSWORD 环境变量配置: " + e.getMessage(), e);
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
