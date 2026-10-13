package com.iflytek.skillhub.controller.admin;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.MessageResponse;
import com.iflytek.skillhub.gitsync.GitSyncAdminService;
import com.iflytek.skillhub.gitsync.dto.GitImportPlanResponse;
import com.iflytek.skillhub.gitsync.dto.GitSourceCreateRequest;
import com.iflytek.skillhub.gitsync.dto.GitSourceItemResponse;
import com.iflytek.skillhub.gitsync.dto.GitSourceResponse;
import com.iflytek.skillhub.gitsync.dto.GitSyncRunResponse;
import com.iflytek.skillhub.service.AuditRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration of git skill sources.
 *
 * <p>Everything here is additive: sources are registered explicitly, and the
 * plan endpoint is a dry run that never modifies the registry.
 */
@RestController
@RequestMapping("/api/v1/admin/git-sources")
public class GitSourceAdminController extends BaseApiController {

    private final GitSyncAdminService gitSyncAdminService;

    public GitSourceAdminController(ApiResponseFactory responseFactory, GitSyncAdminService gitSyncAdminService) {
        super(responseFactory);
        this.gitSyncAdminService = gitSyncAdminService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SKILL_ADMIN')")
    public ApiResponse<List<GitSourceResponse>> listSources() {
        return ok("response.success.read", gitSyncAdminService.list());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SKILL_ADMIN')")
    public ApiResponse<GitSourceResponse> getSource(@PathVariable Long id) {
        return ok("response.success.read", gitSyncAdminService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SKILL_ADMIN')")
    public ApiResponse<GitSourceResponse> createSource(@Valid @RequestBody GitSourceCreateRequest request,
                                                       @AuthenticationPrincipal PlatformPrincipal principal,
                                                       HttpServletRequest httpRequest) {
        return ok("response.success.created",
                gitSyncAdminService.create(request, principal.userId(), AuditRequestContext.from(httpRequest)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SKILL_ADMIN')")
    public ApiResponse<GitSourceResponse> updateSource(@PathVariable Long id,
                                                       @Valid @RequestBody GitSourceCreateRequest request,
                                                       @AuthenticationPrincipal PlatformPrincipal principal,
                                                       HttpServletRequest httpRequest) {
        return ok("response.success.updated",
                gitSyncAdminService.update(id, request, principal.userId(), AuditRequestContext.from(httpRequest)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SKILL_ADMIN')")
    public ApiResponse<MessageResponse> deleteSource(@PathVariable Long id,
                                                     @AuthenticationPrincipal PlatformPrincipal principal,
                                                     HttpServletRequest httpRequest) {
        gitSyncAdminService.delete(id, principal.userId(), AuditRequestContext.from(httpRequest));
        return ok("response.success.deleted", new MessageResponse("Git source deleted"));
    }

    @GetMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SKILL_ADMIN')")
    public ApiResponse<List<GitSourceItemResponse>> listItems(@PathVariable Long id) {
        return ok("response.success.read", gitSyncAdminService.items(id));
    }

    /**
     * Dry-run import plan: what a sync would add, change, or remove.
     */
    @PostMapping("/{id}/plan")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SKILL_ADMIN')")
    public ApiResponse<GitImportPlanResponse> plan(@PathVariable Long id) {
        return ok("response.success.read", gitSyncAdminService.plan(id));
    }

    /**
     * Runs a sync immediately instead of waiting for the schedule.
     */
    @PostMapping("/{id}/sync")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SKILL_ADMIN')")
    public ApiResponse<GitSyncRunResponse> sync(@PathVariable Long id,
                                                @AuthenticationPrincipal PlatformPrincipal principal,
                                                HttpServletRequest httpRequest) {
        return ok("response.success.updated",
                gitSyncAdminService.sync(id, principal.userId(), AuditRequestContext.from(httpRequest)));
    }

    @GetMapping("/{id}/runs")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SKILL_ADMIN')")
    public ApiResponse<List<GitSyncRunResponse>> listRuns(@PathVariable Long id,
                                                         @RequestParam(required = false) Integer limit) {
        return ok("response.success.read", gitSyncAdminService.runs(id, limit));
    }
}
