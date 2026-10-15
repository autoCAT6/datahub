package com.linkedin.metadata.search.elasticsearch.query.request;

import static com.linkedin.metadata.models.StructuredPropertyUtils.entityTypeMatches;
import static com.linkedin.metadata.models.StructuredPropertyUtils.getLogicalValueType;
import static com.linkedin.metadata.models.StructuredPropertyUtils.toElasticsearchFieldName;

import com.linkedin.common.urn.Urn;
import com.linkedin.datahub.DataHubSearchConfig;
import com.linkedin.metadata.aspect.AspectRetriever;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.models.LogicalValueType;
import com.linkedin.metadata.search.elasticsearch.index.entity.v3.V3SearchFields;
import com.linkedin.structured.StructuredPropertyDefinition;
import io.datahubproject.metadata.context.OperationContext;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Structured properties Search V3 full-text reads, resolved from the active definitions for the
 * request. The list is cached per {@link AspectRetriever} and dropped when a definition changes, so
 * it is not stored on the static {@code SearchRequestHandler} cache.
 */
public final class V3StructuredPropertySearchCatalog {

  /** Weight of {@code .text} when a definition does not set {@code boostScore}. */
  public static final float DEFAULT_TEXT_WEIGHT = 0.8f;

  /**
   * Stemmed weight relative to {@code .text} when {@code subfieldBoostScores.stemmed} is absent.
   */
  public static final float STEMMED_FACTOR = 0.5f;

  public static final String STEMMED = "stemmed";

  private static final ConcurrentHashMap<AspectRetriever, List<IndexedProperty>> CACHE =
      new ConcurrentHashMap<>();

  private V3StructuredPropertySearchCatalog() {}

  /** Drops every cached listing. A structured-property definition change calls this. */
  public static void invalidate() {
    CACHE.clear();
  }

  /**
   * Opted-in properties that apply to at least one of {@code entitySpecs}. A property mapped on
   * only some indices of a multi-index search is still returned; the query is lenient on the
   * others.
   */
  @Nonnull
  public static List<IndexedProperty> indexed(
      @Nullable OperationContext opContext, @Nonnull Collection<EntitySpec> entitySpecs) {
    if (opContext == null) {
      return List.of();
    }
    AspectRetriever retriever = opContext.getAspectRetriever();
    if (retriever == null) {
      return List.of();
    }
    List<IndexedProperty> all = CACHE.computeIfAbsent(retriever, key -> load(opContext, key));
    return all.stream().filter(property -> property.appliesTo(entitySpecs)).toList();
  }

  @Nonnull
  private static List<IndexedProperty> load(
      @Nonnull OperationContext opContext, @Nonnull AspectRetriever retriever) {
    return retriever.listStructuredPropertyDefinitions(opContext).stream()
        .map(pair -> indexedProperty(pair.getKey(), pair.getValue()))
        .flatMap(Optional::stream)
        .toList();
  }

  @Nonnull
  private static Optional<IndexedProperty> indexedProperty(
      @Nonnull Urn propertyUrn, @Nonnull StructuredPropertyDefinition definition) {
    if (!definition.hasSearchConfiguration()
        || definition.getEntityTypes() == null
        || definition.getEntityTypes().isEmpty()) {
      return Optional.empty();
    }
    LogicalValueType logicalValueType = getLogicalValueType(definition.getValueType());
    if (logicalValueType != LogicalValueType.STRING
        && logicalValueType != LogicalValueType.RICH_TEXT
        && logicalValueType != LogicalValueType.URN) {
      return Optional.empty();
    }
    DataHubSearchConfig config = definition.getSearchConfiguration();
    float textWeight = textWeight(config);
    return Optional.of(
        new IndexedProperty(
            V3SearchFields.STRUCTURED_PROPERTIES
                + "."
                + toElasticsearchFieldName(propertyUrn, definition),
            definition.getEntityTypes(),
            config.isQueryByDefault(),
            textWeight,
            stemmedWeight(config, textWeight)));
  }

  /**
   * {@code getBoostScore()} cannot tell an absent key from an explicit 1.0, and the PDL default
   * stays 1.0 so V2 is unchanged. V3 uses 0.8 only when the key was not set.
   */
  private static float textWeight(@Nonnull DataHubSearchConfig config) {
    if (config.data().containsKey("boostScore")) {
      return config.getBoostScore().floatValue();
    }
    return DEFAULT_TEXT_WEIGHT;
  }

  private static float stemmedWeight(@Nonnull DataHubSearchConfig config, float textWeight) {
    if (config.hasSubfieldBoostScores() && config.getSubfieldBoostScores().containsKey(STEMMED)) {
      return config.getSubfieldBoostScores().get(STEMMED).floatValue();
    }
    return textWeight * STEMMED_FACTOR;
  }

  /**
   * One opted-in property. {@code fieldPath} is {@code structuredProperties.<field>}, the name a
   * field configuration uses. Weights are before the partial factor.
   */
  public record IndexedProperty(
      @Nonnull String fieldPath,
      @Nonnull Collection<Urn> entityTypes,
      boolean queryByDefault,
      float textWeight,
      float stemmedWeight) {

    public boolean appliesTo(@Nonnull Collection<EntitySpec> entitySpecs) {
      return entitySpecs.stream()
          .anyMatch(
              spec ->
                  entityTypes.stream()
                      .anyMatch(entityType -> entityTypeMatches(entityType, spec.getName())));
    }
  }
}
