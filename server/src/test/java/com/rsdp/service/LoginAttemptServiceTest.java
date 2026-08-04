package com.rsdp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LoginAttemptService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    @InjectMocks
    private LoginAttemptService loginAttemptService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(loginAttemptService, "maxAttempts", 5);
        ReflectionTestUtils.setField(loginAttemptService, "lockDurationMinutes", 15);
        ReflectionTestUtils.setField(loginAttemptService, "attemptWindowMinutes", 30);
    }

    @Test
    void isBlocked_shouldReturnFalseInitially() {
        assertThat(loginAttemptService.isBlocked("192.168.1.1", "admin")).isFalse();
    }

    @Test
    void recordFailure_shouldBlockAfterMaxAttempts() {
        ReflectionTestUtils.setField(loginAttemptService, "maxAttempts", 3);

        loginAttemptService.recordFailure("192.168.1.2", "user1");
        assertThat(loginAttemptService.isBlocked("192.168.1.2", "user1")).isFalse();

        loginAttemptService.recordFailure("192.168.1.2", "user1");
        assertThat(loginAttemptService.isBlocked("192.168.1.2", "user1")).isFalse();

        loginAttemptService.recordFailure("192.168.1.2", "user1");
        assertThat(loginAttemptService.isBlocked("192.168.1.2", "user1")).isTrue();
    }

    @Test
    void recordSuccess_shouldResetFailureCount() {
        ReflectionTestUtils.setField(loginAttemptService, "maxAttempts", 2);

        loginAttemptService.recordFailure("192.168.1.3", "user2");
        loginAttemptService.recordSuccess("192.168.1.3", "user2");

        loginAttemptService.recordFailure("192.168.1.3", "user2");
        assertThat(loginAttemptService.isBlocked("192.168.1.3", "user2")).isFalse();
    }

    @Test
    void ipAndUser_shouldBeBlockedByCompositeKey() {
        ReflectionTestUtils.setField(loginAttemptService, "maxAttempts", 2);

        // 同一 IP 下 user3 失败超限，只锁定 user3，不影响同 IP 的 user4
        loginAttemptService.recordFailure("192.168.1.4", "user3");
        loginAttemptService.recordFailure("192.168.1.4", "user3");

        assertThat(loginAttemptService.isBlocked("192.168.1.4", "user3")).isTrue();
        assertThat(loginAttemptService.isBlocked("192.168.1.4", "user4")).isFalse();
        assertThat(loginAttemptService.isBlocked("192.168.1.5", "user3")).isFalse();

        // 同一用户在不同 IP 失败，各 IP 独立计数
        loginAttemptService.recordFailure("192.168.1.5", "user5");
        loginAttemptService.recordFailure("192.168.1.6", "user5");

        assertThat(loginAttemptService.isBlocked("192.168.1.5", "user5")).isFalse();
        assertThat(loginAttemptService.isBlocked("192.168.1.6", "user5")).isFalse();
    }
}
