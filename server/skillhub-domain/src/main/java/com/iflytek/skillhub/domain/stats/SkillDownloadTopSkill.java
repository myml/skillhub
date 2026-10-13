package com.iflytek.skillhub.domain.stats;

/**
 * A public skill ranked by download count over a period.
 */
public record SkillDownloadTopSkill(Long skillId,
                                    String namespaceSlug,
                                    String skillSlug,
                                    String displayName,
                                    long count) {
}
