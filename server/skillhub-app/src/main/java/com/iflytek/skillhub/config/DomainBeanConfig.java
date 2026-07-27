package com.iflytek.skillhub.config;

import com.iflytek.skillhub.domain.skill.VisibilityChecker;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.validation.BasicPrePublishValidator;
import com.iflytek.skillhub.domain.skill.validation.NoOpPrePublishValidator;
import com.iflytek.skillhub.domain.skill.validation.PrePublishValidator;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires application-level Spring beans that adapt configurable infrastructure into domain-facing
 * ports.
 */
@Configuration
public class DomainBeanConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }

    @Bean
    public SkillMetadataParser skillMetadataParser() {
        return new SkillMetadataParser();
    }

    @Bean
    public SkillPackageValidator skillPackageValidator(SkillMetadataParser skillMetadataParser,
                                                       SkillPublishProperties skillPublishProperties) {
        return new SkillPackageValidator(
                skillMetadataParser,
                skillPublishProperties.getMaxFileCount(),
                skillPublishProperties.getMaxSingleFileSize(),
                skillPublishProperties.getMaxPackageSize(),
                skillPublishProperties.getAllowedFileExtensions()
        );
    }

    @Bean
    public VisibilityChecker visibilityChecker() {
        return new VisibilityChecker();
    }

    @Bean
    @ConditionalOnProperty(
            name = "skillhub.prepublish-validation.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public PrePublishValidator basicPrePublishValidator() {
        return new BasicPrePublishValidator();
    }

    @Bean
    @ConditionalOnMissingBean(PrePublishValidator.class)
    public PrePublishValidator noOpPrePublishValidator() {
        return new NoOpPrePublishValidator();
    }
}
