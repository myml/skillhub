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
import java.util.Set;
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
 * Management of git skill sources.
 *
 * <p>Any signed-in user may register a source for a namespace they belong to;
 * they only ever see and change the sources they registered themselves.
 * Platform administrators see and manage every source.
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
    public ApiResponse<List<GitSourceResponse>> listSources(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ok("response.success.read",
                gitSyncAdminService.list(principal.userId(), canManageAllSources(principal)));
    }

    @GetMapping("/{id}")
    public ApiResponse<GitSourceResponse> getSource(@PathVariable Long id,
                                                    @AuthenticationPrincipal PlatformPrincipal principal) {
        return ok("response.success.read",
                gitSyncAdminService.get(id, principal.userId(), canManageAllSources(principal)));
    }

    @PostMapping
    public ApiResponse<GitSourceResponse> createSource(@Valid @RequestBody GitSourceCreateRequest request,
                                                       @AuthenticationPrincipal PlatformPrincipal principal,
                                                       HttpServletRequest httpRequest) {
        // Fail on the form, not on the next scheduled sync: the branch is verified
        // against the real repository before anything is stored.
        gitSyncAdminService.preflightCreate(request);
        return ok("response.success.created",
                gitSyncAdminService.create(request, principal.userId(), AuditRequestContext.from(httpRequest)));
    }

    @PutMapping("/{id}")
    public ApiResponse<GitSourceResponse> updateSource(@PathVariable Long id,
                                                       @Valid @RequestBody GitSourceCreateRequest request,
                                                       @AuthenticationPrincipal PlatformPrincipal principal,
                                                       HttpServletRequest httpRequest) {
        gitSyncAdminService.preflightUpdate(id, request, principal.userId(), canManageAllSources(principal));
        return ok("response.success.updated",
                gitSyncAdminService.update(id, request, principal.userId(), canManageAllSources(principal),
                        AuditRequestContext.from(httpRequest)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<MessageResponse> deleteSource(@PathVariable Long id,
                                                     @AuthenticationPrincipal PlatformPrincipal principal,
                                                     HttpServletRequest httpRequest) {
        gitSyncAdminService.delete(id, principal.userId(), canManageAllSources(principal),
                AuditRequestContext.from(httpRequest));
        return ok("response.success.deleted", new MessageResponse("Git source deleted"));
    }

    @GetMapping("/{id}/items")
    public ApiResponse<List<GitSourceItemResponse>> listItems(@PathVariable Long id,
                                                              @AuthenticationPrincipal PlatformPrincipal principal) {
        return ok("response.success.read",
                gitSyncAdminService.items(id, principal.userId(), canManageAllSources(principal)));
    }

    /**
     * Dry-run import plan: what a sync would add, change, or remove.
     */
    @PostMapping("/{id}/plan")
    public ApiResponse<GitImportPlanResponse> plan(@PathVariable Long id,
                                                   @AuthenticationPrincipal PlatformPrincipal principal) {
        return ok("response.success.read",
                gitSyncAdminService.plan(id, principal.userId(), canManageAllSources(principal)));
    }

    /**
     * Runs a sync immediately instead of waiting for the schedule.
     */
    @PostMapping("/{id}/sync")
    public ApiResponse<GitSyncRunResponse> sync(@PathVariable Long id,
                                                @AuthenticationPrincipal PlatformPrincipal principal,
                                                HttpServletRequest httpRequest) {
        return ok("response.success.updated",
                gitSyncAdminService.sync(id, principal.userId(), canManageAllSources(principal),
                        AuditRequestContext.from(httpRequest)));
    }

    @GetMapping("/{id}/runs")
    public ApiResponse<List<GitSyncRunResponse>> listRuns(@PathVariable Long id,
                                                         @RequestParam(required = false) Integer limit,
                                                         @AuthenticationPrincipal PlatformPrincipal principal) {
        return ok("response.success.read",
                gitSyncAdminService.runs(id, limit, principal.userId(), canManageAllSources(principal)));
    }

    /**
     * Platforms administrators manage every source; everybody else manages the
     * sources they registered.
     */
    private static boolean canManageAllSources(PlatformPrincipal principal) {
        Set<String> roles = principal.platformRoles() == null ? Set.of() : principal.platformRoles();
        return roles.contains("SUPER_ADMIN") || roles.contains("SKILL_ADMIN");
    }
}
