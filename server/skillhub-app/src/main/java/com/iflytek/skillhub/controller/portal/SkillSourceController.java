package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.gitsync.SkillSourceAppService;
import com.iflytek.skillhub.gitsync.dto.SkillSourceResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provenance of a skill: the repository, ref, commit, and subdirectory it was
 * synchronized from.
 *
 * <p>Visibility mirrors the skill itself, so non-public skills only expose their
 * provenance to namespace members and platform administrators.
 */
@RestController
@RequestMapping({"/api/v1/skills", "/api/web/skills"})
public class SkillSourceController extends BaseApiController {

    private final SkillSourceAppService skillSourceAppService;

    public SkillSourceController(ApiResponseFactory responseFactory, SkillSourceAppService skillSourceAppService) {
        super(responseFactory);
        this.skillSourceAppService = skillSourceAppService;
    }

    @GetMapping("/{namespace}/{slug}/source")
    public ApiResponse<SkillSourceResponse> getSkillSource(
            @PathVariable String namespace,
            @PathVariable String slug,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return ok("response.success.read", skillSourceAppService.getSource(
                namespace,
                slug,
                userNsRoles,
                principal == null ? Set.of() : principal.platformRoles()
        ));
    }
}
