package in.sandesh.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sandeshOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Sandesh API")
                .version("0.1.0")
                .description("Site messenger for Nirman. Tokens are issued by Nirman, not here."));
    }
}
