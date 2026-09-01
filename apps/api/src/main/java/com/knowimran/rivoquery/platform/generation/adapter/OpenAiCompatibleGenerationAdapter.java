package com.knowimran.rivoquery.platform.generation.adapter;

import com.knowimran.rivoquery.generation.AmbiguityStatus;
import com.knowimran.rivoquery.generation.GenerationRequest;
import com.knowimran.rivoquery.generation.GenerationResponse;
import com.knowimran.rivoquery.generation.SqlGenerationModel;
import com.knowimran.rivoquery.generation.SqlGenerationModel.GenerationException;
import com.knowimran.rivoquery.generation.SqlProposal;
import com.knowimran.rivoquery.generation.adapter.PromptBuilder;
import com.knowimran.rivoquery.generation.adapter.StructuredResponseParser;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleGenerationAdapter implements SqlGenerationModel {

    private final RestClient restClient;
    private final String model;
    private final Duration timeout;
    private final int maxRetries;
    private final double temperature;
    private final double topP;
    private final String promptVersion;

    public OpenAiCompatibleGenerationAdapter(
            RestClient restClient,
            String model,
            Duration timeout,
            int maxRetries,
            double temperature,
            double topP,
            String promptVersion) {
        this.restClient = restClient;
        this.model = model;
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.temperature = temperature;
        this.topP = topP;
        this.promptVersion = promptVersion;
    }

    @Override
    public GenerationResponse generate(GenerationRequest request) throws GenerationException {
        long start = Instant.now().toEpochMilli();
        String prompt = PromptBuilder.build(request);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", temperature,
                "top_p", topP,
                "max_tokens", 1024,
                "stream", false
        );

        int attempt = 0;
        while (true) {
            try {
                Map<?, ?> response = restClient.post()
                        .uri("/chat/completions")
                        .body(body)
                        .retrieve()
                        .body(Map.class);

                SqlProposal proposal = parseResponse(response);
                long latencyMs = Instant.now().toEpochMilli() - start;

                return new GenerationResponse(
                        AmbiguityStatus.READY,
                        proposal,
                        promptVersion,
                        model,
                        latencyMs
                );
            } catch (RestClientException e) {
                attempt++;
                if (attempt >= maxRetries) {
                    throw new GenerationException("model generation failed after " + attempt + " attempts: " + e.getMessage(), e);
                }
            }
        }
    }

    static SqlProposal parseResponse(Map<?, ?> response) throws GenerationException {
        if (response == null || !response.containsKey("choices")) {
            throw new GenerationException("invalid response from model provider");
        }

        List<?> choices = (List<?>) response.get("choices");
        if (choices.isEmpty()) {
            throw new GenerationException("empty choices from model provider");
        }

        Map<?, ?> choice = (Map<?, ?>) choices.get(0);
        Map<?, ?> message = (Map<?, ?>) choice.get("message");
        String content = (String) message.get("content");

        try {
            return StructuredResponseParser.parse(content);
        } catch (StructuredResponseParser.ParseException e) {
            throw new GenerationException("failed to parse model response: " + e.getMessage(), e);
        }
    }
}