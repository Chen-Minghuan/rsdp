package com.rsdp.config;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus TypeHandler 配置。
 *
 * <p>让 MyBatis-Plus 内置的 JacksonTypeHandler 使用 Spring 托管的 ObjectMapper，
 * 从而支持 Java 8 日期时间序列化等自定义配置。</p>
 */
@Configuration
@RequiredArgsConstructor
public class MybatisTypeHandlerConfig {

    private final ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        JacksonTypeHandler.setObjectMapper(objectMapper);
    }
}
