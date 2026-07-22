package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.TemplateTagRequest;
import com.rsdp.dto.response.TemplateTagResponse;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.TemplateTag;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.SchemeMapper;
import com.rsdp.mapper.TemplateTagMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模板标签服务：受控标签字典 CRUD 与设模板标签校验。
 *
 * <p>标签以名称为业务键（scheme.template_tags 存名称 JSON）；
 * 重命名时同步替换存量模板方案中的标签名。</p>
 */
@Service
@RequiredArgsConstructor
public class TemplateTagService {

    private final TemplateTagMapper templateTagMapper;
    private final SchemeMapper schemeMapper;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * 查询全部标签（管理端，含停用）。
     *
     * @return 标签列表
     */
    public List<TemplateTagResponse> listAll() {
        return templateTagMapper.selectList(new QueryWrapper<TemplateTag>()
                .orderByAsc("sort_order").orderByAsc("created_at"))
            .stream().map(this::toResponse).toList();
    }

    /**
     * 查询启用标签（模板库页/设模板选择器用，登录即可读）。
     *
     * @return 启用标签列表
     */
    public List<TemplateTagResponse> simpleList() {
        return templateTagMapper.selectList(new QueryWrapper<TemplateTag>()
                .eq("enabled", true)
                .orderByAsc("sort_order").orderByAsc("created_at"))
            .stream().map(this::toResponse).toList();
    }

    /**
     * 创建标签。标签名称全局唯一。
     *
     * @param request 创建请求
     * @return 创建后的标签
     */
    @Transactional
    public TemplateTagResponse create(TemplateTagRequest request) {
        String name = request.getTagName().trim();
        assertTagNameUnique(name, null);

        TemplateTag tag = new TemplateTag();
        tag.setTagId(IdGenerator.generate("TAG"));
        tag.setTagName(name);
        tag.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        tag.setEnabled(request.getEnabled() != null ? request.getEnabled() : Boolean.TRUE);
        tag.setCreatedAt(LocalDateTime.now());
        tag.setUpdatedAt(LocalDateTime.now());
        templateTagMapper.insert(tag);
        auditLogService.logCreate("template_tag", tag.getTagId(), tag, currentUsername());
        return toResponse(tag);
    }

    /**
     * 更新标签（重命名/排序/启停）。重命名时同步替换存量模板方案中的标签名。
     *
     * @param tagId   标签 ID
     * @param request 更新请求
     * @return 更新后的标签
     */
    @Transactional
    public TemplateTagResponse update(String tagId, TemplateTagRequest request) {
        TemplateTag tag = getTag(tagId);
        TemplateTag oldSnapshot = snapshot(tag);

        String newName = request.getTagName().trim();
        boolean renamed = !newName.equals(tag.getTagName());
        if (renamed) {
            assertTagNameUnique(newName, tagId);
        }
        String oldName = tag.getTagName();
        tag.setTagName(newName);
        if (request.getSortOrder() != null) {
            tag.setSortOrder(request.getSortOrder());
        }
        if (request.getEnabled() != null) {
            tag.setEnabled(request.getEnabled());
        }
        tag.setUpdatedAt(LocalDateTime.now());
        templateTagMapper.updateById(tag);
        if (renamed) {
            syncSchemeTemplateTags(oldName, newName);
        }
        auditLogService.logUpdate("template_tag", tagId, oldSnapshot, tag, currentUsername());
        return toResponse(tag);
    }

    /**
     * 删除标签（物理删除）。仍被模板方案使用时拒绝。
     *
     * @param tagId 标签 ID
     */
    @Transactional
    public void delete(String tagId) {
        TemplateTag tag = getTag(tagId);
        Long usage = schemeMapper.selectCount(new QueryWrapper<Scheme>()
            .eq("is_template", true)
            .like("template_tags", "\"" + tag.getTagName() + "\""));
        if (usage != null && usage > 0) {
            throw new BusinessException("标签仍被 " + usage + " 个模板使用，无法删除");
        }
        TemplateTag oldSnapshot = snapshot(tag);
        templateTagMapper.deleteById(tagId);
        auditLogService.logDelete("template_tag", tagId, oldSnapshot, currentUsername());
    }

    /**
     * 校验标签名称集合全部存在于标签字典（设模板时调用）。
     *
     * @param tagNames 标签名称列表
     */
    public void validateTagNames(List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return;
        }
        Set<String> existing = templateTagMapper.selectList(null).stream()
            .map(TemplateTag::getTagName)
            .collect(Collectors.toSet());
        List<String> invalid = tagNames.stream()
            .filter(name -> !existing.contains(name))
            .distinct()
            .toList();
        if (!invalid.isEmpty()) {
            throw new BusinessException("以下模板标签不存在，请先在标签管理中创建: " + String.join("、", invalid));
        }
    }

    /**
     * 重命名同步：替换存量模板方案 template_tags JSON 中的旧标签名。
     */
    private void syncSchemeTemplateTags(String oldName, String newName) {
        List<Scheme> schemes = schemeMapper.selectList(new QueryWrapper<Scheme>()
            .eq("is_template", true)
            .like("template_tags", "\"" + oldName + "\""));
        for (Scheme scheme : schemes) {
            try {
                List<String> tags = objectMapper.readValue(
                    scheme.getTemplateTags(), new TypeReference<List<String>>() {
                    });
                List<String> replaced = tags.stream()
                    .map(t -> oldName.equals(t) ? newName : t)
                    .distinct()
                    .toList();
                scheme.setTemplateTags(objectMapper.writeValueAsString(replaced));
                scheme.setUpdatedAt(LocalDateTime.now());
                schemeMapper.updateById(scheme);
            } catch (Exception e) {
                // 单个方案 JSON 异常不阻断整体重命名，记录后跳过
                org.slf4j.LoggerFactory.getLogger(TemplateTagService.class)
                    .warn("同步模板标签失败，schemeId={}", scheme.getSchemeId(), e);
            }
        }
    }

    private TemplateTag getTag(String tagId) {
        TemplateTag tag = templateTagMapper.selectById(tagId);
        if (tag == null) {
            throw new ResourceNotFoundException("模板标签不存在: " + tagId);
        }
        return tag;
    }

    private void assertTagNameUnique(String tagName, String excludeTagId) {
        QueryWrapper<TemplateTag> wrapper = new QueryWrapper<TemplateTag>().eq("tag_name", tagName);
        if (StringUtils.hasText(excludeTagId)) {
            wrapper.ne("tag_id", excludeTagId);
        }
        if (templateTagMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("同名标签已存在: " + tagName);
        }
    }

    private String currentUsername() {
        String username = SecurityOperatorContext.currentUsername();
        return StringUtils.hasText(username) ? username : "unknown";
    }

    private TemplateTag snapshot(TemplateTag source) {
        TemplateTag copy = new TemplateTag();
        copy.setTagId(source.getTagId());
        copy.setTagName(source.getTagName());
        copy.setSortOrder(source.getSortOrder());
        copy.setEnabled(source.getEnabled());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private TemplateTagResponse toResponse(TemplateTag tag) {
        TemplateTagResponse response = new TemplateTagResponse();
        response.setTagId(tag.getTagId());
        response.setTagName(tag.getTagName());
        response.setSortOrder(tag.getSortOrder());
        response.setEnabled(tag.getEnabled());
        response.setCreatedAt(tag.getCreatedAt());
        return response;
    }
}
