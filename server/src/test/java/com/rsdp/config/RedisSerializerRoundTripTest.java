package com.rsdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 守护 Redis 缓存序列化契约的回归测试。
 *
 * <p>生产环境 {@code GenericJackson2JsonRedisSerializer} 按 NON_FINAL 策略写入 {@code @class} 类型信息，
 * 因此 {@code @Cacheable} 方法返回的值必须是非 final 的具体类型（ArrayList/HashSet/实体类）。
 * 禁止返回 {@code List.of()} / {@code Collections.emptySet()} 等 JDK final 实现，
 * 否则缓存反序列化后类型错乱，在调用方抛 ClassCastException（且绕过 CacheErrorHandler）。</p>
 */
class RedisSerializerRoundTripTest {

    /** 模拟实体类（非 final、含无参构造）。 */
    static class DemoBean {
        public String name;
        public DemoBean() {}
        DemoBean(String name) { this.name = name; }
    }

    private final GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();

    @Test
    void entityRoundTripKeepsConcreteType() {
        DemoBean bean = new DemoBean("hello");
        Object restored = serializer.deserialize(serializer.serialize(bean));
        assertInstanceOf(DemoBean.class, restored);
        assertEquals("hello", ((DemoBean) restored).name);
    }

    /** DictService.listByType 的返回形状：ArrayList&lt;实体&gt;。 */
    @Test
    void arrayListOfEntitiesRoundTrip() {
        List<DemoBean> list = new ArrayList<>(List.of(new DemoBean("a"), new DemoBean("b")));
        Object restored = serializer.deserialize(serializer.serialize(list));
        assertInstanceOf(List.class, restored);
        assertInstanceOf(DemoBean.class, ((List<?>) restored).get(0));
    }

    /** PermissionService.getPermissionsByUserId 的返回形状：HashSet&lt;String&gt;（含空集合）。 */
    @Test
    void hashSetRoundTripIncludingEmpty() {
        Set<String> empty = new HashSet<>();
        Object restoredEmpty = serializer.deserialize(serializer.serialize(empty));
        assertInstanceOf(Set.class, restoredEmpty);

        Set<String> perms = new HashSet<>(Set.of("rsku:read", "rspu:write"));
        Object restored = serializer.deserialize(serializer.serialize(perms));
        assertInstanceOf(Set.class, restored);
        assertEquals(perms, restored);
    }
}
