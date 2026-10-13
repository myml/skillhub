package com.iflytek.skillhub.dto;

/**
 * One entry of the most-downloaded public skills ranking.
 */
public record SkillDownloadTopItemResponse(Long skillId,
                                           String namespace,
                                           String slug,
                                           String displayName,
                                           long count) {
}
