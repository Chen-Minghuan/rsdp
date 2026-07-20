package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.dto.request.UserCreateRequest;
import com.rsdp.dto.request.UserUpdateRequest;
import com.rsdp.dto.response.UserResponse;
import com.rsdp.entity.SysRole;
import com.rsdp.entity.SysUser;
import com.rsdp.entity.SysUserFactory;
import com.rsdp.entity.SysUserRole;
import com.rsdp.mapper.SysRoleMapper;
import com.rsdp.mapper.SysUserFactoryMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private SysRoleMapper sysRoleMapper;

    @Mock
    private SysUserRoleMapper sysUserRoleMapper;

    @Mock
    private SysUserFactoryMapper sysUserFactoryMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserRoleService userRoleService;

    @Mock
    private UserFactoryService userFactoryService;

    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private UserService userService;

    @Test
    void listUsers_shouldBatchMapRolesAndFactories() {
        SysUser admin = buildUser("USER-001", "admin", "管理员");
        SysUser factoryUser = buildUser("USER-002", "factory", "工厂用户");
        Page<SysUser> userPage = new Page<>(1, 10, 2);
        userPage.setRecords(List.of(admin, factoryUser));

        when(sysUserMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(userPage);

        SysRole adminRole = buildRole(1L, "ADMIN", "管理员");
        SysRole factoryRole = buildRole(2L, "FACTORY_ADMIN", "工厂管理员");
        when(sysUserRoleMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            buildUserRole("USER-001", 1L),
            buildUserRole("USER-002", 2L)
        ));
        when(sysRoleMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(adminRole, factoryRole));

        when(sysUserFactoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            buildUserFactory("USER-002", "F001"),
            buildUserFactory("USER-002", "F002")
        ));

        Page<UserResponse> result = userService.listUsers(1, 10, null);

        assertThat(result.getTotal()).isEqualTo(2);
        List<UserResponse> records = result.getRecords();
        assertThat(records).hasSize(2);

        UserResponse adminResponse = records.get(0);
        assertThat(adminResponse.getUserId()).isEqualTo("USER-001");
        assertThat(adminResponse.getRoleCode()).isEqualTo("ADMIN");
        assertThat(adminResponse.getRoleName()).isEqualTo("管理员");
        assertThat(adminResponse.getFactoryCodes()).isEmpty();

        UserResponse factoryResponse = records.get(1);
        assertThat(factoryResponse.getUserId()).isEqualTo("USER-002");
        assertThat(factoryResponse.getRoleCode()).isEqualTo("FACTORY_ADMIN");
        assertThat(factoryResponse.getRoleName()).isEqualTo("工厂管理员");
        assertThat(factoryResponse.getFactoryCodes()).containsExactly("F001", "F002");

        verify(sysUserRoleMapper).selectList(argThat(wrapper -> wrapper instanceof QueryWrapper));
        verify(sysUserFactoryMapper).selectList(argThat(wrapper -> wrapper instanceof QueryWrapper));
    }

    @Test
    void listUsers_whenNoRolesAndFactories_shouldReturnEmptyLists() {
        SysUser plainUser = buildUser("USER-003", "plain", "普通用户");
        Page<SysUser> userPage = new Page<>(1, 10, 1);
        userPage.setRecords(List.of(plainUser));

        when(sysUserMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(userPage);
        when(sysUserRoleMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        when(sysUserFactoryMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        Page<UserResponse> result = userService.listUsers(1, 10, null);

        assertThat(result.getRecords()).hasSize(1);
        UserResponse response = result.getRecords().get(0);
        assertThat(response.getRoleCode()).isNull();
        assertThat(response.getRoleName()).isNull();
        assertThat(response.getFactoryCodes()).isEmpty();
    }

    @Test
    void createUser_shouldPersistCompanyAndGroup() {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("newbie");
        request.setPassword("secret123");
        request.setRoleCode("DESIGNER");
        request.setCompanyName("示例设计工作室");
        request.setGroupName("方案一组");

        when(sysUserMapper.selectByUsername("newbie")).thenReturn(null);
        when(sysRoleMapper.selectByRoleCode("DESIGNER")).thenReturn(buildRole(3L, "DESIGNER", "设计师"));
        when(passwordEncoder.encode("secret123")).thenReturn("hash");
        when(userRoleService.getRoleCodesByUserId(anyString())).thenReturn(List.of("DESIGNER"));
        when(userFactoryService.getFactoryCodesByUserId(anyString())).thenReturn(List.of());

        UserResponse response = userService.createUser(request);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).insert(captor.capture());
        assertThat(captor.getValue().getCompanyName()).isEqualTo("示例设计工作室");
        assertThat(captor.getValue().getGroupName()).isEqualTo("方案一组");
        assertThat(response.getCompanyName()).isEqualTo("示例设计工作室");
        assertThat(response.getGroupName()).isEqualTo("方案一组");
    }

    @Test
    void updateUser_shouldUpdateCompanyAndGroupWhenProvided() {
        SysUser existing = buildUser("USER-009", "designer", "设计师");
        existing.setCompanyName("旧企业");
        existing.setGroupName("旧组");
        when(sysUserMapper.selectById("USER-009")).thenReturn(existing);
        when(sysRoleMapper.selectByRoleCode("DESIGNER")).thenReturn(buildRole(3L, "DESIGNER", "设计师"));
        when(userRoleService.getRoleCodesByUserId("USER-009")).thenReturn(List.of("DESIGNER"));
        when(userFactoryService.getFactoryCodesByUserId("USER-009")).thenReturn(List.of());

        UserUpdateRequest request = new UserUpdateRequest();
        request.setRoleCode("DESIGNER");
        request.setCompanyName("新企业");
        request.setGroupName("新组");

        UserResponse response = userService.updateUser("USER-009", request);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getValue().getCompanyName()).isEqualTo("新企业");
        assertThat(captor.getValue().getGroupName()).isEqualTo("新组");
        assertThat(response.getCompanyName()).isEqualTo("新企业");
        assertThat(response.getGroupName()).isEqualTo("新组");
    }

    @Test
    void updateUser_shouldKeepCompanyAndGroupWhenNotProvided() {
        SysUser existing = buildUser("USER-010", "designer", "设计师");
        existing.setCompanyName("旧企业");
        existing.setGroupName("旧组");
        when(sysUserMapper.selectById("USER-010")).thenReturn(existing);
        when(sysRoleMapper.selectByRoleCode("DESIGNER")).thenReturn(buildRole(3L, "DESIGNER", "设计师"));
        when(userRoleService.getRoleCodesByUserId("USER-010")).thenReturn(List.of("DESIGNER"));
        when(userFactoryService.getFactoryCodesByUserId("USER-010")).thenReturn(List.of());

        UserUpdateRequest request = new UserUpdateRequest();
        request.setRoleCode("DESIGNER");

        UserResponse response = userService.updateUser("USER-010", request);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getValue().getCompanyName()).isEqualTo("旧企业");
        assertThat(captor.getValue().getGroupName()).isEqualTo("旧组");
        assertThat(response.getCompanyName()).isEqualTo("旧企业");
        assertThat(response.getGroupName()).isEqualTo("旧组");
    }

    private SysUser buildUser(String userId, String username, String nickname) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setStatus("active");
        user.setViewFullCatalog(false);
        return user;
    }

    private SysRole buildRole(Long roleId, String roleCode, String roleName) {
        SysRole role = new SysRole();
        role.setRoleId(roleId);
        role.setRoleCode(roleCode);
        role.setRoleName(roleName);
        return role;
    }

    private SysUserRole buildUserRole(String userId, Long roleId) {
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        return userRole;
    }

    private SysUserFactory buildUserFactory(String userId, String factoryCode) {
        SysUserFactory userFactory = new SysUserFactory();
        userFactory.setUserId(userId);
        userFactory.setFactoryCode(factoryCode);
        return userFactory;
    }
}
