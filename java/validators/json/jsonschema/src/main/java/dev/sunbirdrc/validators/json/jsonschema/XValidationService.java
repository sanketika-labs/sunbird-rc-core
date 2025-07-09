package dev.sunbirdrc.validators.json.jsonschema;

import dev.sunbirdrc.registry.middleware.MiddlewareHaltException;
import dev.sunbirdrc.pojos.RegistryLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONObject;
import org.everit.json.schema.Schema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONArray;

import java.time.LocalDate;
import java.time.Period;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class XValidationService {
    private static final Logger logger = LoggerFactory.getLogger(XValidationService.class);
    private final RegistryLookup registryLookup;
    private final ObjectMapper objectMapper;

    public XValidationService(RegistryLookup registryLookup) {
        this.registryLookup = registryLookup;
        this.objectMapper = new ObjectMapper();
    }

    public void validate(Schema schema, JSONObject data) throws MiddlewareHaltException {
        // Get the raw schema JSON from the Schema object
        JSONObject schemaJson = new JSONObject(schema.toString());
        JSONObject osConfig = schemaJson.getJSONObject("_osConfig");
        if (!osConfig.has("x-validation")) {
            return;
        }

        JSONObject xValidationNode = osConfig.getJSONObject("x-validation");
        Iterator<String> keys = xValidationNode.keys();

        while (keys.hasNext()) {
            String ruleName = keys.next();
            JSONObject ruleNode = xValidationNode.getJSONObject(ruleName);
            String ruleExpression = ruleNode.getString("rule");
            String errMsg = ruleNode.has("errMessage") ? ruleNode.getString("errMessage") : "error while validating rule";
            try {
                if (!validateRule(ruleExpression, data)) {
                    throw new MiddlewareHaltException(String.format(errMsg));
                }
            } catch (Exception e) {
                logger.error("Error validating rule {}: {}", ruleName, e.getMessage());
                throw new MiddlewareHaltException(String.format(errMsg));
            }
        }
    }

    private boolean validateRule(String ruleExpression, JSONObject data) throws Exception {
        if (ruleExpression.contains("existsInRegistry")) {
            return validateRegistryExistence(ruleExpression, data);
        } else if (ruleExpression.contains("isUniqueInRegistry")) {
            return validateRegistryUniqueness(ruleExpression, data);
        }
        else if(ruleExpression.contains("validDateOfBirth")) {
            return validDateOfBirth(ruleExpression, data);
        } else {
            return validateEquality(ruleExpression, data);
        }
    }

    private boolean validateEquality(String ruleExpression, JSONObject data) throws Exception {
        String[] parts = ruleExpression.split("==");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid equality rule format");
        }

        String leftExpr = parts[0].trim();
        String rightExpr = parts[1].trim();

        // Handle concatenation in right expression
        if (rightExpr.contains("+")) {
            String[] rightParts = rightExpr.split("\\+");
            StringBuilder concatenated = new StringBuilder();
            for (String part : rightParts) {
                String field = part.trim();
                if (data.has(field)) {
                    concatenated.append(data.get(field));
                } else {
                    throw new IllegalArgumentException("Field not found: " + field);
                }
            }
            rightExpr = concatenated.toString();
        } else if (data.has(rightExpr)) {
            // If right side is a field reference, get its value
            rightExpr = data.get(rightExpr).toString();
        }

        String leftValue = data.has(leftExpr) ? data.get(leftExpr).toString() : "";
        return leftValue.equals(rightExpr);
    }

    private boolean validateRegistryExistence(String ruleExpression, JSONObject data) throws Exception {
        String[] parts = ruleExpression.split("'");
        String entityType;
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid registry existence rule format");
        }

        entityType = parts[1];


        if (ruleExpression.contains("{")) {
            int startIndex = ruleExpression.indexOf('{');
            int endIndex = ruleExpression.lastIndexOf('}');
            if (startIndex == -1 || endIndex == -1) {
                throw new IllegalArgumentException("Invalid multiple fields format");
            }

            String conditionsStr = ruleExpression.substring(startIndex + 1, endIndex).trim();
            Map<String, String> conditions = parseConditions(conditionsStr, data);

            // Create a search query JSON for multiple fields
            JSONObject searchQuery = new JSONObject();
            searchQuery.put("entityType", new JSONArray().put(entityType));

            JSONObject filters = new JSONObject();
            for (Map.Entry<String, String> entry : conditions.entrySet()) {
                JSONObject eqOperator = new JSONObject();
                eqOperator.put("eq", entry.getValue());
                filters.put(entry.getKey(), eqOperator);
            }
            searchQuery.put("filters", filters);
            return registryLookup.exists(searchQuery);
        } else {
            // Single field case
            String regex = "existsInRegistry\\('([^']+)',\\s*'([^']+)',\\s*([^\\)]+)\\)";
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(ruleExpression);

            if (!matcher.find()) {
                throw new IllegalArgumentException("Invalid single field format");
            }

            entityType = matcher.group(1);
            String field = matcher.group(2);
            String valueField = matcher.group(3).trim();

            Object valueObj = getValueByPath(data, valueField);
            if (valueObj == null) {
                throw new IllegalArgumentException("Field not found: " + valueField);
            }
            String value = valueObj.toString();
            JSONObject searchQuery = new JSONObject();
            searchQuery.put("entityType", new JSONArray().put(entityType));

            JSONObject filters = new JSONObject();
            JSONObject eqOperator = new JSONObject();
            eqOperator.put("eq", value);
            filters.put(field, eqOperator);
            searchQuery.put("filters", filters);
            return registryLookup.exists(searchQuery);
        }
    }
    private boolean validateRegistryUniqueness(String ruleExpression, JSONObject data) throws Exception {
        // Use regex to extract entity type and conditions map
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^isUniqueInRegistry\\('([^']+)',\\s*\\{(.+)}\\)$");
        java.util.regex.Matcher matcher = pattern.matcher(ruleExpression);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid registry uniqueness rule format");
        }
        String entityType = matcher.group(1);
        String conditionsStr = matcher.group(2).trim();
        Map<String, String> conditions = parseConditions(conditionsStr, data);
        return registryLookup.isUnique(entityType, conditions);
    }

    private boolean validDateOfBirth(String ruleExpression, JSONObject data) throws Exception {
        // validates Date of Birth : age should be greater than 5 and less than 25
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^validDateOfBirth\\(\\s*'([^']+)'\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)$");
        java.util.regex.Matcher matcher = pattern.matcher(ruleExpression);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid date of birth validation rule format");
        }
        String dobField = matcher.group(1).trim();
        int minAge = Integer.parseInt(matcher.group(2));
        int maxAge = Integer.parseInt(matcher.group(3));

        if(!data.has(dobField)) {
            throw new MiddlewareHaltException("Date of Birth field not found: " + dobField);
        }
        String dobString = data.getString(dobField);
        try{
            LocalDate dob = LocalDate.parse(dobString);
            LocalDate today = LocalDate.now();
            int age= Period.between(dob, today).getYears();
            return age >= minAge && age <= maxAge;
        }
        catch (java.time.format.DateTimeParseException e) {
            logger.error("Invalid date format for Date of Birth: {}", dobString);
            return false;
        }
    }

    private Object getValueByPath(JSONObject data, String path) {
        String[] parts = path.split("\\.");
        Object current = data;
        for (String part : parts) {
            if (current instanceof JSONObject) {
                JSONObject currentObj = (JSONObject) current;
                if (!currentObj.has(part)) {
                    return null;
                }
                current = currentObj.get(part);
            } else {
                return null;
            }
        }
        return current;
    }
    private Map<String, String> parseConditions(String conditionsStr, JSONObject data) throws Exception {
        Map<String, String> conditions = new HashMap<>();
        String[] conditionPairs = conditionsStr.split(",\\s*");  // Split by comma followed by optional whitespace
        for (String pair : conditionPairs) {
            String[] keyValue = pair.split(":\\s*");  // Split by colon followed by optional whitespace
            if (keyValue.length != 2) {
                throw new IllegalArgumentException("Invalid condition pair format: " + pair);
            }

            String field = keyValue[0].trim().replaceAll("'", "");
            String valueField = keyValue[1].trim();

            Object valueObj = getValueByPath(data, valueField);
            if (valueObj == null) {
                throw new IllegalArgumentException("Field not found: " + valueField);
            }
            conditions.put(field, valueObj.toString());
        }
        return conditions;
    }
}