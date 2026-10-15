package com.linkedin.metadata.search.elasticsearch.query.request;

import static com.linkedin.metadata.Constants.DATASET_ENTITY_NAME;
import static com.linkedin.metadata.Constants.STRUCTURED_PROPERTY_DEFINITION_ASPECT_NAME;
import static com.linkedin.metadata.Constants.STRUCTURED_PROPERTY_ENTITY_NAME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import com.linkedin.common.UrnArray;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.data.DataMap;
import com.linkedin.datahub.DataHubSearchConfig;
import com.linkedin.entity.Aspect;
import com.linkedin.metadata.aspect.CachingAspectRetriever;
import com.linkedin.metadata.entity.SearchRetriever;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.models.registry.EntityRegistry;
import com.linkedin.metadata.query.SearchFlags;
import com.linkedin.metadata.search.ScrollResult;
import com.linkedin.metadata.search.SearchEntity;
import com.linkedin.metadata.search.SearchEntityArray;
import com.linkedin.structured.StructuredPropertyDefinition;
import io.datahubproject.metadata.context.OperationContext;
import io.datahubproject.metadata.context.RetrieverContext;
import io.datahubproject.test.metadata.context.TestOperationContexts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class V3StructuredPropertySearchCatalogTest {

  private SearchRetriever searchRetriever;
  private CachingAspectRetriever aspectRetriever;
  private OperationContext opContext;
  private EntityRegistry entityRegistry;

  @BeforeMethod
  public void setUp() {
    searchRetriever = mock(SearchRetriever.class);
    aspectRetriever = mock(CachingAspectRetriever.class);
    opContext = mock(OperationContext.class);
    RetrieverContext retrieverContext = mock(RetrieverContext.class);
    entityRegistry = TestOperationContexts.defaultEntityRegistry();
    when(opContext.getRetrieverContext()).thenReturn(retrieverContext);
    when(retrieverContext.getSearchRetriever()).thenReturn(searchRetriever);
    when(retrieverContext.getCachingAspectRetriever()).thenReturn(aspectRetriever);
  }

  @Test
  public void testSkipsSearchWhenNoTargetEntitySupportsStructuredProperties() {
    EntitySpec structuredPropertySpec =
        entityRegistry.getEntitySpec(STRUCTURED_PROPERTY_ENTITY_NAME);

    assertTrue(
        V3StructuredPropertySearchCatalog.indexed(opContext, List.of(structuredPropertySpec))
            .isEmpty());
    verify(searchRetriever, never()).scroll(anyList(), any(), isNull(), anyInt(), anyList(), any());
    verify(aspectRetriever, never()).getLatestAspectObjects(any(), anySet(), anySet());
  }

  @Test
  public void testLoadsDefinitionsForMixedTargets() throws Exception {
    Urn hiddenUrn = UrnUtils.getUrn("urn:li:structuredProperty:hiddenLabel");
    Urn numberUrn = UrnUtils.getUrn("urn:li:structuredProperty:priority");
    StructuredPropertyDefinition hidden =
        definition("hiddenLabel", "string")
            .setSearchConfiguration(new DataHubSearchConfig().setQueryByDefault(false));
    StructuredPropertyDefinition number =
        definition("priority", "number")
            .setSearchConfiguration(new DataHubSearchConfig().setQueryByDefault(true));
    ScrollResult page =
        new ScrollResult()
            .setEntities(
                new SearchEntityArray(
                    new SearchEntity().setEntity(hiddenUrn),
                    new SearchEntity().setEntity(numberUrn)))
            .setNumEntities(2)
            .setPageSize(2);
    when(searchRetriever.scroll(
            anyList(), isNull(), isNull(), anyInt(), anyList(), any(SearchFlags.class)))
        .thenReturn(page);
    when(aspectRetriever.getLatestAspectObjects(
            eq(opContext), anySet(), eq(Set.of(STRUCTURED_PROPERTY_DEFINITION_ASPECT_NAME))))
        .thenReturn(
            Map.of(
                hiddenUrn,
                Map.of(
                    STRUCTURED_PROPERTY_DEFINITION_ASPECT_NAME,
                    new Aspect(new DataMap(hidden.data()))),
                numberUrn,
                Map.of(
                    STRUCTURED_PROPERTY_DEFINITION_ASPECT_NAME,
                    new Aspect(new DataMap(number.data())))));

    List<EntitySpec> targets =
        List.of(
            entityRegistry.getEntitySpec(STRUCTURED_PROPERTY_ENTITY_NAME),
            entityRegistry.getEntitySpec(DATASET_ENTITY_NAME));
    List<V3StructuredPropertySearchCatalog.IndexedProperty> properties =
        V3StructuredPropertySearchCatalog.indexed(opContext, targets);

    assertEquals(properties.size(), 1);
    assertEquals(properties.get(0).fieldPath(), "structuredProperties.hiddenLabel");
    assertFalse(properties.get(0).queryByDefault());
    assertEquals(properties.get(0).textWeight(), 0.8f, 0.0001f);
    assertEquals(properties.get(0).stemmedWeight(), 0.4f, 0.0001f);
    assertEquals(V3StructuredPropertySearchCatalog.indexed(opContext, targets), properties);

    ArgumentCaptor<List<String>> entities = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<SearchFlags> flags = ArgumentCaptor.forClass(SearchFlags.class);
    verify(searchRetriever, times(1))
        .scroll(entities.capture(), isNull(), isNull(), anyInt(), anyList(), flags.capture());
    assertEquals(entities.getValue(), List.of(STRUCTURED_PROPERTY_ENTITY_NAME));
    assertFalse(flags.getValue().isIncludeSoftDeleted());
    verify(aspectRetriever, times(1))
        .getLatestAspectObjects(
            eq(opContext), anySet(), eq(Set.of(STRUCTURED_PROPERTY_DEFINITION_ASPECT_NAME)));
  }

  private static StructuredPropertyDefinition definition(String name, String type) {
    return new StructuredPropertyDefinition()
        .setQualifiedName(name)
        .setValueType(UrnUtils.getUrn("urn:li:dataType:datahub." + type))
        .setEntityTypes(new UrnArray(UrnUtils.getUrn("urn:li:entityType:datahub.dataset")));
  }
}
