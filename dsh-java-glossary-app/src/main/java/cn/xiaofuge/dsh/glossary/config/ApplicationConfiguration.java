package cn.xiaofuge.dsh.glossary.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GlossaryAtlasProperties.class)
public class ApplicationConfiguration {
}
