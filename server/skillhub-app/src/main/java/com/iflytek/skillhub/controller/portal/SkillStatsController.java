package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.SkillDownloadStatsResponse;
import com.iflytek.skillhub.dto.SkillDownloadTopItemResponse;
import com.iflytek.skillhub.stats.SkillDownloadStatsAppService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only download statistics endpoints.
 *
 * <p>The ranking only covers public skills; per-skill statistics follow the same
 * visibility rules as the skill itself.
 */
@RestController
@RequestMapping({"/api/v1/stats", "/api/web/stats"})
public class SkillStatsController extends BaseApiController {

    private final SkillDownloadStatsAppService statsAppService;

    public SkillStatsController(ApiResponseFactory responseFactory, SkillDownloadStatsAppService statsAppService) {
        super(responseFactory);
        this.statsAppService = statsAppService;
    }

    @GetMapping("/skills/{skillId}")
    public ApiResponse<SkillDownloadStatsResponse> skillDownloadStats(
            @PathVariable Long skillId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        SkillDownloadStatsResponse response = statsAppService.loadSkillStats(
                skillId,
                from,
                to,
                userNsRoles,
                principal == null ? Set.of() : principal.platformRoles()
        );
        return ok("response.success.read", response);
    }

    @GetMapping("/top")
    public ApiResponse<List<SkillDownloadTopItemResponse>> topSkills(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "20") int limit) {
        return ok("response.success.read", statsAppService.topSkills(days, limit));
    }
}
