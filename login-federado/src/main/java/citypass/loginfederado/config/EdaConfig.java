package citypass.loginfederado.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({EdaProperties.class, EdaOutboxProperties.class})
public class EdaConfig {
}