package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
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
import com.iflytek.skillhub.dto.AdminUserSummaryResponse;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.gitsync.store.GitSourceStore;
import com.iflytek.skillhub.repository.AdminSkillSearchRepository;
import com.iflytek.skillhub.repository.AdminSkillSearchRepository.Filter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Administrator console for skills: a platform-wide filtered list plus batch
 * ownership transfer and batch hard delete.
 *
 * <p>Unlike the public catalogue, this service does not apply viewer-scoped
 * visibility rules: SKILL_ADMIN/SUPER_ADMIN operate on every row, including
 * private, hidden and archived skills. That is the point of the console.
 */
@Service
public class AdminSkillAppService {

    /** Upper bound for a single batch request; larger lists are rejected as a whole. */
    static final int MAX_BATCH_SIZE = 200;
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminSkillSearchRepository searchRepository;
    private final SkillRepository skillRepository;
    private final NamespaceRepository namespaceRepository;
    private final NamespaceMemberRepository namespaceMemberRepository;
    private final UserAccountRepository userAccountRepository;
    private final SkillDeleteAppService skillDeleteAppService;
    private final GitSourceStore gitSourceStore;
    private final AdminUserAppService adminUserAppService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /**
     * Runs each batch item in its own transaction so one rejected skill neither
     * aborts the already-applied ones nor marks the batch rollback-only.
     */
    private final TransactionTemplate perSkillTransaction;

    public AdminSkillAppService(AdminSkillSearchRepository searchRepository,
                                SkillRepository skillRepository,
                                NamespaceRepository namespaceRepository,
                                NamespaceMemberRepository namespaceMemberRepository,
                                UserAccountRepository userAccountRepository,
                                SkillDeleteAppService skillDeleteAppService,
                                GitSourceStore gitSourceStore,
                                AdminUserAppService adminUserAppService,
                                AuditLogService auditLogService,
                                MessageSource messageSource,
                                ObjectMapper objectMapper,
                                Clock clock,
                                PlatformTransactionManager transactionManager) {
        this.searchRepository = searchRepository;
        this.skillRepository = skillRepository;
        this.namespaceRepository = namespaceRepository;
        this.namespaceMemberRepository = namespaceMemberRepository;
        this.userAccountRepository = userAccountRepository;
        this.skillDeleteAppService = skillDeleteAppService;
        this.gitSourceStore = gitSourceStore;
        this.adminUserAppService = adminUserAppService;
        this.auditLogService = auditLogService;
        this.messageSource = messageSource;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.perSkillTransaction = new TransactionTemplate(transactionManager);
        this.perSkillTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Raw query parameters of the console filter bar; all optional. */
    public record ListFilter(String keyword,
                             String namespaceSlug,
                             String status,
                             String visibility,
                             Boolean hidden,
                             String ownerId) {
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminSkillSummaryResponse> list(ListFilter filter, int page, int size) {
        int effectiveSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), effectiveSize);

        Filter queryFilter = new Filter(
                trimToNull(filter.keyword()),
                resolveNamespaceId(filter.namespaceSlug()),
                parseStatus(filter.status()),
                parseVisibility(filter.visibility()),
                filter.hidden(),
                trimToNull(filter.ownerId())
        );

        Page<Skill> skills = searchRepository.search(queryFilter, pageable);
        if (skills.isEmpty()) {
            return new PageResponse<>(List.of(), skills.getTotalElements(), skills.getNumber(), skills.getSize());
        }

        Map<Long, String> namespaceSlugs = namespaceRepository
                .findByIdIn(skills.getContent().stream().map(Skill::getNamespaceId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Namespace::getId, Namespace::getSlug));

        Map<String, String> ownerNames = userAccountRepository
                .findByIdIn(skills.getContent().stream().map(Skill::getOwnerId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(UserAccount::getId,
                        owner -> StringUtils.hasText(owner.getDisplayName()) ? owner.getDisplayName() : owner.getId(),
                        (left, right) -> left));

        List<AdminSkillSummaryResponse> items = skills.getContent().stream()
                .map(skill -> new AdminSkillSummaryResponse(
                        skill.getId(),
                        namespaceSlugs.get(skill.getNamespaceId()),
                        skill.getSlug(),
                        skill.getDisplayName(),
                        skill.getOwnerId(),
                        ownerNames.get(skill.getOwnerId()),
                        skill.getVisibility().name(),
                        skill.getStatus().name(),
                        skill.isHidden(),
                        skill.getLatestVersionId() != null,
                        skill.getDownloadCount() == null ? 0L : skill.getDownloadCount(),
                        skill.getCreatedAt(),
                        skill.getUpdatedAt()
                ))
                .toList();

        return new PageResponse<>(items, skills.getTotalElements(), skills.getNumber(), skills.getSize());
    }

    /**
     * Moves the selected skills to another author. Each row is validated and committed
     * on its own so a single rejected skill does not roll back the rest of the batch.
     */
    public AdminSkillBatchResultResponse transfer(List<Long> skillIds,
                                                 String toUserId,
                                                 String actorId,
                                                 AuditRequestContext auditContext) {
        List<Long> ids = normalizeIds(skillIds);
        if (!StringUtils.hasText(toUserId)) {
            throw new DomainBadRequestException("error.skill.transfer.targetRequired");
        }
        UserAccount target = userAccountRepository.findById(toUserId.trim())
                .orElseThrow(() -> new DomainBadRequestException("error.skill.transfer.targetNotFound"));
        if (target.getStatus() != UserStatus.ACTIVE) {
            throw new DomainBadRequestException("error.skill.transfer.targetNotActive");
        }

        Instant now = Instant.now(clock);
        List<Long> succeeded = new ArrayList<>();
        List<AdminSkillBatchResultResponse.Failure> failed = new ArrayList<>();

        for (Long skillId : ids) {
            try {
                perSkillTransaction.executeWithoutResult(
                        status -> transferOne(skillId, target, actorId, auditContext, now));
                succeeded.add(skillId);
            } catch (RuntimeException ex) {
                failed.add(new AdminSkillBatchResultResponse.Failure(skillId, describe(ex)));
            }
        }
        return new AdminSkillBatchResultResponse(succeeded, failed);
    }

    /**
     * Hard-deletes the selected skills together with their versions, files and
     * storage objects. Each deletion is audited and committed separately, so a
     * missing id in the middle of a batch does not undo the other deletions.
     */
    public AdminSkillBatchResultResponse delete(List<Long> skillIds,
                                                String actorId,
                                                AuditRequestContext auditContext) {
        List<Long> ids = normalizeIds(skillIds);
        List<Long> succeeded = new ArrayList<>();
        List<AdminSkillBatchResultResponse.Failure> failed = new ArrayList<>();

        for (Long skillId : ids) {
            try {
                perSkillTransaction.executeWithoutResult(
                        status -> skillDeleteAppService.deleteSkillById(skillId, actorId, auditContext));
                succeeded.add(skillId);
            } catch (RuntimeException ex) {
                failed.add(new AdminSkillBatchResultResponse.Failure(skillId, describe(ex)));
            }
        }
        return new AdminSkillBatchResultResponse(succeeded, failed);
    }

    /** Candidate authors for the transfer dialog, searched by username/email/id. */
    @Transactional(readOnly = true)
    public List<AdminUserSummaryResponse> transferCandidates(String search) {
        String normalized = trimToNull(search);
        return adminUserAppService.listUsers(normalized, UserStatus.ACTIVE.name(), 0, 10).items();
    }

    private void transferOne(Long skillId,
                             UserAccount target,
                             String actorId,
                             AuditRequestContext auditContext,
                             Instant now) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new DomainBadRequestException("error.skill.notFound", skillId));

        if (Objects.equals(skill.getOwnerId(), target.getId())) {
            throw new DomainBadRequestException("error.skill.transfer.sameOwner");
        }
        if (gitSourceStore.findLinkBySkillId(skillId).isPresent()) {
            // The git source publishes under its creator's identity, so the next sync
            // would re-create the skill under the old author (or fail on the name
            // conflict). Transferring the source itself is not supported yet.
            throw new DomainBadRequestException("error.skill.transfer.gitSynced");
        }
        namespaceMemberRepository.findByNamespaceIdAndUserId(skill.getNamespaceId(), target.getId())
                .orElseThrow(() -> new DomainBadRequestException("error.skill.transfer.targetNotMember"));
        skillRepository.findByNamespaceIdAndSlugAndOwnerId(skill.getNamespaceId(), skill.getSlug(), target.getId())
                .ifPresent(existing -> {
                    throw new DomainBadRequestException("error.skill.transfer.slugConflict");
                });

        String previousOwnerId = skill.getOwnerId();
        skill.transferOwnership(target.getId(), actorId, now);
        skillRepository.save(skill);
        skillRepository.flush();

        recordAudit(actorId, "TRANSFER_SKILL", skillId, auditContext,
                detailJson(Map.of(
                        "skillId", skillId,
                        "namespace", namespaceSlugOf(skill),
                        "slug", skill.getSlug(),
                        "fromOwnerId", previousOwnerId,
                        "toOwnerId", target.getId()
                )));
    }

    private String namespaceSlugOf(Skill skill) {
        return namespaceRepository.findById(skill.getNamespaceId())
                .map(Namespace::getSlug)
                .orElse(null);
    }

    private List<Long> normalizeIds(List<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            throw new DomainBadRequestException("error.skill.batch.ids.notEmpty");
        }
        LinkedHashSet<Long> unique = skillIds.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (unique.size() > MAX_BATCH_SIZE) {
            throw new DomainBadRequestException("error.skill.batch.tooMany", MAX_BATCH_SIZE);
        }
        if (unique.isEmpty()) {
            throw new DomainBadRequestException("error.skill.batch.ids.notEmpty");
        }
        return List.copyOf(unique);
    }

    private Long resolveNamespaceId(String namespaceSlug) {
        if (!StringUtils.hasText(namespaceSlug)) {
            return null;
        }
        return namespaceRepository.findBySlug(namespaceSlug.trim())
                .map(Namespace::getId)
                .orElseThrow(() -> new DomainBadRequestException("error.namespace.slug.notFound", namespaceSlug));
    }

    private static SkillStatus parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return SkillStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new DomainBadRequestException("error.skill.admin.status.invalid", status);
        }
    }

    private static SkillVisibility parseVisibility(String visibility) {
        if (!StringUtils.hasText(visibility)) {
            return null;
        }
        try {
            return SkillVisibility.valueOf(visibility.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new DomainBadRequestException("error.skill.admin.visibility.invalid", visibility);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void recordAudit(String actorId, String action, Long targetId,
                             AuditRequestContext auditContext, String detailJson) {
        auditLogService.record(
                actorId,
                action,
                "SKILL",
                targetId,
                MDC.get("requestId"),
                auditContext != null ? auditContext.clientIp() : null,
                auditContext != null ? auditContext.userAgent() : null,
                detailJson
        );
    }

    private String detailJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    /** Resolves a domain error code to the caller's language for the batch report. */
    private String describe(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (current instanceof com.iflytek.skillhub.domain.shared.exception.LocalizedMessage localized) {
            return messageSource.getMessage(localized.messageCode(), localized.messageArgs(),
                    localized.messageCode(), LocaleContextHolder.getLocale());
        }
        return StringUtils.hasText(current.getMessage()) ? current.getMessage() : current.getClass().getSimpleName();
    }
}