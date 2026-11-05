package com.iflytek.skillhub.gitsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.gitsync.plan.PlanProblem;
import com.iflytek.skillhub.gitsync.store.GitSourceRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The dry-run prediction must agree with what the publish pipeline would do:
 * these cases are the ones a real sync reports as failed directories.
 */
@ExtendWith(MockitoExtension.class)
class GitPlanPrecheckTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final String PUBLISHER = "user-1";

    @Mock
    private NamespaceRepository namespaceRepository;

    @Mock
    private NamespaceMemberRepository memberRepository;

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private SkillVersionRepository versionRepository;

    private GitPlanPrecheck precheck;

    @BeforeEach
    void setUp() {
        precheck = new GitPlanPrecheck(namespaceRepository, memberRepository, skillRepository, versionRepository);
    }

    @Test
    void flagsAnotherPublishersPublishedSkillWithTheSameName() {
        givenNamespaceAndMember();
        Skill published = skill("other-user", SkillStatus.ACTIVE);
        given(skillRepository.findByNamespaceIdAndSlug(any(), eq("pdf-helper"))).willReturn(List.of(published));
        given(versionRepository.findBySkillIdAndStatus(any(), eq(SkillVersionStatus.PUBLISHED)))
                .willReturn(List.of(new SkillVersion(null, "1.0.0", "other-user")));

        List<PlanProblem> problems = precheck.check(source(), "pdf-helper");

        assertEquals(List.of("error.skill.publish.nameConflict"), codes(problems));
    }

    @Test
    void doesNotFlagAnotherPublishersUnpublishedSkill() {
        givenNamespaceAndMember();
        given(skillRepository.findByNamespaceIdAndSlug(any(), eq("pdf-helper")))
                .willReturn(List.of(skill("other-user", SkillStatus.ACTIVE)));
        given(versionRepository.findBySkillIdAndStatus(any(), eq(SkillVersionStatus.PUBLISHED)))
                .willReturn(List.of());

        assertTrue(precheck.check(source(), "pdf-helper").isEmpty());
    }

    @Test
    void doesNotFlagThePublishersOwnSkill() {
        givenNamespaceAndMember();
        given(skillRepository.findByNamespaceIdAndSlug(any(), eq("pdf-helper")))
                .willReturn(List.of(skill(PUBLISHER, SkillStatus.ACTIVE)));

        assertTrue(precheck.check(source(), "pdf-helper").isEmpty());
    }

    @Test
    void flagsAnArchivedSkillOwnedByThePublisher() {
        givenNamespaceAndMember();
        given(skillRepository.findByNamespaceIdAndSlug(any(), eq("pdf-helper")))
                .willReturn(List.of(skill(PUBLISHER, SkillStatus.ARCHIVED)));

        List<PlanProblem> problems = precheck.check(source(), "pdf-helper");

        assertEquals(List.of("error.skill.publish.archived"), codes(problems));
    }

    @Test
    void flagsAPublisherThatIsNotAMemberOfTheNamespace() {
        given(namespaceRepository.findBySlug("global")).willReturn(Optional.of(new Namespace("global", "Global", "owner-1")));
        given(memberRepository.findByNamespaceIdAndUserId(any(), eq(PUBLISHER))).willReturn(Optional.empty());

        List<PlanProblem> problems = precheck.check(source(), "pdf-helper");

        assertEquals(List.of("error.skill.publish.publisher.notMember"), codes(problems));
    }

    @Test
    void flagsAMissingTargetNamespace() {
        given(namespaceRepository.findBySlug("global")).willReturn(Optional.empty());

        List<PlanProblem> problems = precheck.check(source(), "pdf-helper");

        assertEquals(List.of("error.namespace.slug.notFound"), codes(problems));
        assertFalse(problems.isEmpty());
    }

    private void givenNamespaceAndMember() {
        given(namespaceRepository.findBySlug("global")).willReturn(Optional.of(new Namespace("global", "Global", "owner-1")));
        given(memberRepository.findByNamespaceIdAndUserId(any(), eq(PUBLISHER))).willReturn(Optional.of(member()));
    }

    private static NamespaceMember member() {
        NamespaceMember member = new NamespaceMember(10L, PUBLISHER, NamespaceRole.OWNER);
        return member;
    }

    private static Skill skill(String ownerId, SkillStatus status) {
        Skill skill = new Skill(10L, "pdf-helper", ownerId, SkillVisibility.PUBLIC);
        skill.setStatus(status);
        return skill;
    }

    private static GitSourceRecord source() {
        return new GitSourceRecord(7L, "https://github.com/owner/repo", "owner/repo",
                "main", "", "global", null, PUBLISHER, 60, true, SkillVisibility.PUBLIC,
                PUBLISHER, NOW, NOW, null, null, null, null);
    }

    private static List<String> codes(List<PlanProblem> problems) {
        return problems.stream().map(PlanProblem::code).toList();
    }
}