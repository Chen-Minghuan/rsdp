package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.common.PageResult;
import com.rsdp.dto.request.ProjectRequest;
import com.rsdp.dto.response.ProjectDetailResponse;
import com.rsdp.dto.response.ProjectResponse;
import com.rsdp.dto.response.SchemeSummaryResponse;
import com.rsdp.entity.Project;
import com.rsdp.entity.Scheme;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ForbiddenException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ProjectMapper;
import com.rsdp.mapper.SchemeMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 设计项目服务：项目 CRUD 与归属校验，详情聚合项目下方案。
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final SchemeMapper schemeMapper;

    /**
     * 分页查询项目列表（非 ADMIN 仅可见自己的项目）。
     *
     * @param keyword 关键词（匹配项目名称/企业名称，可选）
     * @param page    页码（从 1 开始）
     * @param size    每页条数
     * @return 分页结果
     */
    public PageResult<ProjectResponse> list(String keyword, long page, long size) {
        String userId = currentUserIdRequired();
        QueryWrapper<Project> wrapper = new QueryWrapper<>();
        if (!SecurityOperatorContext.isCurrentUserAdmin()) {
            wrapper.eq("owner_id", userId);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like("project_name", keyword).or().like("company_name", keyword));
        }
        wrapper.orderByDesc("created_at");

        Page<Project> result = projectMapper.selectPage(Page.of(page, size), wrapper);
        Map<String, SchemeStats> statsMap = batchSchemeStats(
            result.getRecords().stream().map(Project::getProjectId).toList());
        List<ProjectResponse> rows = result.getRecords().stream()
            .map(p -> toResponse(p, statsMap.get(p.getProjectId())))
            .toList();
        return PageResult.of(result.getTotal(), page, size, rows);
    }

    /**
     * 创建设计项目。
     *
     * @param request 创建请求
     * @return 创建后的项目
     */
    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        Project project = new Project();
        project.setProjectId("PROJ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        project.setProjectName(request.getProjectName().trim());
        project.setProjectType(StringUtils.hasText(request.getProjectType()) ? request.getProjectType() : null);
        project.setCompanyName(StringUtils.hasText(request.getCompanyName()) ? request.getCompanyName() : null);
        project.setRemark(StringUtils.hasText(request.getRemark()) ? request.getRemark() : null);
        project.setOwnerId(currentUserIdRequired());
        project.setStatus("active");
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.insert(project);
        return toResponse(project, null);
    }

    /**
     * 查询项目详情（含项目下方案列表）。
     *
     * @param projectId 项目 ID
     * @return 项目详情
     */
    public ProjectDetailResponse detail(String projectId) {
        Project project = getAccessibleProject(projectId);
        List<Scheme> schemes = schemeMapper.selectList(new QueryWrapper<Scheme>()
            .eq("project_id", projectId)
            .orderByDesc("created_at"));

        ProjectDetailResponse response = new ProjectDetailResponse();
        copyBaseFields(toResponse(project, null), response);
        response.setSchemes(schemes.stream().map(this::toSchemeSummary).toList());
        response.setSchemeCount(schemes.size());
        response.setTotalPrice(schemes.stream()
            .map(Scheme::getTotalPrice)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        return response;
    }

    /**
     * 更新设计项目。
     *
     * @param projectId 项目 ID
     * @param request   更新请求（仅更新非空字段）
     * @return 更新后的项目
     */
    @Transactional
    public ProjectResponse update(String projectId, ProjectRequest request) {
        Project project = getAccessibleProject(projectId);
        if (StringUtils.hasText(request.getProjectName())) {
            project.setProjectName(request.getProjectName().trim());
        }
        if (request.getProjectType() != null) {
            project.setProjectType(StringUtils.hasText(request.getProjectType()) ? request.getProjectType() : null);
        }
        if (request.getCompanyName() != null) {
            project.setCompanyName(StringUtils.hasText(request.getCompanyName()) ? request.getCompanyName() : null);
        }
        if (request.getRemark() != null) {
            project.setRemark(StringUtils.hasText(request.getRemark()) ? request.getRemark() : null);
        }
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(project);
        Map<String, SchemeStats> statsMap = batchSchemeStats(List.of(projectId));
        return toResponse(project, statsMap.get(projectId));
    }

    /**
     * 软删除设计项目；项目下方案保留，project_id 置空。
     *
     * @param projectId 项目 ID
     */
    @Transactional
    public void delete(String projectId) {
        getAccessibleProject(projectId);
        projectMapper.deleteById(projectId);
        schemeMapper.update(null, new UpdateWrapper<Scheme>()
            .eq("project_id", projectId)
            .set("project_id", null));
    }

    /**
     * 校验项目存在且当前用户可访问（归属人或 ADMIN）。
     *
     * @param projectId 项目 ID
     * @return 项目实体
     */
    public Project getAccessibleProject(String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在: " + projectId);
        }
        if (!SecurityOperatorContext.isCurrentUserAdmin()
            && !project.getOwnerId().equals(SecurityOperatorContext.currentUserId())) {
            throw new ForbiddenException("无权访问该项目: " + projectId);
        }
        return project;
    }

    private String currentUserIdRequired() {
        String userId = SecurityOperatorContext.currentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException("无法获取当前用户 ID");
        }
        return userId;
    }

    private ProjectResponse toResponse(Project project, SchemeStats stats) {
        ProjectResponse response = new ProjectResponse();
        response.setProjectId(project.getProjectId());
        response.setProjectName(project.getProjectName());
        response.setProjectType(project.getProjectType());
        response.setCompanyName(project.getCompanyName());
        response.setOwnerId(project.getOwnerId());
        response.setStatus(project.getStatus());
        response.setRemark(project.getRemark());
        response.setSchemeCount(stats != null ? stats.count() : 0);
        response.setTotalPrice(stats != null ? stats.totalPrice() : BigDecimal.ZERO);
        response.setCreatedAt(project.getCreatedAt());
        response.setUpdatedAt(project.getUpdatedAt());
        return response;
    }

    private void copyBaseFields(ProjectResponse source, ProjectDetailResponse target) {
        target.setProjectId(source.getProjectId());
        target.setProjectName(source.getProjectName());
        target.setProjectType(source.getProjectType());
        target.setCompanyName(source.getCompanyName());
        target.setOwnerId(source.getOwnerId());
        target.setStatus(source.getStatus());
        target.setRemark(source.getRemark());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
    }

    private SchemeSummaryResponse toSchemeSummary(Scheme scheme) {
        SchemeSummaryResponse summary = new SchemeSummaryResponse();
        summary.setSchemeId(scheme.getSchemeId());
        summary.setSchemeName(scheme.getSchemeName());
        summary.setItemCount(scheme.getItemCount());
        summary.setTotalPrice(scheme.getTotalPrice());
        summary.setCreatedBy(scheme.getCreatedBy());
        summary.setCreatedAt(scheme.getCreatedAt());
        return summary;
    }

    private Map<String, SchemeStats> batchSchemeStats(List<String> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = schemeMapper.selectMaps(new QueryWrapper<Scheme>()
            .select("project_id", "COUNT(*) AS scheme_count", "COALESCE(SUM(total_price), 0) AS total_price")
            .in("project_id", projectIds)
            .groupBy("project_id"));
        return rows.stream().collect(Collectors.toMap(
            row -> (String) row.get("project_id"),
            row -> new SchemeStats(
                ((Number) row.get("scheme_count")).intValue(),
                row.get("total_price") instanceof BigDecimal bd ? bd : new BigDecimal(row.get("total_price").toString())),
            (a, b) -> a
        ));
    }

    private record SchemeStats(int count, BigDecimal totalPrice) {
    }
}
