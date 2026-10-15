package com.linkedin.metadata.search.elasticsearch.query.request;

import static com.linkedin.metadata.Constants.STRUCTURED_PROPERTIES_ASPECT_NAME;
import static com.linkedin.metadata.Constants.STRUCTURED_PROPERTY_ENTITY_NAME;
import static com.linkedin.metadata.models.StructuredPropertyUtils.entityTypeMatches;
import static com.linkedin.metadata.search.utils.QueryUtils.newConjunctiveFilter;
import static com.linkedin.metadata.utils.CriterionUtils.buildCriterion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.data.DataMap;
import com.linkedin.data.template.StringArray;
import com.linkedin.metadata.entity.SearchRetriever;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.query.SearchFlags;
import com.linkedin.metadata.query.filter.Condition;
import com.linkedin.metadata.query.filter.SortCriterion;
import com.linkedin.metadata.query.filter.SortOrder;
import com.linkedin.metadata.search.ScrollResult;
import com.linkedin.metadata.search.SearchEntity;
import com.linkedin.metadata.search.elasticsearch.index.entity.v3.StructuredPropertySearchMetadata;
import io.datahubproject.metadata.context.OperationContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Structured properties Search V3 full-text reads, resolved from metadata projected onto active
 * structured-property search documents. The result is cached per search retriever.
 */
@Slf4j
public final class V3StructuredPropertySearchCatalog {

  private static final int PAGE_SIZE = 500;
  // Match cache.client.entityClient.entityAspectTTLSeconds.structuredProperty.propertyDefinition.
  // Search writes are asynchronous and GMS pods do not share invalidation signals, so expiry is the
  // consistency boundary rather than event-driven invalidation.
  private static final int CACHE_TTL_SECONDS = 300;
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
    SearchFlags flags =
        new SearchFlags(new DataMap(SearchRetriever.RETRIEVER_SEARCH_FLAGS.data()))
            .setFetchExtraFields(
                new StringArray(List.of(StructuredPropertySearchMetadata.FIELD, "entityTypes")));
    SortCriterion urnSort = new SortCriterion().setField("urn").setOrder(SortOrder.ASCENDING);

    List<IndexedProperty> properties = new ArrayList<>();
    String scrollId = null;
    do {
      ScrollResult page =
          searchRetriever.scroll(
              List.of(STRUCTURED_PROPERTY_ENTITY_NAME),
              newConjunctiveFilter(
                  buildCriterion(
                      StructuredPropertySearchMetadata.FIELD
                          + "."
                          + StructuredPropertySearchMetadata.ENABLED,
                      Condition.EQUAL,
                      "true")),
              scrollId,
              PAGE_SIZE,
              List.of(urnSort),
              flags);
      if (page.getEntities() == null || page.getEntities().isEmpty()) {
        break;
      }
      page.getEntities().stream()
          .map(entity -> indexedProperty(opContext, entity))
          .flatMap(Optional::stream)
          .forEach(properties::add);
      scrollId = page.getScrollId();
    } while (scrollId != null);
    return List.copyOf(properties);
  }

  @Nonnull
  private static Optional<IndexedProperty> indexedProperty(
      @Nonnull OperationContext opContext, @Nonnull SearchEntity entity) {
    if (entity.getExtraFields() == null) {
      return Optional.empty();
    }
    String metadataJson = entity.getExtraFields().get(StructuredPropertySearchMetadata.FIELD);
    String entityTypesJson = entity.getExtraFields().get("entityTypes");
    if (metadataJson == null || entityTypesJson == null) {
      return Optional.empty();
    }
    try {
      JsonNode metadataNode = opContext.getObjectMapper().readTree(metadataJson);
      if (!metadataNode.path(StructuredPropertySearchMetadata.ENABLED).asBoolean(false)) {
        return Optional.empty();
      }
      StructuredPropertySearchMetadata.IndexedMetadata metadata =
          StructuredPropertySearchMetadata.parse(metadataNode);
      if (metadata.fieldPath().isBlank()) {
        return Optional.empty();
      }
      List<String> entityTypeStrings =
          opContext
              .getObjectMapper()
              .readValue(entityTypesJson, new TypeReference<List<String>>() {});
      List<Urn> entityTypes = entityTypeStrings.stream().map(UrnUtils::getUrn).toList();
      if (entityTypes.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(
          new IndexedProperty(
              metadata.fieldPath(),
              entityTypes,
              metadata.queryByDefault(),
              metadata.textWeight(),
              metadata.stemmedWeight()));
    } catch (IOException | RuntimeException e) {
      log.warn(
          "Ignoring invalid structured-property search metadata for {}", entity.getEntity(), e);
      return Optional.empty();
    }
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
