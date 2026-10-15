package com.linkedin.metadata.search.elasticsearch.query.request;

import static com.linkedin.metadata.Constants.STRUCTURED_PROPERTIES_ASPECT_NAME;
import static com.linkedin.metadata.Constants.STRUCTURED_PROPERTY_DEFINITION_ASPECT_NAME;
import static com.linkedin.metadata.Constants.STRUCTURED_PROPERTY_ENTITY_NAME;
import static com.linkedin.metadata.models.StructuredPropertyUtils.entityTypeMatches;
import static com.linkedin.metadata.models.StructuredPropertyUtils.getLogicalValueType;
import static com.linkedin.metadata.models.StructuredPropertyUtils.toElasticsearchFieldName;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.DataMap;
import com.linkedin.datahub.DataHubSearchConfig;
import com.linkedin.entity.Aspect;
import com.linkedin.metadata.aspect.CachingAspectRetriever;
import com.linkedin.metadata.entity.SearchRetriever;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.models.LogicalValueType;
import com.linkedin.metadata.query.SearchFlags;
import com.linkedin.metadata.query.filter.SortCriterion;
import com.linkedin.metadata.query.filter.SortOrder;
import com.linkedin.metadata.search.ScrollResult;
import com.linkedin.metadata.search.SearchEntity;
import com.linkedin.metadata.search.elasticsearch.index.entity.v3.V3SearchFields;
import com.linkedin.structured.StructuredPropertyDefinition;
import io.datahubproject.metadata.context.OperationContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Structured properties Search V3 full-text reads. Active property URNs come from search, which
 * excludes soft-deleted entities. Definitions come from the cached entity client.
 */
public final class V3StructuredPropertySearchCatalog {

  private static final int PAGE_SIZE = 500;
  // Match cache.client.entityClient.entityAspectTTLSeconds.structuredProperty.propertyDefinition.
  private static final int CACHE_TTL_SECONDS = 300;
  private static final float DEFAULT_TEXT_WEIGHT = 0.8f;
  private static final float STEMMED_FACTOR = 0.5f;
  private static final String STEMMED = "stemmed";
  private static final Cache<SearchRetriever, List<IndexedProperty>> CACHE =
      CacheBuilder.newBuilder()
          .maximumSize(100)
          .expireAfterWrite(CACHE_TTL_SECONDS, TimeUnit.SECONDS)
          .build();

  private V3StructuredPropertySearchCatalog() {}

  /**
   * Opted-in properties that apply to at least one of {@code entitySpecs}. A property mapped on
   * only some indices of a multi-index search is still returned; the query is lenient on the
   * others.
   */
  @Nonnull
  public static List<IndexedProperty> indexed(
      @Nullable OperationContext opContext, @Nonnull Collection<EntitySpec> entitySpecs) {
    if (opContext == null
        || entitySpecs.stream()
            .noneMatch(entitySpec -> entitySpec.hasAspect(STRUCTURED_PROPERTIES_ASPECT_NAME))) {
      return List.of();
    }
    SearchRetriever searchRetriever = opContext.getRetrieverContext().getSearchRetriever();
    List<IndexedProperty> all =
        CACHE.asMap().computeIfAbsent(searchRetriever, ignored -> load(opContext, searchRetriever));
    return all.stream().filter(property -> property.appliesTo(entitySpecs)).toList();
  }

  @Nonnull
  private static List<IndexedProperty> load(
      @Nonnull OperationContext opContext, @Nonnull SearchRetriever searchRetriever) {
    SearchFlags flags = new SearchFlags(new DataMap(SearchRetriever.RETRIEVER_SEARCH_FLAGS.data()));
    SortCriterion urnSort = new SortCriterion().setField("urn").setOrder(SortOrder.ASCENDING);
    CachingAspectRetriever aspectRetriever =
        opContext.getRetrieverContext().getCachingAspectRetriever();

    List<IndexedProperty> properties = new ArrayList<>();
    String scrollId = null;
    do {
      ScrollResult page =
          searchRetriever.scroll(
              List.of(STRUCTURED_PROPERTY_ENTITY_NAME),
              null,
              scrollId,
              PAGE_SIZE,
              List.of(urnSort),
              flags);
      if (page.getEntities() == null || page.getEntities().isEmpty()) {
        break;
      }
      Set<Urn> urns = new LinkedHashSet<>();
      page.getEntities().stream().map(SearchEntity::getEntity).forEach(urns::add);
      Map<Urn, Map<String, Aspect>> aspects =
          aspectRetriever.getLatestAspectObjects(
              opContext, urns, Set.of(STRUCTURED_PROPERTY_DEFINITION_ASPECT_NAME));
      for (Urn urn : urns) {
        Aspect definitionAspect =
            aspects.getOrDefault(urn, Map.of()).get(STRUCTURED_PROPERTY_DEFINITION_ASPECT_NAME);
        if (definitionAspect == null) {
          continue;
        }
        indexedProperty(urn, new StructuredPropertyDefinition(new DataMap(definitionAspect.data())))
            .ifPresent(properties::add);
      }
      scrollId = page.getScrollId();
    } while (scrollId != null);
    return List.copyOf(properties);
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

  private static float textWeight(@Nonnull DataHubSearchConfig config) {
    if (config.data().containsKey("boostScore")) {
      return config.getBoostScore().floatValue();
    }
    return DEFAULT_TEXT_WEIGHT;
  }

  private static float stemmedWeight(@Nonnull DataHubSearchConfig config, final float textWeight) {
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
