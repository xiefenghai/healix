package com.healix.agent.rag;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Long-term memory / knowledge retrieval via pgvector.
 * Scaffold: returns empty until vector store wiring is completed.
 */
@Slf4j
@Component
public class KnowledgeRetriever {

    public List<String> retrieve(String query, int topK) {
        log.debug("RAG retrieve stub: query={}, topK={}", query, topK);
        return List.of();
    }
}
