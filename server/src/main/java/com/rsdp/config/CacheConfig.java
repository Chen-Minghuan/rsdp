package com.rsdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Arrays;

/**
 * Spring Cache 配置。
 *
 * <p>生产环境使用 Redis 作为缓存后端；本地开发若 Redis 未启用，则回退到 JVM 内存缓存。</p>
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CACHE_NAME_DICTS = "dicts";
    public static final String CACHE_NAME_STYLE_FORMULA = "styleFormula";

    /**
     * 基于 Redis 的缓存管理器（生产环境默认启用）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring.data.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
            .entryTtl(Duration.ofMinutes(10))
            .disableCachingNullValues();
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaults)
            .build();
    }

    /**
     * 基于 JVM 内存的缓存管理器（本地开发 Redis 禁用时使用）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring.data.redis", name = "enabled", havingValue = "false")
    public CacheManager concurrentMapCacheManager() {
        log.info("Redis 缓存已禁用，回退到 ConcurrentMapCacheManager");
        return new ConcurrentMapCacheManager(CACHE_NAME_DICTS, CACHE_NAME_STYLE_FORMULA);
    }

    /**
     * 缓存键生成器：使用参数类名 + 方法名 + 参数值，避免不同方法键冲突。
     */
    @Bean("simpleKeyGenerator")
    public KeyGenerator simpleKeyGenerator() {
        return (target, method, params) -> target.getClass().getSimpleName() + "#" + method.getName() + "(" + Arrays.toString(params) + ")";
    }
}
