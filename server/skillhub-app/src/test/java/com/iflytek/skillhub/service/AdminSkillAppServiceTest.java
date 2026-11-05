package com.iflytek.skillhub.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.domain.user.UserStatus;
import com.iflytek.skillhub.dto.AdminSkillBatchResultResponse;
import com.iflytek.skillhub.dto.AdminSkillSummaryResponse;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.gitsync.store.GitSourceStore;
import com.iflytek.skillhub.gitsync.store.SkillSourceLinkRecord;
import com.iflytek.skillhub.repository.AdminSkillSearchRepository;
import com.iflytek.skillhub.repository.AdminSkillSearchRepository.Filter;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class AdminSkillAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");

    @Mock private AdminSkillSearchRepository searchRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private NamespaceRepository namespaceRepository;
    @Mock private NamespaceMemberRepository namespaceMemberRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private SkillDeleteAppService skillDeleteAppService;
    @Mock private GitSourceStore gitSourceStore;
    @Mock private AdminUserAppService adminUserAppService;
    @Mock private AuditLogService auditLogService;
    @Mock private MessageSource messageSource;
    @Mock private PlatformTransactionManager transactionManager;

    private AdminSkillAppService service;

    @BeforeEach
    void setUp() {
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new AdminSkillAppService(
                searchRepository,
                skillRepository,
                namespaceRepository,
                namespaceMemberRepository,
                userAccountRepository,
                skillDeleteAppService,
                gitSourceStore,
                adminUserAppService,
                auditLogService,
                messageSource,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactionManager
        );
    }

    // ---- list ------------------------------------------------------------

    @Test
    void listMapsNamespaceSlugAndOwnerDisplayName() {
        Skill skill = skill(7L, 3L, "pdf", "pdf-toolkit", "u-1");
        given(searchRepository.search(any(), any())).willReturn(
                new PageImpl<>(List.of(skill), PageRequest.of(0, 20), 1));
        given(namespaceRepository.findByIdIn(any())).willReturn(List.of(namespace(3L, "global")));
        given(userAccountRepository.findByIdIn(any())).willReturn(List.of(user("u-1", "Alice")));

        PageResponse<AdminSkillSummaryResponse> page = service.list(new AdminSkillAppService.ListFilter(
                null, null, null, null, null, null), 0, 20);

        assertEquals(1, page.items().size());
        AdminSkillSummaryResponse row = page.items().get(0);
        assertEquals("global", row.namespaceSlug());
        assertEquals("Alice", row.ownerDisplayName());
        assertEquals("PUBLIC", row.visibility());
        assertEquals("ACTIVE", row.status());
        assertEquals(1, page.total());
    }

    @Test
    void listPassesFiltersThroughToTheQuery() {
        given(searchRepository.search(any(), any())).willReturn(
                new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        given(namespaceRepository.findBySlug("global")).willReturn(Optional.of(namespace(3L, "global")));

        service.list(new AdminSkillAppService.ListFilter(
                " pdf ", "global", "active", "private", Boolean.TRUE, " u-1 "), 1, 50);

        ArgumentCaptor<Filter> captor = ArgumentCaptor.forClass(Filter.class);
        then(searchRepository).should().search(captor.capture(), any());
        Filter filter = captor.getValue();
        assertEquals("pdf", filter.keyword());
        assertEquals(3L, filter.namespaceId());
        assertEquals(SkillStatus.ACTIVE, filter.status());
        assertEquals(SkillVisibility.PRIVATE, filter.visibility());
        assertEquals(Boolean.TRUE, filter.hidden());
        assertEquals("u-1", filter.ownerId());
    }

    @Test
    void listRejectsAnUnknownNamespace() {
        given(namespaceRepository.findBySlug("nope")).willReturn(Optional.empty());

        assertThrows(DomainBadRequestException.class, () -> service.list(
                new AdminSkillAppService.ListFilter(null, "nope", null, null, null, null), 0, 20));
    }

    @Test
    void listRejectsAnUnknownStatus() {
        assertThrows(DomainBadRequestException.class, () -> service.list(
                new AdminSkillAppService.ListFilter(null, null, "deleted", null, null, null), 0, 20));
    }

    // ---- transfer --------------------------------------------------------

    @Test
    void transferMovesOwnershipAndAuditsIt() throws Exception {
        Skill skill = skill(7L, 3L, "pdf", "pdf-toolkit", "u-1");
        given(skillRepository.findById(7L)).willReturn(Optional.of(skill));
        given(userAccountRepository.findById("u-2")).willReturn(Optional.of(user("u-2", "Bob")));
        given(namespaceMemberRepository.findByNamespaceIdAndUserId(3L, "u-2"))
                .willReturn(Optional.of(new NamespaceMember(3L, "u-2", NamespaceRole.MEMBER)));
        given(skillRepository.findByNamespaceIdAndSlugAndOwnerId(3L, "pdf", "u-2")).willReturn(Optional.empty());
        given(namespaceRepository.findById(3L)).willReturn(Optional.of(namespace(3L, "global")));

        AdminSkillBatchResultResponse result = service.transfer(
                List.of(7L), "u-2", "admin-1", new AuditRequestContext("1.2.3.4", "test"));

        assertEquals(List.of(7L), result.succeeded());
        assertTrue(result.failed().isEmpty());
        assertEquals("u-2", ownerIdOf(skill));
        then(skillRepository).should().save(skill);
        then(auditLogService).should().record(
                eq("admin-1"), eq("TRANSFER_SKILL"), eq("SKILL"), eq(7L),
                any(), eq("1.2.3.4"), eq("test"), anyString());
    }

    @Test
    void transferRejectsASkillThatAlreadyBelongsToTheTarget() {
        given(skillRepository.findById(7L)).willReturn(Optional.of(skill(7L, 3L, "pdf", "pdf", "u-2")));
        given(userAccountRepository.findById("u-2")).willReturn(Optional.of(user("u-2", "Bob")));

        AdminSkillBatchResultResponse result = service.transfer(
                List.of(7L), "u-2", "admin-1", null);

        assertEquals(List.of(), result.succeeded());
        assertEquals("error.skill.transfer.sameOwner", result.failed().get(0).reason());
    }

    @Test
    void transferRejectsATargetThatIsNotANamespaceMember() {
        given(skillRepository.findById(7L)).willReturn(Optional.of(skill(7L, 3L, "pdf", "pdf", "u-1")));
        given(userAccountRepository.findById("u-2")).willReturn(Optional.of(user("u-2", "Bob")));
        given(namespaceMemberRepository.findByNamespaceIdAndUserId(3L, "u-2")).willReturn(Optional.empty());

        AdminSkillBatchResultResponse result = service.transfer(
                List.of(7L), "u-2", "admin-1", null);

        assertEquals("error.skill.transfer.targetNotMember", result.failed().get(0).reason());
        then(skillRepository).should(never()).save(any());
    }

    @Test
    void transferRejectsWhenTheTargetAlreadyOwnsTheSameSlug() {
        given(skillRepository.findById(7L)).willReturn(Optional.of(skill(7L, 3L, "pdf", "pdf", "u-1")));
        given(userAccountRepository.findById("u-2")).willReturn(Optional.of(user("u-2", "Bob")));
        given(namespaceMemberRepository.findByNamespaceIdAndUserId(3L, "u-2"))
                .willReturn(Optional.of(new NamespaceMember(3L, "u-2", NamespaceRole.MEMBER)));
        given(skillRepository.findByNamespaceIdAndSlugAndOwnerId(3L, "pdf", "u-2"))
                .willReturn(Optional.of(skill(9L, 3L, "pdf", "pdf", "u-2")));

        AdminSkillBatchResultResponse result = service.transfer(
                List.of(7L), "u-2", "admin-1", null);

        assertEquals("error.skill.transfer.slugConflict", result.failed().get(0).reason());
    }

    @Test
    void transferRejectsAGitSyncedSkill() {
        given(skillRepository.findById(7L)).willReturn(Optional.of(skill(7L, 3L, "pdf", "pdf", "u-1")));
        given(userAccountRepository.findById("u-2")).willReturn(Optional.of(user("u-2", "Bob")));
        given(gitSourceStore.findLinkBySkillId(7L)).willReturn(Optional.of(
                new SkillSourceLinkRecord(1L, 7L, 1L, "github.com/a/b", "skills/pdf", "main", "sha", "tree", NOW)));

        AdminSkillBatchResultResponse result = service.transfer(
                List.of(7L), "u-2", "admin-1", null);

        assertEquals("error.skill.transfer.gitSynced", result.failed().get(0).reason());
        then(skillRepository).should(never()).save(any());
    }

    @Test
    void transferKeepsGoingWhenOneSkillFails() {
        Skill ok = skill(7L, 3L, "pdf", "pdf", "u-1");
        given(skillRepository.findById(7L)).willReturn(Optional.of(ok));
        given(skillRepository.findById(8L)).willReturn(Optional.empty());
        given(userAccountRepository.findById("u-2")).willReturn(Optional.of(user("u-2", "Bob")));
        given(namespaceMemberRepository.findByNamespaceIdAndUserId(3L, "u-2"))
                .willReturn(Optional.of(new NamespaceMember(3L, "u-2", NamespaceRole.MEMBER)));
        given(skillRepository.findByNamespaceIdAndSlugAndOwnerId(3L, "pdf", "u-2")).willReturn(Optional.empty());
        given(namespaceRepository.findById(3L)).willReturn(Optional.of(namespace(3L, "global")));

        AdminSkillBatchResultResponse result = service.transfer(
                List.of(7L, 8L), "u-2", "admin-1", null);

        assertEquals(List.of(7L), result.succeeded());
        assertEquals(1, result.failed().size());
        assertEquals(8L, result.failed().get(0).skillId());
        assertEquals("error.skill.notFound", result.failed().get(0).reason());
    }

    @Test
    void transferRequiresAnActiveTarget() {
        given(userAccountRepository.findById("u-2")).willReturn(Optional.of(userWithStatus("u-2", "Bob", UserStatus.DISABLED)));

        assertThrows(DomainBadRequestException.class,
                () -> service.transfer(List.of(7L), "u-2", "admin-1", null));
    }

    @Test
    void transferRejectsAnEmptySelection() {
        assertThrows(DomainBadRequestException.class,
                () -> service.transfer(List.of(), "u-2", "admin-1", null));
    }

    // ---- delete ----------------------------------------------------------

    @Test
    void deleteReportsPerSkillOutcomes() {
        given(skillDeleteAppService.deleteSkillById(eq(7L), anyString(), any())).willReturn(null);
        org.mockito.BDDMockito.willThrow(new DomainBadRequestException("error.skill.notFound", 8L))
                .given(skillDeleteAppService).deleteSkillById(eq(8L), anyString(), any());

        AdminSkillBatchResultResponse result = service.delete(List.of(7L, 8L), "admin-1",
                new AuditRequestContext("1.2.3.4", "test"));

        assertEquals(List.of(7L), result.succeeded());
        assertEquals(1, result.failed().size());
    }

    @Test
    void deleteRejectsBatchesAboveTheLimit() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, AdminSkillAppService.MAX_BATCH_SIZE + 1)
                .boxed().toList();

        assertThrows(DomainBadRequestException.class,
                () -> service.delete(ids, "admin-1", null));
    }

    // ---- helpers ---------------------------------------------------------

    private static Skill skill(Long id, Long namespaceId, String slug, String displayName, String ownerId) {
        Skill skill = new Skill(namespaceId, slug, ownerId, SkillVisibility.PUBLIC);
        setField(skill, "id", id);
        skill.setDisplayName(displayName);
        skill.setLatestVersionId(100L);
        skill.setCreatedBy(ownerId);
        return skill;
    }

    private static Namespace namespace(Long id, String slug) {
        Namespace namespace = new Namespace(slug, slug, "system");
        namespace.setType(NamespaceType.GLOBAL);
        setField(namespace, "id", id);
        return namespace;
    }

    private static UserAccount user(String id, String displayName) {
        return userWithStatus(id, displayName, UserStatus.ACTIVE);
    }

    private static UserAccount userWithStatus(String id, String displayName, UserStatus status) {
        UserAccount user = new UserAccount(id, displayName, id + "@example.com", null);
        user.setStatus(status);
        return user;
    }

    private static String ownerIdOf(Skill skill) {
        try {
            Field field = Skill.class.getDeclaredField("ownerId");
            field.setAccessible(true);
            return (String) field.get(skill);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}