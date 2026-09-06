package com.transdb.config;

import com.transdb.importer.ImportProperties;
import com.transdb.search.EsIndexAdminService;
import com.transdb.search.EsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({EsProperties.class, ImportProperties.class})
@RequiredArgsConstructor
public class AsyncSchedulingConfig {

    @Bean
    public ApplicationRunner esIndexInitializer(EsIndexAdminService esIndexAdminService) {
        return args -> esIndexAdminService.ensureIndices();
    }
}
