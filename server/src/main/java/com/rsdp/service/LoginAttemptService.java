package com.rsdp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录防爆破保护服务。
 *
 * <p>基于内存记录 IP 与用户名失败次数，超过阈值后短期锁定。当前为单机版实现，
 * 后续如需集群共享可替换为 Redis 存储。</p>
 */
@Slf4j
@Service
public class LoginAttemptService {

    @Value("${rsdp.security.login.max-attempts:5}")
    private int maxAttempts;

    @Value("${rsdp.security.login.lock-duration-minutes:15}")
    private int lockDurationMinutes;

    @Value("${rsdp.security.login.attempt-window-minutes:30}")
    private int attemptWindowMinutes;

    private final Map<String, AttemptRecord> ipAttempts = new ConcurrentHashMap<>();
    private final Map<String, AttemptRecord> userAttempts = new ConcurrentHashMap<>();

    /**
     * 检查指定 IP 和用户名是否被锁定。
     *
     * @param ip       客户端 IP
     * @param username 用户名
     * @return true 表示已被锁定
     */
    public boolean isBlocked(String ip, String username) {
        cleanupIfExpired(ipAttempts, ipKey(ip));
        cleanupIfExpired(userAttempts, userKey(username));
        return isRecordBlocked(ipAttempts.get(ipKey(ip)))
            || isRecordBlocked(userAttempts.get(userKey(username)));
    }

    /**
     * 记录一次登录失败。
     *
     * @param ip       客户端 IP
     * @param username 用户名
     */
    public void recordFailure(String ip, String username) {
        increment(ipAttempts, ipKey(ip));
        increment(userAttempts, userKey(username));
        log.warn("登录失败计数增加: ip={}, username={}", ip, username);
    }

    /**
     * 登录成功后清理失败记录。
     *
     * @param ip       客户端 IP
     * @param username 用户名
     */
    public void recordSuccess(String ip, String username) {
        ipAttempts.remove(ipKey(ip));
        userAttempts.remove(userKey(username));
    }

    private void increment(Map<String, AttemptRecord> store, String key) {
        AttemptRecord record = store.computeIfAbsent(key, k -> new AttemptRecord());
        record.increment(attemptWindowMinutes);
        if (record.getCount() >= maxAttempts) {
            record.lockFor(Duration.ofMinutes(lockDurationMinutes));
            log.warn("登录失败次数超限，已锁定: key={}, count={}", key, record.getCount());
        }
    }

    private void cleanupIfExpired(Map<String, AttemptRecord> store, String key) {
        AttemptRecord record = store.get(key);
        if (record != null && record.isExpired(attemptWindowMinutes)) {
            store.remove(key);
        }
    }

    private boolean isRecordBlocked(AttemptRecord record) {
        return record != null && record.isLocked();
    }

    private String ipKey(String ip) {
        return "ip:" + ip;
    }

    private String userKey(String username) {
        return "user:" + username;
    }

    /**
     * 登录尝试记录。
     */
    private static class AttemptRecord {
        private int count;
        private Instant lastAttempt;
        private Instant lockedUntil;

        void increment(int windowMinutes) {
            Instant now = Instant.now();
            if (lastAttempt == null || now.isAfter(lastAttempt.plus(Duration.ofMinutes(windowMinutes)))) {
                count = 0;
                lockedUntil = null;
            }
            count++;
            lastAttempt = now;
        }

        void lockFor(Duration duration) {
            lockedUntil = Instant.now().plus(duration);
        }

        boolean isLocked() {
            if (lockedUntil == null) {
                return false;
            }
            if (Instant.now().isAfter(lockedUntil)) {
                lockedUntil = null;
                return false;
            }
            return true;
        }

        boolean isExpired(int windowMinutes) {
            if (lastAttempt == null) {
                return true;
            }
            return Instant.now().isAfter(lastAttempt.plus(Duration.ofMinutes(windowMinutes)))
                && !isLocked();
        }

        int getCount() {
            return count;
        }
    }
}
