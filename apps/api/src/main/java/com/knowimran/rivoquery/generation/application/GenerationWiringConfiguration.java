package com.knowimran.rivoquery.generation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import com.knowimran.rivoquery.generation.adapter.OpenAiCompatibleGenerationAdapter;
import com.knowimran.rivoquery.generation.SqlGenerationModel;
import com.knowimran.rivoquery.querypolicy.QuerySentry;
import com.knowimran.rivoquery.querypolicy.JsqlParserSqlParser;
import com.knowimran.rivoquery.querypolicy.SqlParserPort;

@Configuration
@EnableConfigurationProperties(GenerationProperties.class)
public class GenerationWiringConfiguration {

    @Bean
    SqlGenerationModel sqlGenerationModel(GenerationProperties properties, RestClient.Builder restClientBuilder) {
        RestClient restClient = restClientBuilder
                .baseUrl(properties.getAdapter().getBaseUrl())
                .build();
        return new OpenAiCompatibleGenerationAdapter(
                restClient,
                properties.getAdapter().getModel(),
                properties.getAdapter().getTimeout(),
                properties.getAdapter().getMaxRetries(),
                properties.getAdapter().getTemperature(),
                properties.getAdapter().getTopP(),
                properties.getPromptVersion()
        );
    }

    @Bean
    QuerySentry querySentry(SqlParserPort parser) {
        return new QuerySentry(parser, "query-policy-v1");
    }

    @Bean
    GenerationService generationService(
            SqlGenerationModel model,
            com.knowimran.rivoquery.catalog.application.SchemaSelectorService schemaSelector,
            com.knowimran.rivoquery.catalog.application.SchemaQueryService schemaQuery,
            QuerySentry querySentry,
            com.knowimran.rivoquery.connection.application.ConnectionPermissionRepository permissionRepository) {
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
