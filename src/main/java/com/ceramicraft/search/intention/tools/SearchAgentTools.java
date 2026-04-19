package com.ceramicraft.search.intention.tools;

import com.ceramicraft.search.intention.config.PromptConfig;
import com.ceramicraft.search.intention.service.SearchHistoryService;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SearchAgentTools {

    private final VectorStore vectorStore;
    private final PromptConfig promptConfig;
    private final SearchHistoryService historyService;

    public SearchAgentTools(@Lazy VectorStore vectorStore,
                            PromptConfig promptConfig,
                            SearchHistoryService historyService) {
        this.vectorStore = vectorStore;
        this.promptConfig = promptConfig;
        this.historyService = historyService;
    }

    @Tool(description = "Search ceramic products by semantic similarity in the vector database. "
            + "Use this when you need to find products matching a user query. "
            + "Returns product name, category, price, material, style, tags, and relevance score.")
    public String vectorSearch(
            @ToolParam(description = "Search query, e.g. 'celadon teacup for gift'") String query,
            @ToolParam(description = "Max number of results to return, between 1 and 20") int topK) {
        var request = SearchRequest.builder()
                .query(query)
                .topK(Math.min(Math.max(topK, 1), 20))
                .similarityThreshold(promptConfig.similarityThreshold())
                .build();
        List<Document> docs = vectorStore.similaritySearch(request);
        if (docs.isEmpty()) {
            return "No products found matching the query.";
        }
        return docs.stream()
                .map(doc -> {
                    var m = doc.getMetadata();
                    String priceDisplay = formatPrice(m.getOrDefault("price", "0"));
                    return "Product: %s | Category: %s | Price: $%s | Material: %s | Style: %s | Origin: %s | Tags: %s | Score: %.2f | Description: %s"
                            .formatted(
                                    m.getOrDefault("name", ""),
                                    m.getOrDefault("category", ""),
                                    priceDisplay,
                                    m.getOrDefault("material", ""),
                                    m.getOrDefault("style", ""),
                                    m.getOrDefault("origin", ""),
                                    m.getOrDefault("tags", ""),
                                    doc.getScore() != null ? doc.getScore() : 0.0,
                                    doc.getText());
                })
                .collect(Collectors.joining("\n"));
    }

    private static String formatPrice(Object rawPrice) {
        try {
            int cents = Integer.parseInt(rawPrice.toString());
            return "%.2f".formatted(cents / 100.0);
        } catch (NumberFormatException e) {
            return rawPrice.toString();
        }
    }

    @Tool(description = "Get a user's recent search history. "
            + "Use this to understand user preferences for personalized recommendations. "
            + "Returns a list of recent search keywords.")
    public String getUserSearchHistory(
            @ToolParam(description = "User ID") String userId,
            @ToolParam(description = "Number of recent history items, between 1 and 20") int limit) {
        if (userId == null || userId.isBlank()) {
            return "No user ID provided, cannot retrieve search history.";
        }
        List<String> history = historyService.getHistory(userId, Math.min(limit, 20)).block();
        if (history == null || history.isEmpty()) {
            return "No search history available for this user.";
        }
        return "Recent searches: " + String.join(", ", history);
    }

    @Tool(description = "Get platform-wide trending search keywords. "
            + "Use this when you need to know what is popular right now, "
            + "especially for users with no search history.")
    public String getTrendingSearches(
            @ToolParam(description = "Number of trending keywords to return") int limit) {
        List<String> hot = historyService.getHotSearches(Math.min(limit, 30)).block();
        if (hot == null || hot.isEmpty()) {
            return "No trending search data available.";
        }
        return "Trending searches: " + String.join(", ", hot);
    }

    @Tool(description = "Find products similar to a given product. "
            + "Use this when the user asks for alternatives or 'more like this'.")
    public String findSimilarProducts(
            @ToolParam(description = "Product name to find similar products for") String productName,
            @ToolParam(description = "Number of similar products to return") int limit) {
        var request = SearchRequest.builder()
                .query(productName)
                .topK(Math.min(limit, 20) + 1)
                .similarityThreshold(promptConfig.similarityThreshold())
                .build();
        List<Document> docs = vectorStore.similaritySearch(request);
        return docs.stream()
                .map(doc -> {
                    var m = doc.getMetadata();
                    return "Product: %s | Price: $%s | Material: %s | Style: %s"
                            .formatted(
                                    m.getOrDefault("name", ""),
                                    formatPrice(m.getOrDefault("price", "0")),
                                    m.getOrDefault("material", ""),
                                    m.getOrDefault("style", ""));
                })
                .filter(line -> !line.contains(productName))
                .limit(limit)
                .collect(Collectors.joining("\n"));
    }
}
