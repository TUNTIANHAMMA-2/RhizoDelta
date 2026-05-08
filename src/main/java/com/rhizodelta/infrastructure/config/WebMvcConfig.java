package com.rhizodelta.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final String localStoragePath;

    public WebMvcConfig(
            @Value("${rhizodelta.avatar.local-storage-path:./data/avatars}") String localStoragePath
    ) {
        this.localStoragePath = localStoragePath;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/api/files/avatars/**")
                .addResourceLocations("file:" + localStoragePath + "/");
    }
}
