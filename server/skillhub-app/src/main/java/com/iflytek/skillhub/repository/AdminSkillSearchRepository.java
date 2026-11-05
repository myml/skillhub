package com.iflytek.skillhub.repository;

import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pageable, multi-filter search over every skill on the platform for the
 * administrator skill console.
 *
 * <p>The console must operate on the complete table (including private, hidden
 * and archived skills), so this query deliberately bypasses the viewer-scoped
 * visibility rules that {@code VisibilityChecker} applies to the public pages.
 */
@Repository
public class AdminSkillSearchRepository {

    private final EntityManager entityManager;

    public AdminSkillSearchRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Filter bundle for the admin list. Every field is optional; {@code null} means
     * "no restriction".
     */
    public record Filter(String keyword,
                         Long namespaceId,
                         SkillStatus status,
                         SkillVisibility visibility,
                         Boolean hidden,
                         String ownerId) {
    }

    public Page<Skill> search(Filter filter, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();

        CriteriaQuery<Skill> query = builder.createQuery(Skill.class);
        Root<Skill> root = query.from(Skill.class);
        query.select(root)
                .where(buildPredicates(filter, builder, root).toArray(Predicate[]::new))
                .orderBy(builder.desc(root.get("updatedAt")), builder.desc(root.get("id")));

        TypedQuery<Skill> typedQuery = entityManager.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        List<Skill> skills = typedQuery.getResultList();

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<Skill> countRoot = countQuery.from(Skill.class);
        countQuery.select(builder.count(countRoot))
                .where(buildPredicates(filter, builder, countRoot).toArray(Predicate[]::new));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(skills, pageable, total);
    }

    private List<Predicate> buildPredicates(Filter filter, CriteriaBuilder builder, Root<Skill> root) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter == null) {
            return predicates;
        }
        if (StringUtils.hasText(filter.keyword())) {
            String normalized = "%" + filter.keyword().trim().toLowerCase(Locale.ROOT) + "%";
            predicates.add(builder.or(
                    builder.like(builder.lower(root.get("slug")), normalized),
                    builder.like(builder.lower(root.get("displayName")), normalized)
            ));
        }
        if (filter.namespaceId() != null) {
            predicates.add(builder.equal(root.get("namespaceId"), filter.namespaceId()));
        }
        if (filter.status() != null) {
            predicates.add(builder.equal(root.get("status"), filter.status()));
        }
        if (filter.visibility() != null) {
            predicates.add(builder.equal(root.get("visibility"), filter.visibility()));
        }
        if (filter.hidden() != null) {
            predicates.add(builder.equal(root.get("hidden"), filter.hidden()));
        }
        if (StringUtils.hasText(filter.ownerId())) {
            predicates.add(builder.equal(root.get("ownerId"), filter.ownerId().trim()));
        }
        return predicates;
    }
}