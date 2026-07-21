package com.rsdp.security;

import com.rsdp.service.SecurityUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${rsdp.security.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityUserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                // 公开接口
                .requestMatchers("/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/images/**").permitAll()
                // 订单邀请公开页（免登录，token 自校验）
                .requestMatchers("/api/v1/public/**").permitAll()

                // 收藏夹：用户自服务数据，按当前用户隔离
                .requestMatchers(HttpMethod.GET, "/api/v1/favorites").hasAuthority(Permissions.FAVORITE_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/favorites/check").hasAuthority(Permissions.FAVORITE_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/favorites").hasAuthority(Permissions.FAVORITE_WRITE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/favorites/*").hasAuthority(Permissions.FAVORITE_WRITE)

                // 用户管理（管理员）
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/users/**").hasAuthority(Permissions.USER_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/users").hasAuthority(Permissions.USER_CREATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/admin/users/*/reset-password").hasAuthority(Permissions.USER_RESET_PASSWORD)
                .requestMatchers(HttpMethod.PUT, "/api/v1/admin/users/**").hasAuthority(Permissions.USER_UPDATE)
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

                // 认证即可访问的读接口（非敏感）
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/auth/me/preferences").hasRole("FACTORY_ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/dicts/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/tasks/*").authenticated()

                // 文档导入（必须放在产品读通配规则之前）
                .requestMatchers(HttpMethod.POST, "/api/v1/products/document-import").hasAuthority(Permissions.PRODUCT_IMPORT)

                // 导入模板：按最小权限显式授权（必须放在对应通配规则之前）
                .requestMatchers(HttpMethod.GET, "/api/v1/products/import-template").hasAuthority(Permissions.PRODUCT_IMPORT)
                .requestMatchers(HttpMethod.GET, "/api/v1/rsku/import-template").hasAuthority(Permissions.RSKU_IMPORT)

                // RSKU 读接口：必须放在产品/工厂读通配规则之前
                .requestMatchers(HttpMethod.GET, "/api/v1/products/*/rsku").hasAuthority(Permissions.RSKU_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/products/*/rsku/*").hasAuthority(Permissions.RSKU_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/factories/*/rsku").hasAuthority(Permissions.RSKU_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/rsku/*/price-history").hasAuthority(Permissions.RSKU_READ)

                // 产品读接口
                .requestMatchers(HttpMethod.GET, "/api/v1/products/**").hasAuthority(Permissions.PRODUCT_READ)

                // 工厂产品能力（必须放在工厂读/写通配规则之前）
                .requestMatchers(HttpMethod.GET, "/api/v1/factories/*/capabilities").hasAuthority(Permissions.CAPABILITY_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/factories/*/capabilities/*").hasAuthority(Permissions.CAPABILITY_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/factories/*/capabilities").hasAuthority(Permissions.CAPABILITY_CREATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/factories/*/capabilities/sync").hasAuthority(Permissions.CAPABILITY_CREATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/factories/*/capabilities/**").hasAuthority(Permissions.CAPABILITY_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/factories/*/capabilities/**").hasAuthority(Permissions.CAPABILITY_DELETE)

                // 工厂读接口
                .requestMatchers(HttpMethod.GET, "/api/v1/factories/**").hasAuthority(Permissions.FACTORY_READ)

                // 方案读接口
                .requestMatchers(HttpMethod.GET, "/api/v1/schemes/**").hasAuthority(Permissions.SCHEME_READ)

                // 设计项目接口
                .requestMatchers(HttpMethod.GET, "/api/v1/projects", "/api/v1/projects/**").hasAuthority(Permissions.PROJECT_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/projects").hasAuthority(Permissions.PROJECT_CREATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/projects/**").hasAuthority(Permissions.PROJECT_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/projects/**").hasAuthority(Permissions.PROJECT_DELETE)

                // 设计订单接口
                .requestMatchers(HttpMethod.GET, "/api/v1/orders", "/api/v1/orders/**").hasAuthority(Permissions.ORDER_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasAuthority(Permissions.ORDER_CREATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/invite").hasAuthority(Permissions.ORDER_UPDATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/orders/**").hasAuthority(Permissions.ORDER_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/orders/**").hasAuthority(Permissions.ORDER_DELETE)

                // 检索/推荐接口
                .requestMatchers(HttpMethod.POST, "/api/v1/matching/**").hasAuthority(Permissions.PRODUCT_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/retrieval/**").hasAuthority(Permissions.PRODUCT_READ)

                // 产品录入与批量导入
                .requestMatchers(HttpMethod.POST, "/api/v1/products/entry").hasAuthority(Permissions.PRODUCT_CREATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/products/factory-entry").hasAuthority(Permissions.PRODUCT_CREATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/products/import").hasAuthority(Permissions.PRODUCT_IMPORT)

                // RSKU 写接口：必须放在产品写通配规则之前
                .requestMatchers(HttpMethod.POST, "/api/v1/products/*/rsku/batch").hasAuthority(Permissions.RSKU_CREATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/products/*/rsku").hasAuthority(Permissions.RSKU_CREATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/products/*/rsku/**").hasAuthority(Permissions.RSKU_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/sku/**").hasAuthority(Permissions.RSKU_DELETE)
                .requestMatchers("/api/v1/rsku/import").hasAuthority(Permissions.RSKU_IMPORT)

                // 产品关系、变体与工厂关联
                .requestMatchers(HttpMethod.POST, "/api/v1/products/*/relations").hasAuthority(Permissions.PRODUCT_UPDATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/products/*/relations/*").hasAuthority(Permissions.PRODUCT_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/products/*/relations/*").hasAuthority(Permissions.PRODUCT_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/products/*/factories").hasAuthority(Permissions.PRODUCT_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/products/*/factories/*").hasAuthority(Permissions.PRODUCT_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/products/*/variants").hasAuthority(Permissions.PRODUCT_UPDATE)

                // 产品复核
                .requestMatchers(HttpMethod.PUT, "/api/v1/products/*/review").hasAuthority(Permissions.PRODUCT_REVIEW)

                // 产品更新/删除
                .requestMatchers(HttpMethod.PUT, "/api/v1/products/**").hasAuthority(Permissions.PRODUCT_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/products/**").hasAuthority(Permissions.PRODUCT_DELETE)

                // 工厂写接口
                .requestMatchers(HttpMethod.POST, "/api/v1/factories").hasAuthority(Permissions.FACTORY_CREATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/factories/**").hasAuthority(Permissions.FACTORY_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/factories/**").hasAuthority(Permissions.FACTORY_DELETE)
                .requestMatchers(HttpMethod.POST, "/api/v1/factories/*/lead-time-rules").hasAuthority(Permissions.FACTORY_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/factories/*/lead-time-rules/*").hasAuthority(Permissions.FACTORY_UPDATE)

                // 报价单
                .requestMatchers(HttpMethod.POST, "/api/v1/quotes/generate").hasAuthority(Permissions.QUOTE_GENERATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/quotes/export").hasAuthority(Permissions.QUOTE_EXPORT)
                .requestMatchers(HttpMethod.POST, "/api/v1/schemes/*/quote").hasAuthority(Permissions.QUOTE_GENERATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/schemes/*/copy-from-template").hasAuthority(Permissions.SCHEME_CREATE)

                // 运营统计接口（复用方案读权限）
                .requestMatchers(HttpMethod.GET, "/api/v1/statistics/**").hasAuthority(Permissions.SCHEME_READ)

                // 系统配置：读取需订单读权限，修改仅 ADMIN
                .requestMatchers(HttpMethod.GET, "/api/v1/configs/**").hasAuthority(Permissions.ORDER_READ)
                .requestMatchers(HttpMethod.PUT, "/api/v1/configs/**").hasRole("ADMIN")
                // 搭配方案写接口
                .requestMatchers(HttpMethod.POST, "/api/v1/schemes").hasAuthority(Permissions.SCHEME_CREATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/schemes/**").hasAuthority(Permissions.SCHEME_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/schemes/**").hasAuthority(Permissions.SCHEME_DELETE)

                // 产品集
                .requestMatchers(HttpMethod.GET, "/api/v1/collections").hasAuthority(Permissions.COLLECTION_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/collections/*").hasAuthority(Permissions.COLLECTION_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/collections").hasAuthority(Permissions.COLLECTION_CREATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/collections/**").hasAuthority(Permissions.COLLECTION_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/collections/**").hasAuthority(Permissions.COLLECTION_DELETE)

                // 设计师画像（/me 为当前用户自服务，不依赖 designer:profile:update 权限）
                .requestMatchers(HttpMethod.GET, "/api/v1/designer-profiles/me").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/designer-profiles/me").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/designer-profiles/me").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/designer-profiles").hasAuthority(Permissions.DESIGNER_PROFILE_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/designer-profiles/*").hasAuthority(Permissions.DESIGNER_PROFILE_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/designer-profiles").hasAuthority(Permissions.DESIGNER_PROFILE_UPDATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/designer-profiles/**").hasAuthority(Permissions.DESIGNER_PROFILE_UPDATE)

                // 推荐打分配置
                .requestMatchers(HttpMethod.GET, "/api/v1/recommendation-score-configs").hasAuthority(Permissions.RECOMMENDATION_SCORE_CONFIG_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/recommendation-score-configs/*").hasAuthority(Permissions.RECOMMENDATION_SCORE_CONFIG_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/recommendation-score-configs").hasAuthority(Permissions.RECOMMENDATION_SCORE_CONFIG_UPDATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/recommendation-score-configs/**").hasAuthority(Permissions.RECOMMENDATION_SCORE_CONFIG_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/recommendation-score-configs/**").hasAuthority(Permissions.RECOMMENDATION_SCORE_CONFIG_UPDATE)

                // AI 推荐候选
                .requestMatchers(HttpMethod.GET, "/api/v1/scheme-candidates").hasAuthority(Permissions.SCHEME_CANDIDATE_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/scheme-candidates/*").hasAuthority(Permissions.SCHEME_CANDIDATE_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/scheme-candidates").hasAuthority(Permissions.SCHEME_CANDIDATE_CREATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/scheme-candidates/batch").hasAuthority(Permissions.SCHEME_CANDIDATE_CREATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/scheme-candidates/**").hasAuthority(Permissions.SCHEME_CANDIDATE_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/scheme-candidates/**").hasAuthority(Permissions.SCHEME_CANDIDATE_DELETE)

                // 字典写接口
                .requestMatchers(HttpMethod.POST, "/api/v1/dicts").hasAuthority(Permissions.DICT_CREATE)

                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return new ProviderManager(provider);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        configuration.setAllowedOrigins(origins.isEmpty() ? List.of("http://localhost:5173") : origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
