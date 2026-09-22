package com.rsdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * CAD 户型解析服务（rsdp-cad-parser）HTTP 客户端配置（CAD 户型导入 P3）。
 *
 * <p>dev 默认 {@code http://localhost:8090}（本机 dotnet run）；
 * 容器部署配 {@code http://cad-parser:8090}（仅内网，不暴露端口）。</p>
 */
@Configuration
public class CadParserConfig {

    @Value("${rsdp.cad-parser.base-url:http://localhost:8090}")
    private String baseUrl;

    /** CAD 解析为同步快调用（通常 <5s），超时给足大图纸余量，默认 120s。 */
    @Value("${rsdp.cad-parser.timeout-seconds:120}")
    private int timeoutSeconds;

    /**
     * CAD 解析服务 RestClient：JDK HttpClient 连接池，无鉴权（内网服务）。
     *
     * @return cadParserRestClient Bean
     */
    @Bean
    public RestClient cadParserRestClient() {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory((ClientHttpRequestFactory) factory)
            .build();
    }
}
