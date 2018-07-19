package org.oreto.graphql

import com.owlike.genson.Genson
import com.owlike.genson.GensonBuilder
import gql.DSL
import gql.dsl.ScalarsAware
import grails.converters.JSON
import graphql.language.Field
import graphql.language.NullValue
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeReference
import org.grails.core.artefact.DomainClassArtefactHandler
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.Association
import org.grails.orm.hibernate.cfg.GrailsDomainBinder
import org.grails.orm.hibernate.cfg.PropertyConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class GraphUtils {

    private static final Logger L = LoggerFactory.getLogger(GraphUtils.class)

    static String SIZE_ARG_NAME = 'size'
    static String SKIP_ARG_NAME = 'skip'
    static String FILTER_ARG_NAME = 'filter'
    static String ORDERBY_ARG_NAME = 'orderBy'
    static String PAGED_RESULTS_NAME = 'results'
    static String PAGE_INFO_NAME = 'pageInfo'
    static String INFO_TOTAL_COUNT_NAME = 'totalCount'
    static String INFO_SIZE_NAME = 'size'
    static String INFO_SKIP_NAME = 'skip'
    static String INFO_PAGE_NAME = 'page'
    static String INFO_TOTAL_PAGES_NAME = 'totalPages'

    static int DEFAULT_SIZE = 20
    static int MAX_SIZE = 1000

    static Genson genson = new GensonBuilder().useStrictDoubleParse(false).create()

    static GraphQLSchema createGqlSchema(Map<String, PersistentEntity> entities) {
        if (entities.isEmpty()) throw new Exception('GORM entities are required to create a graphql schema. None specified.')
        Map<String, GraphQLOutputType> typeMap

        Iterator<Map.Entry<String, PersistentEntity>> iterator = entities.entrySet().iterator()
        Map.Entry<String, PersistentEntity> entry = iterator.next()
        def entityQueries = entityToFilterQuery(entry.key, entry.value, (typeMap = [:]))

        while(iterator.hasNext()) {
            entry = iterator.next()
            entityQueries = entityQueries << entityToFilterQuery(entry.key, entry.value, typeMap)
        }
        DSL.schema( {
            queries(
                    entityQueries
            )
        })
    }

    static Closure entityToFilterQuery(String name, PersistentEntity entity, Map<String, GraphQLOutputType> typeMap) {
        Closure filterQuery = {
            field(name) {
                argument(FILTER_ARG_NAME, GraphQLString)
                argument(SIZE_ARG_NAME, GraphQLInt)
                argument(SKIP_ARG_NAME, GraphQLInt)
                argument(ORDERBY_ARG_NAME, list(GraphQLString))
                type entityToType(entity, typeMap)
                fetcher { DataFetchingEnvironment env ->
                    int max = env.getArgument(SIZE_ARG_NAME) ?: DEFAULT_SIZE
                    max = max > MAX_SIZE ? MAX_SIZE : max
                    int offset = (env.getArgument(SKIP_ARG_NAME) ?: 0) as int

                    List<Field> selections = (env.selectionSet.get().get(PAGED_RESULTS_NAME)
                            ?.find { it.name == PAGED_RESULTS_NAME}
                            ?.selectionSet?.selections as List<Field>) ?: []

                    List<String> criteriaList =
                        GqlToCriteria.transform(entity
                                , selections
                                , [(FILTER_ARG_NAME) : env.getArgument(FILTER_ARG_NAME)
                                , (SIZE_ARG_NAME) : max
                                , (SKIP_ARG_NAME) : offset
                                , (ORDERBY_ARG_NAME) : env.getArgument(ORDERBY_ARG_NAME)
                        ])
                    def count = Eval.me(criteriaList[0]) as Collection
                    def results = Eval.me(criteriaList[1]) as Collection
                    def entities = resultSetToEntities(results, entity, fieldsWithoutId(selections, entity))
                    eagerFetch(entities, entity, selections)
                    new PagedGraphResults(entities, max, offset, count[0] as int)
                }
            }
        }
        filterQuery
    }

    static int DEFAULT_BATCH_SIZE = 200

    static eagerFetch(Collection entities, PersistentEntity entity, List<Field> selections) {
        def ids = entities.collect { it."${entity.identity.name}" }
        selections.each {
            def results = (it.selectionSet?.selections as List<Field>)?.find { it.name == PAGED_RESULTS_NAME }
            if (results) {
                def filterArg = it.arguments.find { it.name == FILTER_ARG_NAME }?.value
                def sizeArg = it.arguments.find { it.name == SIZE_ARG_NAME }?.value
                def skipArg = it.arguments.find { it.name == SKIP_ARG_NAME }?.value

                String filter = filterArg instanceof NullValue ? '{}' : filterArg?.value ?: '{}'
                int max = sizeArg instanceof NullValue ? DEFAULT_SIZE : sizeArg?.value ?: DEFAULT_SIZE
                if (max > MAX_SIZE) max = MAX_SIZE
                int offset = skipArg instanceof NullValue ? 0 : skipArg?.value ?: 0

                List<Field> resultSelections = results.selectionSet?.selections as List<Field>
                Association association = getAssociation(entity, it.name)

                int batchSize = GrailsDomainBinder.getMapping(association.associatedEntity.javaClass).batchSize ?: DEFAULT_BATCH_SIZE
                L.debug("${association.associatedEntity.javaClass.simpleName} batch size: $batchSize")

                Collection eagerResults = []
                def orderByArg = it.arguments.find { it.name == ORDERBY_ARG_NAME }?.value
                List<String> orderBy = orderByArg == null || orderByArg instanceof NullValue ? [] :
                        orderByArg instanceof Collection ? orderByArg?.values?.collect { it.value as String } : [orderByArg.value]

                int i = 1
                ids.collate(batchSize).each { idBatch ->
                    L.debug("batch: $i")
                    String criteria = GqlToCriteria.transformEagerBatch(entity
                            , idBatch
                            , association
                            , resultSelections
                            , [(FILTER_ARG_NAME) : filter
                               , (SIZE_ARG_NAME) : 1000000
                               , (SKIP_ARG_NAME) : 0
                               , (ORDERBY_ARG_NAME) : orderBy
                    ])
                    if (eagerResults) {
                        eagerResults.addAll(Eval.me(criteria) as Collection)
                    }
                    else {
                        eagerResults = Eval.me(criteria) as Collection
                    }
                    i++
                }
                if (eagerResults.size()) {
                    def entityMap = resultSetToEntityMap(eagerResults
                            , association.associatedEntity
                            , fieldsWithoutId(resultSelections, association.associatedEntity))
                    def propertyName = it.name
                    entities.each {
                        def id = it."${entity.identity.name}"
                        if (entityMap.containsKey(id)) {
                            it."$propertyName" = entityMap.get(id).sort{ a, b ->
                                int compare = 0
                                for(Map.Entry<String, String> order : QueryUtils.parseOrderBy(orderBy, association.associatedEntity)) {
                                    if (order.value == 'asc') {
                                        compare = a."${order.key}" <=> b."${order.key}"
                                    } else {
                                        compare = b."${order.key}" <=> a."${order.key}"
                                    }
                                    if (compare != 0) break
                                }
                                compare
                            }.drop(offset).take(max)
                        } else {
                            it."$propertyName" = []
                        }
                    }
                    if (entityMap.size() > 0) {
                        def newEntities = []
                        entityMap.each { newEntities.addAll(it.value) }
                        if (newEntities.size()) eagerFetch(newEntities, association.associatedEntity, resultSelections)
                    }
                } else {
                    def propertyName = it.name
                    entities.each {
                        it."$propertyName" = []
                    }
                }
            } else if (it.selectionSet) {
                def subEntities = []
                def propertyName = it.name
                entities.each {
                    def e = it."$propertyName"
                    if (e) subEntities.add(e)
                }
                if (subEntities.size()) {
                    def subEntity = getAssociation(entity, propertyName).associatedEntity
                    eagerFetch(subEntities, subEntity, it.selectionSet.selections as List<Field>)
                }
            }
        }
    }

    static PageInfo = DSL.type('PageInfo') {
        description('paging information')
        field(INFO_TOTAL_COUNT_NAME) {
            description('total number of elements in collection')
            type GraphQLInt
        }
        field(INFO_SIZE_NAME) {
            description('size of collection')
            type GraphQLInt
        }
        field(INFO_SKIP_NAME) {
            description('number of elements to skip from the beginning')
            type GraphQLInt
        }
        field(INFO_PAGE_NAME) {
            description('current page number')
            type GraphQLInt
        }
        field(INFO_TOTAL_PAGES_NAME) {
            description('total number of pages in collection')
            type GraphQLInt
        }
    }

    static GraphQLOutputType pagedType(String name, String listName, List<Closure> fields) {
        DSL.type(name) {
            field(PAGED_RESULTS_NAME) {
                type list(DSL.type(listName, mergeClosures(fields)))
            }
            field(PAGE_INFO_NAME) {
                type PageInfo
            }
        }
    }

    static List resultSetToEntities(Collection results, PersistentEntity entity, List<Field> fields) {
        LinkedHashMap entities = new LinkedHashMap()
        results.collect { Object[] row ->
            _resultSetToEntities(entity, fields, row, 0, entities)[0]
        }
    }

    static Map<Object, Collection> resultSetToEntityMap(Collection results, PersistentEntity entity, List<Field> fields) {
        LinkedHashMap entities = new LinkedHashMap()
        Map<Object, Collection> entityMap = new LinkedHashMap<Object, Collection>()
        results.collect { Object[] row ->
            def newEntity = _resultSetToEntities(entity, fields, row, 1, entities)[0]
            if (entityMap.containsKey(row[0])) {
                entityMap.get(row[0]).add(newEntity)
            } else {
                entityMap.put(row[0], [newEntity])
            }
        }
        entityMap
    }

    private static List _resultSetToEntities(PersistentEntity entity
                                             , List<Field> fields
                                             , Object[] row
                                             , int i
                                             , LinkedHashMap entities) {
        def newEntity = null, association

        String id = row[i]
        String keyId = "${entity.name}-$id"
        if (entities?.containsKey(keyId)) {
            newEntity = entities.get(keyId)
        } else if (row[i]) {
            newEntity = entity.javaClass.newInstance()
            newEntity."${entity.identity.name}" = row[i]
            entities.put(keyId, newEntity)
        }
        i++
        int rowLen = row.length
        for (Field it : fields) {
            if (i >= rowLen || !newEntity) break
            def name = it.name
            def results = (it.selectionSet?.selections as List<Field>)?.find{ it.name == PAGED_RESULTS_NAME}
            if (results) {
                // one to many and many to many associations won't be in the result set, don't process this
//                def selections = selectionsWithoutId(results, entity)
//                if (newEntity."$name" == null) newEntity."$name" = []
//                (association, i) = _resultSetToEntities(
//                        entity.getAssociations().find{ it.name == name }.associatedEntity
//                        , selections, row, i, entities)
//                if (!newEntity."$name".contains(association)) newEntity."$name".add(association)
            } else if (it.selectionSet) {
                if (row[i] && DomainClassArtefactHandler.isDomainClass(row[i].class, true)) {
                    newEntity."$name" = row[i]
                    i++
                } else {
                    def selections = selectionsWithoutId(it, entity)
                    def associatedEntity = entity.getAssociations().find{ it.name == name }.associatedEntity
                    (association, i) = _resultSetToEntities(associatedEntity, selections, row, i, entities)
                    newEntity."$name" = association
                }
            } else if(!name.startsWith('__')) {
                newEntity."$name" = row[i]
                i++
            }
        }
        [newEntity, i]
    }

    static List<Field> selectionsWithoutId(Field field, PersistentEntity entity) {
        fieldsWithoutId(field.selectionSet?.selections as List<Field>, entity)
    }

    static List<Field> fieldsWithoutId(List<Field> fields, PersistentEntity entity) {
        fields?.findAll { it.name != entity.identity.name }
    }

    static GraphQLOutputType entityToType(PersistentEntity entity
                                          , Map<String, GraphQLOutputType> typeMap
                                          , boolean paged = true) {

        String simpleName = paged ? "${entity.javaClass.simpleName}List" : entity.javaClass.simpleName
        String resultsName = "${simpleName}Results"
        List<Closure> fields = []

        if (paged) {
            if (typeMap.containsKey(resultsName)) {
                return typeMap.get(resultsName)
            }
            typeMap.put(resultsName, new GraphQLTypeReference(resultsName))
        } else {
            if (typeMap.containsKey(simpleName)) {
                return typeMap.get(simpleName)
            }
            typeMap.put(simpleName, new GraphQLTypeReference(simpleName))
        }

        fields.add({
            field(entity.identity.name) {
                type propertyToType(entity.identity)
            }
        })
        entity.persistentProperties.each { PersistentProperty property ->
            Association association = getAssociation(entity, property.name)
            def graphField
            if (association) {
                PersistentEntity associatedEntity = association.getAssociatedEntity()
                if (propertyIsCollection(property)) {
                    graphField = { field(property.name) {
                        type entityToType(associatedEntity, typeMap)
                        argument(FILTER_ARG_NAME, GraphQLString)
                        argument(SIZE_ARG_NAME, GraphQLInt)
                        argument(SKIP_ARG_NAME, GraphQLInt)
                        argument(ORDERBY_ARG_NAME, list(GraphQLString))
                        fetcher { DataFetchingEnvironment env ->
                            int max = env.getArgument(SIZE_ARG_NAME) ?: DEFAULT_SIZE
                            max = max > MAX_SIZE ? MAX_SIZE : max
                            int offset = (env.getArgument(SKIP_ARG_NAME) ?: 0) as int
                            String filter = env.getArgument(FILTER_ARG_NAME) as String

                            if (env.getSource()?."$property.name" == null) {
                                String propertyName = getPropertyNameForAssociation(association, env.getSource().class)
                                def id = env.getSource()."${entity.identity.name}"
                                def filterWithId = JSON.parse(filter ?: '{}') as Map<String, Object>
                                filterWithId.put(propertyName, [(entity.identity.name): id])
                                filter = genson.serialize(filterWithId)

                                List<Field> selections = (env.selectionSet.get().get(PAGED_RESULTS_NAME)
                                        ?.find { it.name == PAGED_RESULTS_NAME }
                                        ?.selectionSet?.selections as List<Field>)
                                        .findAll { it.name != associatedEntity.identity.name} ?: []

                                List<String> criteriaList =
                                        GqlToCriteria.transform(associatedEntity, selections, [
                                                (FILTER_ARG_NAME)   : filter
                                                , (SIZE_ARG_NAME)   : max
                                                , (SKIP_ARG_NAME)   : offset
                                                , (ORDERBY_ARG_NAME): env.getArgument(ORDERBY_ARG_NAME)
                                        ])
                                def count = Eval.me(criteriaList[0]) as Collection
                                def results = Eval.me(criteriaList[1]) as Collection
                                def entities = resultSetToEntities(results, associatedEntity, selections)
                                new PagedGraphResults(entities
                                        , max, offset, count[0] as int)
                            } else {
                                LinkedHashSet entities = env.getSource()."$property.name"
                                new PagedGraphResults(entities, max, offset, entities.size())
                            }
                        }
                    } }
                } else {
                    graphField = { field(property.name) {
                        type entityToType(associatedEntity, typeMap, false)
                    }}
                }
            } else {
                graphField = { field property.name, propertyToType(property) }
            }
            if(property.name != 'version' || entity.isVersioned()) fields.add( graphField )
        }
        def graphType
        if (paged) {
            graphType = pagedType(resultsName, simpleName, fields)
            typeMap.put(resultsName, graphType)
        } else {
            graphType = DSL.type(simpleName, mergeClosures(fields))
            typeMap.put(simpleName, graphType)
        }
        graphType
    }

    static GraphQLOutputType propertyToType(PersistentProperty property) {
        switch (property.class.simpleName) {
            case 'String': ScalarsAware.GraphQLString; break
            case 'Integer': ScalarsAware.GraphQLInt; break
            case 'Long': ScalarsAware.GraphQLLong; break
            case 'Short': ScalarsAware.GraphQLShort; break
            case 'Byte': ScalarsAware.GraphQLByte; break
            case 'Float': ScalarsAware.GraphQLFloat; break
            case 'BigInteger': ScalarsAware.GraphQLBigInteger; break
            case 'BigDecimal': ScalarsAware.GraphQLBigDecimal; break
            case 'Boolean': ScalarsAware.GraphQLBoolean; break
            case 'Char': ScalarsAware.GraphQLChar; break
            default: ScalarsAware.GraphQLString; break
        }
    }

    static boolean propertyIsCollection(PersistentProperty property) {
        Collection.isAssignableFrom(property?.type)
    }

    static Association getAssociation(PersistentEntity entity, String field) {
        entity.associations.find { it.name == field }
    }

    static List<String> getNonCollectionPropertyNames(PersistentEntity entity) {
        [entity.identity.name] +
        entity.persistentProperties.findAll {
            !propertyIsCollection(it) &&
                    (!entity.isVersioned() && it.name != 'version')
        }.collect { it.name }
    }

    static List<PersistentProperty> getCollectionProperties(PersistentEntity entity) {
            entity.persistentProperties.findAll {
                propertyIsCollection(it)
            }
    }

    static String getPropertyNameForAssociation(Association association, Class type) {
        if (association.isBidirectional()) {
            association.getReferencedPropertyName()
        } else {
            List<Association> associations = association.getAssociatedEntity().associations.findAll {
                it.getAssociatedEntity().javaClass == type
            }
            if (associations?.size() > 1 && association.isCircular()) {
                if (association.isCircular()) {
                    associations.find { it.name == association.name }?.name
                } else {
                    PropertyConfig propertyConfig = association.mapping.mappedForm as PropertyConfig
                    if (propertyConfig.joinTable?.name) {
                        associations.find { it.mapping.mappedForm.joinTable?.name == propertyConfig.joinTable.name }?.name
                    } else if (propertyConfig.column) {
                        associations.find { it.mapping.mappedForm.column == propertyConfig.column }?.name
                    } else {
                        null
                    }
                }
            } else {
                if (associations.isEmpty()) {
                    throw new PropertyNotFoundException(type.simpleName, association.associatedEntity.name)
                }
                else associations.get(0).name
            }
        }
    }

    static Closure mergeClosures(List<Closure> closures) {
        def c = null
        for(Closure it : closures) {
            if (c == null) {
                c = it
            } else {
                c = c << it
            }
        }
        c
    }
}
