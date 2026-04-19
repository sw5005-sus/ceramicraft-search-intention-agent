package com.ceramicraft.search.intention.tools;

import com.ceramicraft.search.intention.config.PromptConfig;
import com.ceramicraft.search.intention.service.SearchHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SearchAgentToolsTest {

    private VectorStore vectorStore;
    private SearchHistoryService historyService;
    private SearchAgentTools tools;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        var promptConfig = new PromptConfig(null, 5, 0.75, true, "en");
        historyService = mock(SearchHistoryService.class);
        tools = new SearchAgentTools(vectorStore, promptConfig, historyService);
    }

    @Test
    void vectorSearch_returnsFormattedProducts() {
        Document doc = new Document("id1", "A fine celadon teacup", Map.of(
                "name", "Celadon Teacup",
                "category", "teaware",
                "price", "128",
                "material", "celadon",
                "style", "Chinese classical",
                "origin", "Jingdezhen",
                "tags", "teacup,celadon"
        ));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        String result = tools.vectorSearch("celadon teacup", 5);

        assertTrue(result.contains("Celadon Teacup"));
        assertTrue(result.contains("celadon"));
        assertTrue(result.contains("128"));
    }

    @Test
    void vectorSearch_emptyResults() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        String result = tools.vectorSearch("nonexistent", 5);
        assertEquals("No products found matching the query.", result);
    }

    @Test
    void getUserSearchHistory_returnsHistory() {
        when(historyService.getHistory("user1", 5)).thenReturn(Mono.just(List.of("teacup", "vase")));
        String result = tools.getUserSearchHistory("user1", 5);
        assertTrue(result.contains("teacup"));
        assertTrue(result.contains("vase"));
    }

    @Test
    void getUserSearchHistory_noUser() {
        String result = tools.getUserSearchHistory("", 5);
        assertTrue(result.contains("No user ID"));
    }

    @Test
    void getTrendingSearches_returnsTrending() {
        when(historyService.getHotSearches(10)).thenReturn(Mono.just(List.of("celadon", "teapot")));
        String result = tools.getTrendingSearches(10);
        assertTrue(result.contains("celadon"));
    }

    @Test
    void getTrendingSearches_empty() {
        when(historyService.getHotSearches(10)).thenReturn(Mono.just(List.of()));
        String result = tools.getTrendingSearches(10);
        assertTrue(result.contains("No trending"));
    }
}
