package com.rsdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
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
 * <p>生产环境使用 Redis 作为缓存后端；本地开发若 Redis 未启用，则回退到 JVM 内存缓存。
 * 注册 {@link CacheErrorHandler}，Redis 运行期故障时降级为直接查库并告警，不影响业务请求。</p>
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    public static final String CACHE_NAME_DICTS = "dicts";
    public static final String CACHE_NAME_USER_PERMISSIONS = "userPermissions";
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
        return new ConcurrentMapCacheManager(CACHE_NAME_DICTS, CACHE_NAME_USER_PERMISSIONS, CACHE_NAME_STYLE_FORMULA);
    }

    /**
     * 缓存键生成器：使用参数类名 + 方法名 + 参数值，避免不同方法键冲突。
     */
    @Bean("simpleKeyGenerator")
    public KeyGenerator simpleKeyGenerator() {
        return (target, method, params) -> target.getClass().getSimpleName() + "#" + method.getName() + "(" + Arrays.toString(params) + ")";
    }

    /**
     * 缓存异常处理器：缓存后端（如 Redis）故障时仅记录 ERROR 日志并吞掉异常。
     *
     * <p>读取失败按缓存未命中处理，业务方法回源查库；写入/清除失败仅告警，
     * 等待 TTL 过期自然一致，避免缓存故障放大为业务故障。</p>
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new SimpleCacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.error("缓存读取失败，降级回源查询，cache={}, key={}: {}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.error("缓存写入失败，cache={}, key={}: {}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.error("缓存清除失败，等待 TTL 过期，cache={}, key={}: {}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.error("缓存清空失败，等待 TTL 过期，cache={}: {}", cache.getName(), exception.getMessage());
            }
        };
    }
}
