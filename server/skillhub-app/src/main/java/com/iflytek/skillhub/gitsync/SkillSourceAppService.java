package com.iflytek.skillhub.gitsync;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.gitsync.dto.SkillSourceResponse;
import com.iflytek.skillhub.gitsync.git.GitRepoUrls;
import com.iflytek.skillhub.gitsync.store.GitSourceStore;
import com.iflytek.skillhub.gitsync.store.SkillSourceLinkRecord;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only provenance lookup for the skill detail page.
 */
@Service
public class SkillSourceAppService {

    private static final Set<String> ADMIN_ROLES = Set.of("SUPER_ADMIN", "SKILL_ADMIN", "AUDITOR");

    private final SkillRepository skillRepository;
    private final GitSourceStore store;

    public SkillSourceAppService(SkillRepository skillRepository, GitSourceStore store) {
        this.skillRepository = skillRepository;
        this.store = store;
    }

    @Transactional(readOnly = true)
    public SkillSourceResponse getSource(String namespaceSlug,
                                         String slug,
                                         Map<Long, NamespaceRole> userNamespaceRoles,
                                         Set<String> platformRoles) {
        Skill skill = skillRepository.findByNamespaceSlugAndSlug(namespaceSlug, slug).stream()
                .findFirst()
                .orElseThrow(() -> new DomainNotFoundException("error.skill.notFound", slug));
        assertVisible(skill, userNamespaceRoles, platformRoles);

        SkillSourceLinkRecord link = store.findLinkBySkillId(skill.getId())
                .orElseThrow(() -> new DomainNotFoundException("error.gitSource.item.notFound", slug));
        return new SkillSourceResponse(
                link.repoUrl(),
                GitRepoUrls.webUrl(link.repoUrl()),
                link.itemPath(),
                link.ref(),
                link.commitSha(),
                link.treeSha(),
                link.syncedAt()
        );
    }

    private static void assertVisible(Skill skill,
                                      Map<Long, NamespaceRole> userNamespaceRoles,
                                      Set<String> platformRoles) {
        if (skill.getStatus() != SkillStatus.ACTIVE || skill.isHidden()) {
            throw new DomainNotFoundException("error.skill.notFound", skill.getSlug());
        }
        if (skill.getVisibility() == SkillVisibility.PUBLIC) {
            return;
        }
        boolean namespaceMember = userNamespaceRoles != null && userNamespaceRoles.containsKey(skill.getNamespaceId());
        boolean platformAdmin = platformRoles != null && !Collections.disjoint(platformRoles, ADMIN_ROLES);
        if (!namespaceMember && !platformAdmin) {
            throw new DomainForbiddenException("error.skill.access.denied", skill.getSlug());
        }
    }
}
