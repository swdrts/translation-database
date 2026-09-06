package com.transdb.config;

import com.transdb.search.EsIndexAdminService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncSchedulingConfig {

    @Bean
    public ApplicationRunner esIndexInitializer(EsIndexAdminService esIndexAdminService) {
        return args -> esIndexAdminService.ensureIndices();
    }
}
