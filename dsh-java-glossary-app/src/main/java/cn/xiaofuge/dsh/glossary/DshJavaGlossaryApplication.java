package cn.xiaofuge.dsh.glossary;

import cn.xiaofuge.dsh.glossary.config.GlossaryAtlasProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GlossaryAtlasProperties.class)
public class DshJavaGlossaryApplication {

    public static void main(String[] args) {
        SpringApplication.run(DshJavaGlossaryApplication.class, args);
    }
}
