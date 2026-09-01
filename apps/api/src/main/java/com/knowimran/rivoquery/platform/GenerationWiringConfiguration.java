package com.knowimran.rivoquery.platform;

import com.knowimran.rivoquery.connection.application.lifecycle.CanonicalIdGenerator;
import com.knowimran.rivoquery.connection.application.ConnectionPermissionRepository;
import com.knowimran.rivoquery.catalog.application.SchemaQueryService;
import com.knowimran.rivoquery.catalog.application.SchemaSelectorService;
import com.knowimran.rivoquery.generation.SqlGenerationModel;
import com.knowimran.rivoquery.generation.application.GenerationService;
import com.knowimran.rivoquery.platform.generation.GenerationProperties;
import com.knowimran.rivoquery.platform.generation.adapter.OpenAiCompatibleGenerationAdapter;
import com.knowimran.rivoquery.querypolicy.JsqlParserSqlParser;
import com.knowimran.rivoquery.querypolicy.QuerySentry;
import com.knowimran.rivoquery.querypolicy.SqlParserPort;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(GenerationProperties.class)
public class GenerationWiringConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GenerationWiringConfiguration.class);

    @Bean
    SqlGenerationModel sqlGenerationModel(GenerationProperties properties, RestClient.Builder restClientBuilder) {
        String apiKey = properties.getAdapter().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("rivoquery.generation.adapter.api-key is not configured; LLM requests will be unauthenticated");
        }
        RestClient.Builder builder = restClientBuilder
                .baseUrl(properties.getAdapter().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                .defaultHeader("Content-Type", "application/json");
        return new OpenAiCompatibleGenerationAdapter(
                builder.build(),
                properties.getAdapter().getModel(),
                properties.getAdapter().getTimeout(),
                properties.getAdapter().getMaxRetries(),
                properties.getAdapter().getTemperature(),
                properties.getAdapter().getTopP(),
                properties.getPromptVersion()
        );
    }

    @Bean
    QuerySentry querySentry(SqlParserPort parser, CanonicalIdGenerator idGenerator) {
        return new QuerySentry(parser, "query-policy-v1", idGenerator);
    }

    @Bean
    SqlParserPort sqlParserPort() {
        return new JsqlParserSqlParser();
    }

    @Bean
    GenerationService generationService(
            SqlGenerationModel model,
            SchemaSelectorService schemaSelector,
            SchemaQueryService schemaQuery,
            QuerySentry querySentry,
            ConnectionPermissionRepository permissionRepository) {
        return new GenerationService(
                model,
                schemaSelector,
                schemaQuery,
                querySentry,
                permissionRepository,
                "query-policy-v1"
        );
    }
}