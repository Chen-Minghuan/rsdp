package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.SysUser;
import com.rsdp.entity.SysUserFactory;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.SysUserFactoryMapper;
import com.rsdp.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户工厂关联服务（用于厂商业务员数据权限）。
 */
@Service
@RequiredArgsConstructor
public class UserFactoryService {

    private final SysUserFactoryMapper sysUserFactoryMapper;
    private final SysUserMapper sysUserMapper;
    private final FactoryMasterMapper factoryMasterMapper;

    /**
     * 查询用户关联的所有工厂编码。
     *
     * @param userId 用户 ID
     * @return 工厂编码列表
     */
    public List<String> getFactoryCodesByUserId(String userId) {
        return sysUserFactoryMapper.selectFactoryCodesByUserId(userId);
    }

    /**
     * 重置用户的工厂关联。
     *
     * @param userId       用户 ID
     * @param factoryCodes 工厂编码列表
     */
    @Transactional
    public void resetFactories(String userId, List<String> factoryCodes) {
        sysUserFactoryMapper.delete(
            new QueryWrapper<SysUserFactory>().eq("user_id", userId)
        );
        if (factoryCodes == null || factoryCodes.isEmpty()) {
            return;
        }
        for (String factoryCode : factoryCodes) {
            if (factoryCode == null || factoryCode.isBlank()) {
                continue;
            }
            String code = factoryCode.trim();
            FactoryMaster factory = factoryMasterMapper.selectById(code);
            if (factory == null) {
                throw new BusinessException("工厂不存在: " + code);
            }
            SysUserFactory userFactory = new SysUserFactory();
            userFactory.setUserId(userId);
            userFactory.setFactoryCode(code);
            sysUserFactoryMapper.insert(userFactory);
        }
    }

    /**
     * 按用户名查询关联的所有工厂编码。
     *
     * @param username 用户名
     * @return 工厂编码列表
     */
    public List<String> getFactoryCodesByUsername(String username) {
        SysUser user = sysUserMapper.selectByUsername(username);
        return user == null ? List.of() : getFactoryCodesByUserId(user.getUserId());
    }
}
