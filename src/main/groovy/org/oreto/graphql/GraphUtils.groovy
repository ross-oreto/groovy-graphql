package org.oreto.graphql

import com.owlike.genson.Genson
import com.owlike.genson.GensonBuilder
import gql.DSL
import gql.dsl.ScalarsAware
import grails.converters.JSON
import graphql.language.ArrayValue
import graphql.language.Field
import graphql.language.NullValue
import graphql.schema.*
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
    static String PARAMS_ARG_NAME = 'params'
    static String ID_ARG_NAME = 'id'
    static String ORDERBY_ARG_NAME = 'orderBy'
    static String PAGED_RESULTS_NAME = 'results'
    static String PAGE_INFO_NAME = 'pageInfo'
    static String INFO_TOTAL_COUNT_NAME = 'totalCount'
    static String INFO_SIZE_NAME = 'size'
    static String INFO_SKIP_NAME = 'skip'
    static String INFO_PAGE_NAME = 'page'
    static String INFO_TOTAL_PAGES_NAME = 'totalPages'
    static String KEY_NAME = 'key'
    static String VALUE_NAME = 'value'

    static int DEFAULT_SIZE = 20
    static int MAX_SIZE = 1000

    static Genson genson = new GensonBuilder().useStrictDoubleParse(false).create()

    static GraphQLSchema createGqlSchema(Map<String, PersistentEntity> entities) {
        if (entities.isEmpty()) throw new Exception('GORM entities are required to create a graphql schema. None specified.')
        Map<String, GraphQLOutputType> typeMap

        Iterator<Map.Entry<String, PersistentEntity>> iterator = entities.entrySet().iterator()
        Map.Entry<String, PersistentEntity> entry = iterator.next()
        def entityQueries = entityToFilterQuery(entry.key, entry.value, (typeMap = [:]))
        entityQueries = entityQueries << entityToGetQuery(entry.value, typeMap)

        while(iterator.hasNext()) {
            entry = iterator.next()
            entityQueries = entityQueries << entityToFilterQuery(entry.key, entry.value, typeMap)
            entityQueries = entityQueries << entityToGetQuery(entry.value, typeMap)
        }

        iterator = entities.entrySet().iterator()
        entry = iterator.next()
        def mutationQueries = entityToMutationQuery(entry.value, typeMap)
        mutationQueries = mutationQueries << entityToDeleteQuery(entry.value, typeMap)
        while(iterator.hasNext()) {
            entry = iterator.next()
            mutationQueries = mutationQueries << entityToMutationQuery(entry.value, typeMap)
            mutationQueries = mutationQueries << entityToDeleteQuery(entry.value, typeMap)
        }

        DSL.schema( {
            queries(
                    entityQueries
            )
            mutations(
                    mutationQueries
            )
        })
    }

    static Closure entityToMutationQuery(PersistentEntity entity, Map<String, GraphQLOutputType> typeMap) {
        String fieldName = "save${entity.javaClass.simpleName}"
        Closure mutation = {
            field(fieldName) {
                type entityToType(entity, typeMap, false)
                argument(PARAMS_ARG_NAME, GraphQLString)
                fetcher { DataFetchingEnvironment env ->
                    def id = 0
                    entity.javaClass.withTransaction {
                        def params = JSON.parse((env.getArgument(PARAMS_ARG_NAME) ?: '{}') as String) as Map<String, Object>
                        String idName = entity.identity.name
                        def newEntity
                        if (params.containsKey(idName)) {
                            newEntity = entity.javaClass.get(params.get(idName))
                        }
                        if (newEntity == null) {
                            newEntity = entity.javaClass.newInstance()
                        }
                        dataBind(newEntity, params, entity.associations, entity)
                        newEntity.save(failOnError: true, flush: true)
                        id = newEntity."${idName}"
                    }
                    get(id, entity, env)
                }
            }
        }
        mutation
    }

    static Object dataBind(Object entity, Map<String, Object> params, List<Association> associations, PersistentEntity persistentEntity) {
        params.each { String key, Object value ->
            Association association = associations.find { key == it.name }
            if (value != null && association && association.associatedEntity) {
                PersistentEntity associatedEntity = association.associatedEntity
                String idName = associatedEntity.identity.name

                Collection<Map<String, Object>> objectMaps
                if (value instanceof Map) {
                    objectMaps = [value as Map<String, Object>]
                } else {
                    objectMaps = value as Collection<Map<String, Object>>
                }

                if (Collection.isAssignableFrom(association.type)) {
                    def l = []
                    l += entity."${key}"
                    l?.each { existing ->
                        if (existing && !objectMaps.find { it.containsKey(idName) && it.get(idName) == existing."$idName" }) {
                            entity."removeFrom${key.capitalize()}"(existing)
                            entity.markDirty(key)
                        }
                    }
                }

                objectMaps.each { Map<String, Object> objectMap ->
                    def newEntity = null
                    if (objectMap.containsKey(idName)) {
                        newEntity = associatedEntity.javaClass.get(objectMap.get(idName))
                    }
                    if (!newEntity) {
                        newEntity = associatedEntity.javaClass.newInstance()
                        newEntity."$idName" = objectMap.containsKey(idName) ? objectMap.get(idName) : null
                    }
                    dataBind(newEntity, objectMap, associatedEntity.associations, associatedEntity)
                    if (Collection.isAssignableFrom(association.type)) {
                        def idValue = newEntity."$idName"
                        if (idValue == null) {
                            entity."addTo${key.capitalize()}"(newEntity)
                            entity.markDirty(key)
                        } else if (!entity."${key}".find { it."$idName" == idValue }) {
                            entity."addTo${key.capitalize()}"(newEntity)
                            entity.markDirty(key)
                        }
                    } else {
                        entity."$key" = newEntity
                    }
                }
            } else {
                PersistentProperty persistentProperty = persistentEntity.getPropertyByName(key)
                if (persistentEntity) {
                    if (Date.isAssignableFrom(persistentProperty.type) || persistentProperty.type.simpleName == 'Date') {
                        String dateString = value as String
                        entity."$key" = dateString.contains(' ') ?
                                QueryUtils.dateTimeFormatter.parse(dateString) : QueryUtils.dateFormatter.parse(dateString)
                    } else {
                        entity."$key" = value
                    }
                }
            }
        }
        entity
    }

    static Closure entityToDeleteQuery(PersistentEntity entity, Map<String, GraphQLOutputType> typeMap) {
        String fieldName = "delete${entity.javaClass.simpleName}"
        Closure mutation = {
            field(fieldName) {
                type entityToType(entity, typeMap, false)
                argument(ID_ARG_NAME, propertyToType(entity.identity))
                fetcher { DataFetchingEnvironment env ->
                    def id = env.getArgument(ID_ARG_NAME)
                    def deletedEntity = get(id, entity, env)
                    entity.javaClass.withTransaction  {
                        deletedEntity?.delete()
                        //entity.javaClass.get(id)?.delete()
                    }
                    deletedEntity
                }
            }
        }
        mutation
    }

    static Closure entityToGetQuery(PersistentEntity entity, Map<String, GraphQLOutputType> typeMap) {
        String fieldName = "get${entity.javaClass.simpleName}"
        Closure query = {
            field(fieldName) {
                type entityToType(entity, typeMap, false)
                argument(entity.identity.name, propertyToType(entity.identity))
                fetcher { DataFetchingEnvironment env ->
                    get(env.getArgument(entity.identity.name), entity, env)
                }
            }
        }
        query
    }

    static get(def id, PersistentEntity entity, DataFetchingEnvironment env) {
        List<Field> selections = env.selectionSet.get()
                .findAll { !it.key.contains('/') }.collect { it.value[0] }

        def idVal = entity.identity.type.simpleName == 'String' ? "'$id'" : id
        List<Object> criteriaList =
                GqlToCriteria.transform(entity
                        , selections
                        , [(FILTER_ARG_NAME) : "{ ${entity.identity.name}:$idVal }"
                           , (SIZE_ARG_NAME) : 1
                           , (SKIP_ARG_NAME) : 0
                           , (ORDERBY_ARG_NAME) : null], false)
        def results = criteriaList[1] as Collection
        def entities = resultSetToEntities(results, entity, fieldsWithoutId(selections, entity))
        if (entities.size() > 0) {
            eagerFetch(entities, entity, selections)
        }
        entities?.size() ? entities[0] : null
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

                    List<Object> criteriaList =
                        GqlToCriteria.transform(entity
                                , selections
                                , [(FILTER_ARG_NAME) : env.getArgument(FILTER_ARG_NAME)
                                , (SIZE_ARG_NAME) : max
                                , (SKIP_ARG_NAME) : offset
                                , (ORDERBY_ARG_NAME) : env.getArgument(ORDERBY_ARG_NAME)
                        ])
                    def count = criteriaList[0] as int
                    def results = criteriaList[1] as Collection
                    def entities = resultSetToEntities(results, entity, fieldsWithoutId(selections, entity))
                    if (entities.size() > 0) {
                        eagerFetch(entities as Collection, entity, selections)
                    }
                    new PagedGraphResults(entities, max, offset, count)
                }
            }
        }
        filterQuery
    }

    static int DEFAULT_BATCH_SIZE = 200

    static eagerFetch(Collection entities, PersistentEntity persistentEntity, List<Field> selections) {
        def ids = entities.collect { it?."${persistentEntity.identity.name}" }
        for (Field selection : selections) {
            def results = (selection.selectionSet?.selections as List<Field>)?.find { it.name == PAGED_RESULTS_NAME }
            if (results) {
                def filterArg = selection.arguments.find { it.name == FILTER_ARG_NAME }?.value
                def sizeArg = selection.arguments.find { it.name == SIZE_ARG_NAME }?.value
                def skipArg = selection.arguments.find { it.name == SKIP_ARG_NAME }?.value

                String filter = filterArg instanceof NullValue ? '{}' : filterArg?.value ?: '{}'
                int max = sizeArg instanceof NullValue ? DEFAULT_SIZE : sizeArg?.value ?: DEFAULT_SIZE
                if (max > MAX_SIZE) max = MAX_SIZE
                int offset = skipArg instanceof NullValue ? 0 : skipArg?.value ?: 0

                List<Field> resultSelections = results.selectionSet?.selections as List<Field>
                Association association = getAssociation(persistentEntity, selection.name)

                int batchSize = GrailsDomainBinder.getMapping(association.associatedEntity?.javaClass)?.batchSize ?: DEFAULT_BATCH_SIZE
                L.debug("${association.associatedEntity?.javaClass?.simpleName ?: association.name} batch size: $batchSize")

                List eagerResults = []
                def orderByArg = selection.arguments.find { it.name == ORDERBY_ARG_NAME }?.value
                List<String> orderBy = orderByArg == null || orderByArg instanceof NullValue ? [] :
                        orderByArg instanceof ArrayValue ? orderByArg?.values?.collect { it.value as String } : [orderByArg.value]

                int i = 1
                ids.collate(batchSize).each { idBatch ->
                    L.debug("batch: $i")
                    List criteria = GqlToCriteria.transformEagerBatch(persistentEntity
                            , idBatch
                            , association
                            , resultSelections
                            , [(FILTER_ARG_NAME) : filter
                               , (SIZE_ARG_NAME) : 1000000
                               , (SKIP_ARG_NAME) : 0
                               , (ORDERBY_ARG_NAME) : orderBy]
                    )
                    if (eagerResults) {
                        eagerResults.addAll(criteria)
                    }
                    else {
                        eagerResults = criteria
                    }
                    i++
                }
                if (eagerResults?.size()) {
                    def entityMap = resultSetToEntityMap(eagerResults
                            , association.associatedEntity
                            , fieldsWithoutId(resultSelections, association.associatedEntity))
                    def propertyName = selection.name
                    entities.each {
                        def id = it."${persistentEntity.identity.name}"
                        if (entityMap.containsKey(id)) {
                            def childEntities = entityMap.get(id)
                            it._sizes.put(propertyName, childEntities?.size() ?: 0)
                            it."$propertyName" = childEntities.sort{ a, b ->
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
                            it._sizes.put(propertyName, 0)
                        }
                    }
                    if (entityMap.size() > 0) {
                        def newEntities = []
                        entityMap.each { newEntities.addAll(it.value) }
                        if (newEntities.size()) {
                            eagerFetch(newEntities, association.associatedEntity, resultSelections)
                        }
                    }
                } else {
                    def propertyName = selection.name
                    entities.each {
                        it."$propertyName" = []
                    }
                }
            } else if (selection.selectionSet) {
                def propertyName = selection.name
                Association association = getAssociation(persistentEntity, propertyName)
                def subEntity = association.associatedEntity
                String idName = subEntity.identity.name
                boolean idIsString = subEntity.identity.type.simpleName == 'String'

                def subIds = []
                entities.each {
                    if (it."$propertyName" != null) {
                        def idValue = it."$propertyName"."$idName"
                        subIds.add(idIsString ? "'$idValue'" : idValue)
                    }
                }
                List<Field> resultSelections = selection.selectionSet?.selections as List<Field>
                List eagerResults = subIds ? GqlToCriteria.transform(subEntity
                        , resultSelections
                        , [(FILTER_ARG_NAME) : "{ ${idName}_in: [${subIds.join(',')}]}"
                           , (SIZE_ARG_NAME) : 1000000
                           , (SKIP_ARG_NAME) : 0
                           , (ORDERBY_ARG_NAME) : idName], false
                )[1] as List : []
                def newEntities = resultSetToEntities(eagerResults
                        , subEntity
                        , fieldsWithoutId(resultSelections, subEntity))
                def matchedEntities = []
                entities.each { e ->
                    def id = e."$propertyName"?."${persistentEntity.identity.name}"
                    def newEntity = newEntities.find { it."${persistentEntity.identity.name}" == id }
                    if (id && newEntity) {
                        e."$propertyName" = newEntity
                        matchedEntities.add(newEntity)
                    }
                }
                if (newEntities.size() > 0) {
                    eagerFetch(matchedEntities, subEntity, resultSelections)
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
                type GraphUtils.PageInfo
            }
        }
    }

    static GraphQLOutputType pagedBasicListType(String name, String listName) {
        DSL.type(name) {
            field(PAGED_RESULTS_NAME) {
                type list(DSL.type(listName) {
                    field(VALUE_NAME) {
                        description('item value')
                        type GraphQLString
                    }
                })
            }
            field(PAGE_INFO_NAME) {
                type GraphUtils.PageInfo
            }
        }
    }

    static GraphQLOutputType pagedBasicMapType(String name, String listName) {
        DSL.type(name) {
            field(PAGED_RESULTS_NAME) {
                type list(DSL.type(listName, {
                    field(KEY_NAME) {
                        description('item key')
                        type GraphQLString
                    }
                    field(VALUE_NAME) {
                        description('item value')
                        type GraphQLString
                    }
                }))
            }
            field(PAGE_INFO_NAME) {
                type GraphUtils.PageInfo
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
                    //def selections = selectionsWithoutId(it, entity)
                    def associatedEntity = entity.getAssociations().find{ it.name == name }.associatedEntity
                    (association, i) = _resultSetToEntities(associatedEntity, [], row, i, entities)
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
                    if (!associatedEntity) {
                        println("collection of basic type ${association.type}:${association.name}")
                        String listName = "${association.name}List"
                        String resultsListName = "${listName}Results"
                        def typeRef
                        if (typeMap.containsKey(resultsListName)) {
                            typeRef = typeMap.get(resultsListName)
                        } else {
                            typeRef = new GraphQLTypeReference(resultsListName)
                            typeMap.put(resultsListName, typeRef)
                            if (Map.isAssignableFrom(property?.type)) {
                                typeRef = pagedBasicMapType(resultsListName, listName)
                            } else {
                                typeRef = pagedBasicListType(resultsListName, listName)
                            }
                        }
                        graphField = { field(property.name) {
                            type typeRef
                            argument(FILTER_ARG_NAME, GraphQLString)
                            argument(SIZE_ARG_NAME, GraphQLInt)
                            argument(SKIP_ARG_NAME, GraphQLInt)
                            argument(ORDERBY_ARG_NAME, list(GraphQLString))
                            fetcher { DataFetchingEnvironment env ->
                                int max = env.getArgument(SIZE_ARG_NAME) ?: DEFAULT_SIZE
                                max = max > MAX_SIZE ? MAX_SIZE : max
                                int offset = (env.getArgument(SKIP_ARG_NAME) ?: 0) as int
                                //String filter = env.getArgument(FILTER_ARG_NAME) as String

                                def mock = [new BasicListType(value: 'Misty')
                                            , new BasicListType(value: 'Leo')
                                            , new BasicListType(value: 'Jake')]
                                new PagedGraphResults(mock, max, offset, mock.size())
                            }
                        }
                        }
                        fields.add( graphField )
                    } else {
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

                                    List<Object> criteriaList =
                                            GqlToCriteria.transform(associatedEntity, selections, [
                                                    (FILTER_ARG_NAME)   : filter
                                                    , (SIZE_ARG_NAME)   : max
                                                    , (SKIP_ARG_NAME)   : offset
                                                    , (ORDERBY_ARG_NAME): env.getArgument(ORDERBY_ARG_NAME)
                                            ])
                                    def count = criteriaList[0] as int
                                    def results = criteriaList[1] as Collection
                                    def entities = resultSetToEntities(results, associatedEntity, selections)
                                    new PagedGraphResults(entities
                                            , max, offset, count)
                                } else {
                                    LinkedHashSet entities = []
                                    env.getSource().class.withTransaction {
                                        entities = env.getSource()."$property.name"
                                    }
                                    int count = env.getSource()._sizes.get(property.name) ?: 0
                                    new PagedGraphResults(entities, max, offset, count)
                                }
                            }
                        } }
                    }
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

    static GraphQLOutputType typeToGraphType(Class type, String propertyName) {
        GraphQLOutputType gtype
        String typeName = type.simpleName
        switch (typeName) {
            case 'String':
                gtype = ScalarsAware.GraphQLString
                break
            case 'Integer':
                gtype = ScalarsAware.GraphQLInt
                break
            case 'Long':
                gtype = ScalarsAware.GraphQLLong
                break
            case 'Short':
                gtype = ScalarsAware.GraphQLShort
                break
            case 'Byte':
                gtype = ScalarsAware.GraphQLByte
                break
            case 'Float':
                gtype = ScalarsAware.GraphQLFloat
                break
            case 'BigInteger':
                gtype = ScalarsAware.GraphQLBigInteger
                break
            case 'BigDecimal':
                gtype = ScalarsAware.GraphQLBigDecimal
                break
            case 'Boolean':
                gtype = ScalarsAware.GraphQLBoolean
                break
            case 'Char':
                gtype = ScalarsAware.GraphQLChar
                break
            case { it == 'byte[]' || it == 'Byte[]' && propertyName.toLowerCase().endsWith('image')}:
                gtype = GraphQLByteString
                break
            case 'Date':
                gtype = GraphQLDate
                break
            default:
                gtype = ScalarsAware.GraphQLString
                break
        }
        gtype
    }

    static GraphQLScalarType GraphQLDate = DSL.scalar('DateTime') {
        serialize { Date date ->
            date.format(GqlToCriteria.dateTimeFormat)
        }
        parseLiteral { value ->
            String dateString = value.value as String
            dateString.contains(' ') ? QueryUtils.dateTimeFormatter.parse(dateString) : QueryUtils.dateFormatter.parse(dateString)
        }
        parseValue { String value ->
            String dateString = value as String
            dateString.contains(' ') ? QueryUtils.dateTimeFormatter.parse(dateString) : QueryUtils.dateFormatter.parse(dateString)
        }
    }

    static GraphQLOutputType propertyToType(PersistentProperty property) {
        typeToGraphType(property.type, property.name)
    }

    static GraphQLScalarType GraphQLByteString = DSL.scalar('Base64ByteString') {
        serialize { byte[] bytes ->
            Base64.encoder.encodeToString(bytes)
        }
        parseLiteral { value ->
            Base64.decoder.decode(value.value as String)
        }
        parseValue { String value ->
            Base64.decoder.decode(value)
        }
    }

    static boolean propertyIsCollection(PersistentProperty property) {
        Class type = property?.type
        Collection.isAssignableFrom(type) ||
                Set.isAssignableFrom(type) ||
                List.isAssignableFrom(type) ||
                Map.isAssignableFrom(type) ||
                Iterable.isAssignableFrom(type)
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
