package com.linkedin.metadata.search.elasticsearch.query.request;

import static com.linkedin.metadata.Constants.DATASET_ENTITY_NAME;
import static com.linkedin.metadata.Constants.STRUCTURED_PROPERTY_ENTITY_NAME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.common.UrnArray;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.data.template.StringMap;
import com.linkedin.datahub.DataHubSearchConfig;
import com.linkedin.metadata.entity.SearchRetriever;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.models.registry.EntityRegistry;
import com.linkedin.metadata.query.SearchFlags;
import com.linkedin.metadata.query.filter.Condition;
import com.linkedin.metadata.query.filter.Filter;
import com.linkedin.metadata.search.ScrollResult;
import com.linkedin.metadata.search.SearchEntity;
import com.linkedin.metadata.search.SearchEntityArray;
import com.linkedin.metadata.search.elasticsearch.index.entity.v3.StructuredPropertySearchMetadata;
import com.linkedin.structured.StructuredPropertyDefinition;
import io.datahubproject.metadata.context.OperationContext;
import io.datahubproject.metadata.context.RetrieverContext;
import io.datahubproject.test.metadata.context.TestOperationContexts;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class V3StructuredPropertySearchCatalogTest {

  private SearchRetriever searchRetriever;
  private OperationContext opContext;
  private EntityRegistry entityRegistry;

  @BeforeMethod
  public void setUp() {
    searchRetriever = mock(SearchRetriever.class);
    opContext = mock(OperationContext.class);
    RetrieverContext retrieverContext = mock(RetrieverContext.class);
    entityRegistry = TestOperationContexts.defaultEntityRegistry();
    when(opContext.getRetrieverContext()).thenReturn(retrieverContext);
    when(opContext.getObjectMapper()).thenReturn(new ObjectMapper());
    when(retrieverContext.getSearchRetriever()).thenReturn(searchRetriever);
  }

  @Test
  public void testSkipsSearchWhenNoTargetEntitySupportsStructuredProperties() {
    EntitySpec structuredPropertySpec =
        entityRegistry.getEntitySpec(STRUCTURED_PROPERTY_ENTITY_NAME);

    assertTrue(
        V3StructuredPropertySearchCatalog.indexed(opContext, List.of(structuredPropertySpec))
            .isEmpty());
    verify(searchRetriever, never()).scroll(anyList(), any(), isNull(), anyInt(), anyList(), any());
  }

  @Test
  public void testLoadsActiveMetadataForMixedTargets() throws Exception {
    Urn propertyUrn = UrnUtils.getUrn("urn:li:structuredProperty:hiddenLabel");
    StructuredPropertyDefinition definition =
        new StructuredPropertyDefinition()
            .setQualifiedName("hiddenLabel")
            .setValueType(UrnUtils.getUrn("urn:li:dataType:datahub.string"))
            .setEntityTypes(new UrnArray(UrnUtils.getUrn("urn:li:entityType:datahub.dataset")))
            .setSearchConfiguration(new DataHubSearchConfig().setQueryByDefault(false));
    StringMap extraFields = new StringMap();
    extraFields.put(
        StructuredPropertySearchMetadata.FIELD,
        StructuredPropertySearchMetadata.project(propertyUrn, definition).toString());
    extraFields.put(
        "entityTypes",
        opContext
            .getObjectMapper()
            .writeValueAsString(List.of("urn:li:entityType:datahub.dataset")));
    ScrollResult page =
        new ScrollResult()
            .setEntities(
                new SearchEntityArray(
                    new SearchEntity().setEntity(propertyUrn).setExtraFields(extraFields)))
            .setNumEntities(1)
            .setPageSize(1);
    when(searchRetriever.scroll(
            anyList(), any(Filter.class), isNull(), anyInt(), anyList(), any(SearchFlags.class)))
        .thenReturn(page);

    List<V3StructuredPropertySearchCatalog.IndexedProperty> properties =
        V3StructuredPropertySearchCatalog.indexed(
            opContext,
            List.of(
                entityRegistry.getEntitySpec(STRUCTURED_PROPERTY_ENTITY_NAME),
                entityRegistry.getEntitySpec(DATASET_ENTITY_NAME)));

    assertEquals(properties.size(), 1);
    assertEquals(properties.get(0).fieldPath(), "structuredProperties.hiddenLabel");
    assertFalse(properties.get(0).queryByDefault());
    assertEquals(
        V3StructuredPropertySearchCatalog.indexed(
            opContext, List.of(entityRegistry.getEntitySpec(DATASET_ENTITY_NAME))),
        properties);

    ArgumentCaptor<List<String>> entities = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
    ArgumentCaptor<SearchFlags> flags = ArgumentCaptor.forClass(SearchFlags.class);
    verify(searchRetriever)
        .scroll(
            entities.capture(), filter.capture(), isNull(), anyInt(), anyList(), flags.capture());
    assertEquals(entities.getValue(), List.of(STRUCTURED_PROPERTY_ENTITY_NAME));
    assertEquals(
        filter.getValue().getOr().get(0).getAnd().get(0).getField(),
        StructuredPropertySearchMetadata.FIELD + ".enabled");
    assertEquals(filter.getValue().getOr().get(0).getAnd().get(0).getCondition(), Condition.EQUAL);
    assertEquals(filter.getValue().getOr().get(0).getAnd().get(0).getValues(), List.of("true"));
    assertFalse(flags.getValue().isIncludeSoftDeleted());
    assertTrue(
        flags.getValue().getFetchExtraFields().contains(StructuredPropertySearchMetadata.FIELD));
    assertTrue(flags.getValue().getFetchExtraFields().contains("entityTypes"));
  }
}
