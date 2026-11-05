package com.iflytek.skillhub.gitsync;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.gitsync.plan.PlanProblem;
import com.iflytek.skillhub.gitsync.store.GitSourceRecord;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Predicts, without publishing anything, the registry-side reasons a sync would
 * fail to import a directory.
 *
 * <p>Mirrors the checks {@code SkillPublishService} runs at import time: the
 * publisher must be a member of the target namespace, no other owner may have
 * published a skill with the same name, and the imported skill must not be
 * archived. Package-side problems (size limits, missing {@code SKILL.md})
 * are computed from the plan's file selection instead.
 *
 * <p>A prediction is only as fresh as the plan: a publish that lands between the
 * dry-run and the sync is not reflected here, and the sync itself remains the
 * authority.
 */
@Component
public class GitPlanPrecheck {

    private final NamespaceRepository namespaceRepository;
    private final NamespaceMemberRepository memberRepository;
    private final SkillRepository skillRepository;
    private final SkillVersionRepository versionRepository;

    public GitPlanPrecheck(NamespaceRepository namespaceRepository,
                           NamespaceMemberRepository memberRepository,
                           SkillRepository skillRepository,
                           SkillVersionRepository versionRepository) {
        this.namespaceRepository = namespaceRepository;
        this.memberRepository = memberRepository;
        this.skillRepository = skillRepository;
        this.versionRepository = versionRepository;
    }

    /**
     * Checks one planned directory whose {@code SKILL.md} yielded a skill slug.
     */
    public List<PlanProblem> check(GitSourceRecord source, String skillSlug) {
        List<PlanProblem> problems = new ArrayList<>();
        Namespace namespace = namespaceRepository.findBySlug(source.namespaceSlug()).orElse(null);
        if (namespace == null) {
            problems.add(PlanProblem.of("error.namespace.slug.notFound", source.namespaceSlug()));
            return problems;
        }

        // The import path publishes without platform roles, so membership is
        // always required - there is no SUPER_ADMIN bypass to predict.
        if (memberRepository.findByNamespaceIdAndUserId(namespace.getId(), source.publisherId()).isEmpty()) {
            problems.add(PlanProblem.of("error.skill.publish.publisher.notMember", source.namespaceSlug()));
        }

        List<Skill> sameSlug = skillRepository.findByNamespaceIdAndSlug(namespace.getId(), skillSlug);
        boolean otherOwnerPublished = sameSlug.stream()
                .filter(skill -> !skill.getOwnerId().equals(source.publisherId()))
                .anyMatch(this::hasPublishedVersion);
        if (otherOwnerPublished) {
            problems.add(PlanProblem.of("error.skill.publish.nameConflict", skillSlug));
        }

        boolean ownArchived = sameSlug.stream()
                .filter(skill -> skill.getOwnerId().equals(source.publisherId()))
                .anyMatch(skill -> skill.getStatus() == SkillStatus.ARCHIVED);
        if (ownArchived) {
            problems.add(PlanProblem.of("error.skill.publish.archived", skillSlug));
        }

        return problems;
    }

    private boolean hasPublishedVersion(Skill skill) {
        return !versionRepository.findBySkillIdAndStatus(skill.getId(), SkillVersionStatus.PUBLISHED).isEmpty();
    }
}