package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.config.CacheConfig;
import com.rsdp.dto.request.PlatformBannerRequest;
import com.rsdp.dto.request.PlatformCaseRequest;
import com.rsdp.dto.request.PlatformContentRequest;
import com.rsdp.dto.request.PlatformCustomDictRequest;
import com.rsdp.dto.request.PlatformCustomizedRequest;
import com.rsdp.dto.request.SceneCoverUpdateRequest;
import com.rsdp.dto.response.PlatformBannerResponse;
import com.rsdp.dto.response.PlatformCaseResponse;
import com.rsdp.dto.response.PlatformContentResponse;
import com.rsdp.dto.response.PlatformCustomDictResponse;
import com.rsdp.dto.response.PlatformCustomizedResponse;
import com.rsdp.dto.response.SceneCoverResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.PlatformBanner;
import com.rsdp.entity.PlatformCase;
import com.rsdp.entity.PlatformContent;
import com.rsdp.entity.PlatformCustomDict;
import com.rsdp.entity.PlatformCustomized;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.CategoryDictMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.PlatformBannerMapper;
import com.rsdp.mapper.PlatformCaseMapper;
import com.rsdp.mapper.PlatformContentMapper;
import com.rsdp.mapper.PlatformCustomDictMapper;
import com.rsdp.mapper.PlatformCustomizedMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 官网 CMS 管理服务：Banner / 落地案例 / 内容配置 / 自定义字典 / 产品定制 五组薄 CRUD。
 *
 * <p>仅 ADMIN/EDITOR 可写（SecurityConfig 角色限定）；全部写操作记审计日志。
 * 公开读取走 {@code /api/v1/public/**}（PlatformPublicService）。</p>
 */
@Service
@RequiredArgsConstructor
public class PlatformCmsService {

    /** 合法状态值。 */
    private static final Set<String> VALID_STATUS = Set.of("active", "inactive");
    /** 合法 Banner 跳转类型。 */
    private static final Set<String> VALID_LINK_TYPES = Set.of("none", "rspu", "url");
    /** 合法内容类型。 */
    private static final Set<String> VALID_CONTENT_TYPES = Set.of("image", "rich_text", "embed");
    /** 空间场景字典类型。 */
    private static final String DICT_TYPE_SCENE = "scene";

    private final PlatformBannerMapper bannerMapper;
    private final PlatformCaseMapper caseMapper;
    private final PlatformContentMapper contentMapper;
    private final PlatformCustomDictMapper customDictMapper;
    private final PlatformCustomizedMapper customizedMapper;
    private final CategoryDictMapper categoryDictMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final AuditLogService auditLogService;

    // ==================== 空间场景封面（V35） ====================

    /**
     * 查询全部空间场景（dict_type=scene）的手配封面列表。
     *
     * @return 场景封面列表（code/name/imageId/imageUrl），按 sort_order 排序
     */
    public List<SceneCoverResponse> listSceneCovers() {
        return categoryDictMapper.selectAllByType(DICT_TYPE_SCENE).stream()
            .map(this::toSceneCoverResponse)
            .toList();
    }

    /**
     * 手配/清除空间场景封面图。
     *
     * <p>imageId 非空时校验图片存在；传 null/空白表示清除手配（公开端回退产品主图兜底）。
     * 写操作记审计日志，并清空字典缓存（公开端 listScenes 经 DictService 读缓存）。</p>
     *
     * @param code    场景字典码
     * @param request 封面图请求
     * @return 更新后的场景封面
     */
    @CacheEvict(value = CacheConfig.CACHE_NAME_DICTS, allEntries = true)
    @Transactional
    public SceneCoverResponse updateSceneCover(String code, SceneCoverUpdateRequest request) {
        CategoryDict scene = categoryDictMapper.selectOne(new QueryWrapper<CategoryDict>()
            .eq("dict_type", DICT_TYPE_SCENE)
            .eq("dict_code", code));
        if (scene == null) {
            throw new BusinessException("场景字典项不存在: scene=" + code);
        }
        String imageId = StringUtils.hasText(request.getImageId()) ? request.getImageId().trim() : null;
        if (imageId != null && imageAssetsMapper.selectById(imageId) == null) {
            throw new BusinessException("图片不存在: " + imageId);
        }
        CategoryDict oldSnapshot = copyCategoryDict(scene);
        scene.setImageId(imageId);
        // category_dict 为 (dict_type, dict_code) 复合主键，@TableId 仅标注 dict_type，
        // updateById 会误更新同类型全部行，必须按复合条件只更新 image_id 列
        categoryDictMapper.update(null, new UpdateWrapper<CategoryDict>()
            .eq("dict_type", DICT_TYPE_SCENE)
            .eq("dict_code", scene.getDictCode())
            .set("image_id", imageId));
        auditLogService.logUpdate("category_dict", DICT_TYPE_SCENE + ":" + scene.getDictCode(),
            oldSnapshot, scene, currentUsername());
        return toSceneCoverResponse(scene);
    }

    // ==================== Banner ====================

    /**
     * 查询 Banner 列表（管理端，含停用）。
     */
    public List<PlatformBannerResponse> listBanners() {
        return bannerMapper.selectList(new QueryWrapper<PlatformBanner>()
                .orderByAsc("position", "sort_order").orderByDesc("created_at"))
            .stream().map(this::toBannerResponse).toList();
    }

    /**
     * 创建 Banner。
     */
    @Transactional
    public PlatformBannerResponse createBanner(PlatformBannerRequest request) {
        validateStatus(request.getStatus());
        validateLinkType(request.getLinkType());
        PlatformBanner banner = new PlatformBanner();
        BeanUtils.copyProperties(request, banner);
        banner.setBannerId(IdGenerator.generate("BAN"));
        banner.setPosition(StringUtils.hasText(request.getPosition()) ? request.getPosition() : "home_top");
        banner.setLinkType(StringUtils.hasText(request.getLinkType()) ? request.getLinkType() : "none");
        banner.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "active");
        banner.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        banner.setCreatedAt(LocalDateTime.now());
        banner.setUpdatedAt(LocalDateTime.now());
        bannerMapper.insert(banner);
        auditLogService.logCreate("platform_banner", banner.getBannerId(), banner, currentUsername());
        return toBannerResponse(banner);
    }

    /**
     * 更新 Banner（仅更新非空字段）。
     */
    @Transactional
    public PlatformBannerResponse updateBanner(String bannerId, PlatformBannerRequest request) {
        PlatformBanner banner = getBanner(bannerId);
        validateStatus(request.getStatus());
        validateLinkType(request.getLinkType());
        PlatformBanner oldSnapshot = copyBanner(banner);
        applyIfPresent(request, banner);
        banner.setUpdatedAt(LocalDateTime.now());
        bannerMapper.updateById(banner);
        auditLogService.logUpdate("platform_banner", bannerId, oldSnapshot, banner, currentUsername());
        return toBannerResponse(banner);
    }

    /**
     * 删除 Banner。
     */
    @Transactional
    public void deleteBanner(String bannerId) {
        PlatformBanner banner = getBanner(bannerId);
        bannerMapper.deleteById(bannerId);
        auditLogService.logDelete("platform_banner", bannerId, banner, currentUsername());
    }

    // ==================== 落地案例 ====================

    /**
     * 查询案例列表（管理端，含停用）。
     */
    public List<PlatformCaseResponse> listCases() {
        return caseMapper.selectList(new QueryWrapper<PlatformCase>()
                .orderByAsc("sort_order").orderByDesc("created_at"))
            .stream().map(this::toCaseResponse).toList();
    }

    /**
     * 创建案例。
     */
    @Transactional
    public PlatformCaseResponse createCase(PlatformCaseRequest request) {
        validateStatus(request.getStatus());
        PlatformCase entity = new PlatformCase();
        BeanUtils.copyProperties(request, entity);
        entity.setCaseId(IdGenerator.generate("CASE"));
        entity.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "active");
        entity.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        caseMapper.insert(entity);
        auditLogService.logCreate("platform_case", entity.getCaseId(), entity, currentUsername());
        return toCaseResponse(entity);
    }

    /**
     * 更新案例（仅更新非空字段）。
     */
    @Transactional
    public PlatformCaseResponse updateCase(String caseId, PlatformCaseRequest request) {
        PlatformCase entity = getCase(caseId);
        validateStatus(request.getStatus());
        PlatformCase oldSnapshot = copyCase(entity);
        applyIfPresent(request, entity);
        entity.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(entity);
        auditLogService.logUpdate("platform_case", caseId, oldSnapshot, entity, currentUsername());
        return toCaseResponse(entity);
    }

    /**
     * 删除案例。
     */
    @Transactional
    public void deleteCase(String caseId) {
        PlatformCase entity = getCase(caseId);
        caseMapper.deleteById(caseId);
        auditLogService.logDelete("platform_case", caseId, entity, currentUsername());
    }

    // ==================== 内容配置 ====================

    /**
     * 查询内容配置列表（管理端）。
     */
    public List<PlatformContentResponse> listContents() {
        return contentMapper.selectList(new QueryWrapper<PlatformContent>()
                .orderByAsc("code"))
            .stream().map(this::toContentResponse).toList();
    }

    /**
     * 创建内容配置（code 唯一）。
     */
    @Transactional
    public PlatformContentResponse createContent(PlatformContentRequest request) {
        validateStatus(request.getStatus());
        validateContentType(request.getContentType());
        if (contentMapper.selectCount(new QueryWrapper<PlatformContent>().eq("code", request.getCode())) > 0) {
            throw new BusinessException("内容编码已存在: " + request.getCode());
        }
        PlatformContent entity = new PlatformContent();
        BeanUtils.copyProperties(request, entity);
        entity.setContentId(IdGenerator.generate("CONT"));
        entity.setContentType(StringUtils.hasText(request.getContentType()) ? request.getContentType() : "rich_text");
        entity.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "active");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        contentMapper.insert(entity);
        auditLogService.logCreate("platform_content", entity.getContentId(), entity, currentUsername());
        return toContentResponse(entity);
    }

    /**
     * 更新内容配置（code 不可修改；仅更新非空字段）。
     */
    @Transactional
    public PlatformContentResponse updateContent(String contentId, PlatformContentRequest request) {
        PlatformContent entity = getContent(contentId);
        validateStatus(request.getStatus());
        validateContentType(request.getContentType());
        if (StringUtils.hasText(request.getCode()) && !request.getCode().equals(entity.getCode())) {
            throw new BusinessException("内容编码创建后不可修改");
        }
        PlatformContent oldSnapshot = copyContent(entity);
        if (StringUtils.hasText(request.getTitle())) {
            entity.setTitle(request.getTitle());
        }
        if (StringUtils.hasText(request.getContentType())) {
            entity.setContentType(request.getContentType());
        }
        if (request.getContent() != null) {
            entity.setContent(request.getContent());
        }
        if (StringUtils.hasText(request.getStatus())) {
            entity.setStatus(request.getStatus());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        contentMapper.updateById(entity);
        auditLogService.logUpdate("platform_content", contentId, oldSnapshot, entity, currentUsername());
        return toContentResponse(entity);
    }

    /**
     * 删除内容配置。
     */
    @Transactional
    public void deleteContent(String contentId) {
        PlatformContent entity = getContent(contentId);
        contentMapper.deleteById(contentId);
        auditLogService.logDelete("platform_content", contentId, entity, currentUsername());
    }

    // ==================== 自定义字典 ====================

    /**
     * 查询自定义字典列表（管理端）。
     */
    public List<PlatformCustomDictResponse> listCustomDicts() {
        return customDictMapper.selectList(new QueryWrapper<PlatformCustomDict>()
                .orderByAsc("dict_type", "dict_name"))
            .stream().map(this::toDictResponse).toList();
    }

    /**
     * 创建自定义字典（dict_type + dict_name 唯一）。
     */
    @Transactional
    public PlatformCustomDictResponse createCustomDict(PlatformCustomDictRequest request) {
        validateStatus(request.getStatus());
        assertDictUnique(request.getDictType(), request.getDictName(), null);
        PlatformCustomDict entity = new PlatformCustomDict();
        BeanUtils.copyProperties(request, entity);
        entity.setDictId(IdGenerator.generate("PDIC"));
        entity.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "active");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        customDictMapper.insert(entity);
        auditLogService.logCreate("platform_custom_dict", entity.getDictId(), entity, currentUsername());
        return toDictResponse(entity);
    }

    /**
     * 更新自定义字典。
     */
    @Transactional
    public PlatformCustomDictResponse updateCustomDict(String dictId, PlatformCustomDictRequest request) {
        PlatformCustomDict entity = getDict(dictId);
        validateStatus(request.getStatus());
        assertDictUnique(request.getDictType(), request.getDictName(), dictId);
        PlatformCustomDict oldSnapshot = copyDict(entity);
        entity.setDictName(request.getDictName());
        entity.setDictType(request.getDictType());
        if (StringUtils.hasText(request.getStatus())) {
            entity.setStatus(request.getStatus());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        customDictMapper.updateById(entity);
        auditLogService.logUpdate("platform_custom_dict", dictId, oldSnapshot, entity, currentUsername());
        return toDictResponse(entity);
    }

    /**
     * 删除自定义字典。
     */
    @Transactional
    public void deleteCustomDict(String dictId) {
        PlatformCustomDict entity = getDict(dictId);
        customDictMapper.deleteById(dictId);
        auditLogService.logDelete("platform_custom_dict", dictId, entity, currentUsername());
    }

    // ==================== 产品定制 ====================

    /**
     * 查询产品定制列表（管理端，含停用）。
     */
    public List<PlatformCustomizedResponse> listCustomizeds() {
        return customizedMapper.selectList(new QueryWrapper<PlatformCustomized>()
                .orderByAsc("sort_order").orderByDesc("created_at"))
            .stream().map(this::toCustomizedResponse).toList();
    }

    /**
     * 创建产品定制。
     */
    @Transactional
    public PlatformCustomizedResponse createCustomized(PlatformCustomizedRequest request) {
        validateStatus(request.getStatus());
        PlatformCustomized entity = new PlatformCustomized();
        BeanUtils.copyProperties(request, entity);
        entity.setCustomizedId(IdGenerator.generate("CUST"));
        entity.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "active");
        entity.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        customizedMapper.insert(entity);
        auditLogService.logCreate("platform_customized", entity.getCustomizedId(), entity, currentUsername());
        return toCustomizedResponse(entity);
    }

    /**
     * 更新产品定制（仅更新非空字段）。
     */
    @Transactional
    public PlatformCustomizedResponse updateCustomized(String customizedId, PlatformCustomizedRequest request) {
        PlatformCustomized entity = getCustomized(customizedId);
        validateStatus(request.getStatus());
        PlatformCustomized oldSnapshot = copyCustomized(entity);
        applyIfPresent(request, entity);
        entity.setUpdatedAt(LocalDateTime.now());
        customizedMapper.updateById(entity);
        auditLogService.logUpdate("platform_customized", customizedId, oldSnapshot, entity, currentUsername());
        return toCustomizedResponse(entity);
    }

    /**
     * 删除产品定制。
     */
    @Transactional
    public void deleteCustomized(String customizedId) {
        PlatformCustomized entity = getCustomized(customizedId);
        customizedMapper.deleteById(customizedId);
        auditLogService.logDelete("platform_customized", customizedId, entity, currentUsername());
    }

    // ==================== 私有辅助 ====================

    private void applyIfPresent(PlatformBannerRequest request, PlatformBanner banner) {
        if (StringUtils.hasText(request.getPosition())) {
            banner.setPosition(request.getPosition());
        }
        if (request.getTitle() != null) {
            banner.setTitle(request.getTitle());
        }
        if (StringUtils.hasText(request.getImageId())) {
            banner.setImageId(request.getImageId());
        }
        if (StringUtils.hasText(request.getLinkType())) {
            banner.setLinkType(request.getLinkType());
        }
        if (request.getLinkValue() != null) {
            banner.setLinkValue(request.getLinkValue());
        }
        if (request.getSortOrder() != null) {
            banner.setSortOrder(request.getSortOrder());
        }
        if (StringUtils.hasText(request.getStatus())) {
            banner.setStatus(request.getStatus());
        }
    }

    private void applyIfPresent(PlatformCaseRequest request, PlatformCase entity) {
        if (StringUtils.hasText(request.getTitle())) {
            entity.setTitle(request.getTitle());
        }
        if (request.getCoverImageId() != null) {
            entity.setCoverImageId(request.getCoverImageId());
        }
        if (request.getContent() != null) {
            entity.setContent(request.getContent());
        }
        if (request.getSortOrder() != null) {
            entity.setSortOrder(request.getSortOrder());
        }
        if (StringUtils.hasText(request.getStatus())) {
            entity.setStatus(request.getStatus());
        }
    }

    private void applyIfPresent(PlatformCustomizedRequest request, PlatformCustomized entity) {
        if (StringUtils.hasText(request.getTitle())) {
            entity.setTitle(request.getTitle());
        }
        if (request.getCoverImageId() != null) {
            entity.setCoverImageId(request.getCoverImageId());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }
        if (request.getLinkValue() != null) {
            entity.setLinkValue(request.getLinkValue());
        }
        if (request.getSortOrder() != null) {
            entity.setSortOrder(request.getSortOrder());
        }
        if (StringUtils.hasText(request.getStatus())) {
            entity.setStatus(request.getStatus());
        }
    }

    private void assertDictUnique(String dictType, String dictName, String excludeDictId) {
        QueryWrapper<PlatformCustomDict> wrapper = new QueryWrapper<PlatformCustomDict>()
            .eq("dict_type", dictType).eq("dict_name", dictName);
        if (StringUtils.hasText(excludeDictId)) {
            wrapper.ne("dict_id", excludeDictId);
        }
        if (customDictMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("同类型下字典名称已存在: " + dictName);
        }
    }

    private void validateStatus(String status) {
        if (StringUtils.hasText(status) && !VALID_STATUS.contains(status)) {
            throw new BusinessException("非法状态值: " + status + "（可选 active/inactive）");
        }
    }

    private void validateLinkType(String linkType) {
        if (StringUtils.hasText(linkType) && !VALID_LINK_TYPES.contains(linkType)) {
            throw new BusinessException("非法跳转类型: " + linkType + "（可选 none/rspu/url）");
        }
    }

    private void validateContentType(String contentType) {
        if (StringUtils.hasText(contentType) && !VALID_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException("非法内容类型: " + contentType + "（可选 image/rich_text/embed）");
        }
    }

    private PlatformBanner getBanner(String bannerId) {
        PlatformBanner banner = bannerMapper.selectById(bannerId);
        if (banner == null) {
            throw new ResourceNotFoundException("Banner 不存在: " + bannerId);
        }
        return banner;
    }

    private PlatformCase getCase(String caseId) {
        PlatformCase entity = caseMapper.selectById(caseId);
        if (entity == null) {
            throw new ResourceNotFoundException("案例不存在: " + caseId);
        }
        return entity;
    }

    private PlatformContent getContent(String contentId) {
        PlatformContent entity = contentMapper.selectById(contentId);
        if (entity == null) {
            throw new ResourceNotFoundException("内容配置不存在: " + contentId);
        }
        return entity;
    }

    private PlatformCustomDict getDict(String dictId) {
        PlatformCustomDict entity = customDictMapper.selectById(dictId);
        if (entity == null) {
            throw new ResourceNotFoundException("自定义字典不存在: " + dictId);
        }
        return entity;
    }

    private PlatformCustomized getCustomized(String customizedId) {
        PlatformCustomized entity = customizedMapper.selectById(customizedId);
        if (entity == null) {
            throw new ResourceNotFoundException("产品定制不存在: " + customizedId);
        }
        return entity;
    }

    private String currentUsername() {
        String username = SecurityOperatorContext.currentUsername();
        return StringUtils.hasText(username) ? username : "unknown";
    }

    private PlatformBanner copyBanner(PlatformBanner source) {
        PlatformBanner copy = new PlatformBanner();
        BeanUtils.copyProperties(source, copy);
        return copy;
    }

    private PlatformCase copyCase(PlatformCase source) {
        PlatformCase copy = new PlatformCase();
        BeanUtils.copyProperties(source, copy);
        return copy;
    }

    private PlatformContent copyContent(PlatformContent source) {
        PlatformContent copy = new PlatformContent();
        BeanUtils.copyProperties(source, copy);
        return copy;
    }

    private PlatformCustomDict copyDict(PlatformCustomDict source) {
        PlatformCustomDict copy = new PlatformCustomDict();
        BeanUtils.copyProperties(source, copy);
        return copy;
    }

    private PlatformCustomized copyCustomized(PlatformCustomized source) {
        PlatformCustomized copy = new PlatformCustomized();
        BeanUtils.copyProperties(source, copy);
        return copy;
    }

    private CategoryDict copyCategoryDict(CategoryDict source) {
        CategoryDict copy = new CategoryDict();
        BeanUtils.copyProperties(source, copy);
        return copy;
    }

    private SceneCoverResponse toSceneCoverResponse(CategoryDict scene) {
        SceneCoverResponse response = new SceneCoverResponse();
        response.setCode(scene.getDictCode());
        response.setName(scene.getDictName());
        response.setImageId(scene.getImageId());
        response.setImageUrl(StringUtils.hasText(scene.getImageId())
            ? "/api/v1/images/" + scene.getImageId() : null);
        return response;
    }

    private PlatformBannerResponse toBannerResponse(PlatformBanner banner) {
        PlatformBannerResponse response = new PlatformBannerResponse();
        BeanUtils.copyProperties(banner, response);
        return response;
    }

    private PlatformCaseResponse toCaseResponse(PlatformCase entity) {
        PlatformCaseResponse response = new PlatformCaseResponse();
        BeanUtils.copyProperties(entity, response);
        return response;
    }

    private PlatformContentResponse toContentResponse(PlatformContent entity) {
        PlatformContentResponse response = new PlatformContentResponse();
        BeanUtils.copyProperties(entity, response);
        return response;
    }

    private PlatformCustomDictResponse toDictResponse(PlatformCustomDict entity) {
        PlatformCustomDictResponse response = new PlatformCustomDictResponse();
        BeanUtils.copyProperties(entity, response);
        return response;
    }

    private PlatformCustomizedResponse toCustomizedResponse(PlatformCustomized entity) {
        PlatformCustomizedResponse response = new PlatformCustomizedResponse();
        BeanUtils.copyProperties(entity, response);
        return response;
    }
}
