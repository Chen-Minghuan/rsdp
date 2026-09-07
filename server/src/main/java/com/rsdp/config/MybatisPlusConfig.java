package com.rsdp.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.rsdp.config.typehandler.PgLocalDateTimeTypeHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册分页插件。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * 全局覆盖 LocalDateTime TypeHandler，兼容 PostgreSQL TIMESTAMPTZ 列。
     *
     * <p>pgjdbc 拒绝将 timestamptz 按 {@code getObject(col, LocalDateTime.class)} 转换，
     * 必须用本项目的 {@link PgLocalDateTimeTypeHandler}（getTimestamp 读取）替换 MyBatis 内置实现，
     * 否则所有读取时间列的查询都会抛 PSQLException。</p>
     *
     * @return 注册自定义 TypeHandler 的配置定制器
     */
    @Bean
    public ConfigurationCustomizer pgLocalDateTimeTypeHandlerCustomizer() {
        return configuration -> configuration.getTypeHandlerRegistry()
            .register(LocalDateTime.class, new PgLocalDateTimeTypeHandler());
    }
}
