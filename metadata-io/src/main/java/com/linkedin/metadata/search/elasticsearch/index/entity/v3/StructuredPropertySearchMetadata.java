package com.linkedin.metadata.search.elasticsearch.index.entity.v3;

import static com.linkedin.metadata.models.StructuredPropertyUtils.getLogicalValueType;
import static com.linkedin.metadata.models.StructuredPropertyUtils.toElasticsearchFieldName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkedin.common.urn.Urn;
import com.linkedin.datahub.DataHubSearchConfig;
import com.linkedin.metadata.models.LogicalValueType;
import com.linkedin.structured.StructuredPropertyDefinition;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Search V3 metadata projected onto structured-property entity documents. This is the query-time
 * projection of {@link DataHubSearchConfig}; arbitrary configuration maps are intentionally not
 * indexed.
 */
public final class StructuredPropertySearchMetadata {

  public static final String FIELD = "_structuredPropertySearch";
  public static final String ENABLED = "enabled";
  public static final String FIELD_PATH = "fieldPath";
  public static final String QUERY_BY_DEFAULT = "queryByDefault";
  public static final String TEXT_WEIGHT = "textWeight";
  public static final String STEMMED_WEIGHT = "stemmedWeight";

  public static final float DEFAULT_TEXT_WEIGHT = 0.8f;
  public static final float STEMMED_FACTOR = 0.5f;
  public static final String STEMMED = "stemmed";

  private StructuredPropertySearchMetadata() {}

  @Nonnull
  public static ObjectNode project(
      @Nonnull Urn propertyUrn, @Nonnull StructuredPropertyDefinition definition) {
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    if (!isEnabled(definition)) {
      metadata.put(ENABLED, false);
      return metadata;
    }

    DataHubSearchConfig config = definition.getSearchConfiguration();
    float textWeight = textWeight(config);
    metadata.put(ENABLED, true);
    metadata.put(
        FIELD_PATH,
        V3SearchFields.STRUCTURED_PROPERTIES
            + "."
            + toElasticsearchFieldName(propertyUrn, definition));
    metadata.put(QUERY_BY_DEFAULT, config.isQueryByDefault());
    metadata.put(TEXT_WEIGHT, textWeight);
    metadata.put(STEMMED_WEIGHT, stemmedWeight(config, textWeight));
    return metadata;
  }

  @Nonnull
  public static Map<String, Object> mapping() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(ENABLED, Map.of("type", "boolean"));
    properties.put(FIELD_PATH, Map.of("type", "keyword"));
    properties.put(QUERY_BY_DEFAULT, Map.of("type", "boolean"));
    properties.put(TEXT_WEIGHT, Map.of("type", "double"));
    properties.put(STEMMED_WEIGHT, Map.of("type", "double"));
    return Map.of("type", "object", "dynamic", false, "properties", properties);
  }

  public static boolean isEnabled(@Nonnull StructuredPropertyDefinition definition) {
    if (!definition.hasSearchConfiguration()) {
      return false;
    }
    LogicalValueType logicalValueType = getLogicalValueType(definition.getValueType());
    return logicalValueType == LogicalValueType.STRING
        || logicalValueType == LogicalValueType.RICH_TEXT
        || logicalValueType == LogicalValueType.URN;
  }

  public static float textWeight(@Nonnull DataHubSearchConfig config) {
    if (config.data().containsKey("boostScore")) {
      return config.getBoostScore().floatValue();
    }
    return DEFAULT_TEXT_WEIGHT;
  }

  public static float stemmedWeight(@Nonnull DataHubSearchConfig config, final float textWeight) {
    if (config.hasSubfieldBoostScores() && config.getSubfieldBoostScores().containsKey(STEMMED)) {
      return config.getSubfieldBoostScores().get(STEMMED).floatValue();
    }
    return textWeight * STEMMED_FACTOR;
  }

  @Nonnull
  public static IndexedMetadata parse(@Nonnull JsonNode metadata) {
    return new IndexedMetadata(
        metadata.path(FIELD_PATH).asText(),
        metadata.path(QUERY_BY_DEFAULT).asBoolean(false),
        (float) metadata.path(TEXT_WEIGHT).asDouble(DEFAULT_TEXT_WEIGHT),
        (float)
            metadata
                .path(STEMMED_WEIGHT)
                .asDouble(
                    metadata.path(TEXT_WEIGHT).asDouble(DEFAULT_TEXT_WEIGHT) * STEMMED_FACTOR));
  }

  public record IndexedMetadata(
      @Nonnull String fieldPath, boolean queryByDefault, float textWeight, float stemmedWeight) {}
}
