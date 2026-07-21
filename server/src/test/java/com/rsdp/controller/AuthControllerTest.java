package com.rsdp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.LoginRequest;
import com.rsdp.dto.response.LoginResponse;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.AuthService;
import com.rsdp.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuthController} 单元测试。
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private UserService userService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void login_shouldReturnUserInfoAndSetHttpOnlyCookie() throws Exception {
        LoginResponse response = new LoginResponse(
            "token-xxx", "Bearer", "USER-001", "admin", "管理员", "ADMIN",
            List.of("ADMIN"), List.of("admin:user:manage"), false, List.of(),
            "ABCD2345", false, null
        );
        when(authService.login(any(), anyString())).thenReturn(response);

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.token").doesNotExist())
            .andExpect(jsonPath("$.data.role").value("ADMIN"))
            .andExpect(jsonPath("$.data.roles[0]").value("ADMIN"))
            .andExpect(jsonPath("$.data.permissions[0]").value("admin:user:manage"))
            .andExpect(header().stringValues("Set-Cookie",
                org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("rsdp_token=token-xxx"))))
            .andExpect(header().stringValues("Set-Cookie",
                org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("HttpOnly"))));
    }

    @Test
    void login_invalidCredentials_shouldReturnBusinessError() throws Exception {
        when(authService.login(any(), anyString())).thenThrow(new BusinessException("用户名或密码错误"));

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrong");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void login_blankPassword_shouldReturnValidationError() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }
}
