package dev.sunbirdrc.registry.service.impl;

import dev.sunbirdrc.pojos.RegistryLookup;
import dev.sunbirdrc.registry.service.RegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Lazy;

import java.util.Map;

@Service
public class RegistryLookupImpl implements RegistryLookup {
    private static final Logger logger = LoggerFactory.getLogger(RegistryLookupImpl.class);
    private final RegistryService registryService;
    private final ObjectMapper objectMapper;

    public RegistryLookupImpl(@Lazy RegistryService registryService) {
        this.registryService = registryService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean exists(String entityType, Map<String, String> conditions) {
        try {
            return registryService.exists(entityType, conditions);
        } catch (Exception e) {
            logger.error("Error checking existence in registry for entity type {} with conditions {}: {}",
                    entityType, conditions, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isUnique(String entityType, Map<String, String> conditions) {
        try {
            return registryService.isUnique(entityType, conditions);
        } catch (Exception e) {
            logger.error("Error checking uniqueness in registry for entity type {} with conditions {}: {}",
                    entityType, conditions, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean exists(String entityType, String field, String value) {
        try {
            return registryService.exists(entityType, field, value);
        } catch (Exception e) {
            logger.error("Error checking existence in registry for entity type {} with field {} and value {}: {}",
                    entityType, field, value, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean exists(JSONObject searchQuery) {
        try {
            // Convert JSONObject to JsonNode
            JsonNode searchNode = objectMapper.readTree(searchQuery.toString());
            return registryService.exists(searchNode);
        } catch (Exception e) {
            logger.error("Error checking existence in registry with search query {}: {}",
                    searchQuery, e.getMessage());
            return false;
        }
    }
}