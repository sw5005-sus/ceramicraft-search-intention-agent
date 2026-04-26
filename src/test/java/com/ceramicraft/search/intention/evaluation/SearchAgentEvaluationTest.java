package com.ceramicraft.search.intention.evaluation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI output quality evaluation tests using Spring AI's built-in evaluators.
 * <p>
 * These tests call a real LLM to judge output quality, similar to DeepEval.
 * They only run when CI_OPENAI_API_KEY environment variable is set.
 * </p>
 *
 * <h3>Evaluators used:</h3>
 * <ul>
 *   <li><b>RelevancyEvaluator</b> — Is the response relevant to the user query? (like DeepEval AnswerRelevancy)</li>
 *   <li><b>FactCheckingEvaluator</b> — Is the response grounded in the provided context? (like DeepEval Faithfulness)</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "CI_OPENAI_API_KEY", matches = ".+")
@DisplayName("AI Output Quality Evaluation")
class SearchAgentEvaluationTest {

    private static ChatClient.Builder chatClientBuilder;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("CI_OPENAI_API_KEY");
        var api = OpenAiApi.builder().apiKey(apiKey).build();
        var chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("gpt-4o").temperature(0.0).build())
                .build();
        chatClientBuilder = ChatClient.builder(chatModel);
    }

    @Test
    @DisplayName("RAG recommendation should be relevant to user query")
    void ragRecommendation_shouldBeRelevant() {
        String userQuery = "premium celadon teacup as gift for elders";
        String aiResponse = "For a premium gift, I'd recommend the Jingdezhen Celadon Teacup (SGD 85.00) — "
                + "its traditional craftsmanship and elegant glaze make it perfect for elders. "
                + "The Longquan Celadon Tea Set (SGD 120.00) is also excellent if budget allows.";

        var evaluator = new RelevancyEvaluator(chatClientBuilder);
        var request = new EvaluationRequest(userQuery, aiResponse);
        EvaluationResponse result = evaluator.evaluate(request);

        assertTrue(result.isPass(),
                "RAG recommendation should be relevant. Feedback: " + result.getFeedback());
    }

    @Test
    @DisplayName("RAG recommendation should be grounded in retrieved product data")
    void ragRecommendation_shouldBeFactual() {
        List<Document> retrievedProducts = List.of(
                new Document("Jingdezhen Celadon Teacup — handmade, traditional glaze, SGD 85.00",
                        Map.of("name", "Jingdezhen Celadon Teacup", "price", "8500", "material", "celadon")),
                new Document("Longquan Celadon Tea Set — includes teapot and 6 cups, SGD 120.00",
                        Map.of("name", "Longquan Celadon Tea Set", "price", "12000", "material", "celadon"))
        );
        String aiResponse = "For a premium gift, I'd recommend the Jingdezhen Celadon Teacup (SGD 85.00) — "
                + "its traditional craftsmanship and elegant glaze make it perfect for elders. "
                + "The Longquan Celadon Tea Set (SGD 120.00) is also excellent if budget allows.";

        var evaluator = new FactCheckingEvaluator(chatClientBuilder);
        var request = new EvaluationRequest(retrievedProducts, aiResponse);
        EvaluationResponse result = evaluator.evaluate(request);

        assertTrue(result.isPass(),
                "Recommendation should be grounded in retrieved products. Feedback: " + result.getFeedback());
    }

    @Test
    @DisplayName("Hallucinated recommendation should fail fact checking")
    void hallucination_shouldFailFactCheck() {
        List<Document> retrievedProducts = List.of(
                new Document("Blue-and-white bowl — Chinese classical style, SGD 35.00",
                        Map.of("name", "Blue-and-white bowl", "price", "3500"))
        );
        String hallucinatedResponse = "I recommend the Royal Doulton Fine China Set (SGD 500.00) — "
                + "a premium British porcelain collection perfect for formal dining. "
                + "Also consider the Wedgwood Jasperware Vase (SGD 350.00).";

        var evaluator = new FactCheckingEvaluator(chatClientBuilder);
        var request = new EvaluationRequest(retrievedProducts, hallucinatedResponse);
        EvaluationResponse result = evaluator.evaluate(request);

        assertFalse(result.isPass(),
                "Hallucinated products should fail fact checking. Feedback: " + result.getFeedback());
    }

    @Test
    @DisplayName("Intent parsing JSON should be relevant to ceramic query")
    void intentParsing_shouldBeRelevant() {
        String userQuery = "200 SGD budget celadon teacup for daily use";
        String intentJson = """
                {"category":"teacup","priceRange":{"min":0,"max":200},"material":"celadon","style":null,"keywords":["celadon","teacup","daily use"],"occasion":"daily use","confidence":0.9}""";

        var evaluator = new RelevancyEvaluator(chatClientBuilder);
        var request = new EvaluationRequest(userQuery, intentJson);
        EvaluationResponse result = evaluator.evaluate(request);

        assertTrue(result.isPass(),
                "Intent JSON should be relevant to the query. Feedback: " + result.getFeedback());
    }

    @Test
    @DisplayName("Off-topic response should fail relevancy check")
    void offTopicResponse_shouldFailRelevancy() {
        String userQuery = "celadon teacup for gift";
        String offTopicResponse = "The weather in Tokyo is sunny today with a high of 25 degrees. "
                + "Tomorrow will be partly cloudy with a chance of rain.";

        var evaluator = new RelevancyEvaluator(chatClientBuilder);
        var request = new EvaluationRequest(userQuery, offTopicResponse);
        EvaluationResponse result = evaluator.evaluate(request);

        assertFalse(result.isPass(),
                "Off-topic response should fail relevancy. Feedback: " + result.getFeedback());
    }
}
