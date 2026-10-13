package com.iflytek.skillhub.domain.stats;

import java.time.LocalDate;

/**
 * One point of the daily download series.
 */
public record SkillDownloadDailyPoint(LocalDate day, long count) {
}
