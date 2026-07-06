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

                // 用户管理（管理员）
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/users/**").hasAuthority(Permissions.USER_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/users").hasAuthority(Permissions.USER_CREATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/admin/users/**").hasAuthority(Permissions.USER_UPDATE)
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

                // 认证即可访问的读接口（非敏感）
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/dicts/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/tasks/*").authenticated()
                // 敏感读接口：按最小权限显式授权（必须放在对应通配规则之前）
                .requestMatchers(HttpMethod.GET, "/api/v1/products/import-template").hasAuthority(Permissions.PRODUCT_IMPORT)
                .requestMatchers(HttpMethod.GET, "/api/v1/rsku/import-template").hasAuthority(Permissions.RSKU_IMPORT)

                .requestMatchers(HttpMethod.GET, "/api/v1/products").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/products/*").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/products/*/relations").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/products/*/variants").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/factories").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/factories/*").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/factories/*/rsku").hasAuthority(Permissions.RSKU_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/products/*/rsku").hasAuthority(Permissions.RSKU_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/products/*/rsku/*").hasAuthority(Permissions.RSKU_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/rsku/*/price-history").hasAuthority(Permissions.RSKU_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/schemes").hasAuthority(Permissions.SCHEME_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/schemes/*").hasAuthority(Permissions.SCHEME_READ)

                // 产品写接口
                .requestMatchers(HttpMethod.POST, "/api/v1/products/entry").hasAuthority(Permissions.PRODUCT_CREATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/products/import").hasAuthority(Permissions.PRODUCT_IMPORT)
                .requestMatchers(HttpMethod.PUT, "/api/v1/products/**").hasAuthority(Permissions.PRODUCT_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/products/**").hasAuthority(Permissions.PRODUCT_DELETE)

                // 工厂写接口
                .requestMatchers(HttpMethod.POST, "/api/v1/factories").hasAuthority(Permissions.FACTORY_CREATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/factories/**").hasAuthority(Permissions.FACTORY_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/factories/**").hasAuthority(Permissions.FACTORY_DELETE)

                // RSKU 写接口
                .requestMatchers(HttpMethod.POST, "/api/v1/products/*/rsku").hasAuthority(Permissions.RSKU_CREATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/products/*/rsku/**").hasAuthority(Permissions.RSKU_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/sku/**").hasAuthority(Permissions.RSKU_DELETE)
                .requestMatchers("/api/v1/rsku/import").hasAuthority(Permissions.RSKU_IMPORT)

                // 报价单
                .requestMatchers(HttpMethod.POST, "/api/v1/quotes/**").hasAuthority(Permissions.QUOTE_GENERATE)

                // 搭配方案写接口
                .requestMatchers(HttpMethod.POST, "/api/v1/schemes").hasAuthority(Permissions.SCHEME_CREATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/schemes/**").hasAuthority(Permissions.SCHEME_UPDATE)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/schemes/**").hasAuthority(Permissions.SCHEME_DELETE)

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
