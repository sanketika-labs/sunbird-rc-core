package dev.sunbirdrc.pojos;

import java.util.Map;
import org.json.JSONObject;

public interface RegistryLookup {
    boolean exists(String entityType, Map<String, String> conditions);
    boolean isUnique(String entityType, Map<String, String> conditions);
    boolean exists(String entityType, String field, String value);
    boolean exists(JSONObject searchQuery);
}