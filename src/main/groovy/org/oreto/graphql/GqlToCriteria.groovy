package org.oreto.graphql

import grails.converters.JSON
import graphql.language.Field
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.Association
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class GqlToCriteria {

    private static final Logger L = LoggerFactory.getLogger(GqlToCriteria.class)

    static String EQ = 'eq'
    static String IN_LIST = 'inList'
    static String IS_EMPTY = 'isEmpty'
    static String LT = 'lt'
    static String GT = 'gt'
    static String LE = 'le'
    static String GE = 'ge'
    static String LIKE = 'like'
    static String ILIKE = 'ilike'
    static String BETWEEN = 'between'

    static filterOpToCriteria = [(QueryUtils.DEFAULT)             : EQ
                                 , (QueryUtils.IN)                : IN_LIST
                                 , 'empty'                        : IS_EMPTY
                                 , (QueryUtils.LESS_THAN)         : LT
                                 , (QueryUtils.GREATER_THAN)      : GT
                                 , (QueryUtils.LESS_THAN_EQUAL)   : LE
                                 , (QueryUtils.GREATER_THAN_EQUAL): GE
                                 , (QueryUtils.BETWEEN_OP): BETWEEN] + QueryUtils.substringOpMap

    static filterOpToGroovy = [
            (QueryUtils.DEFAULT): GroovyOp.EQ
            , (QueryUtils.IN): GroovyOp.IN_LIST
            , (QueryUtils.LESS_THAN): GroovyOp.LT
            , (QueryUtils.GREATER_THAN): GroovyOp.GT
            , (QueryUtils.LESS_THAN_EQUAL): GroovyOp.LE
            , (QueryUtils.GREATER_THAN_EQUAL): GroovyOp.GE
            , (QueryUtils.CONTAINS_OP): GroovyOp.LIKE
            , (QueryUtils.STARTS_WITH_OP): GroovyOp.LIKE
            , (QueryUtils.ENDS_WITH_OP): GroovyOp.LIKE
            , (ILIKE): GroovyOp.ILIKE
            , (QueryUtils.BETWEEN_OP): BETWEEN
    ]

    static criteriaOpToGroovy = [
            (EQ): GroovyOp.EQ
            , (LT): GroovyOp.LT
            , (GT): GroovyOp.GT
            , (LE): GroovyOp.LE
            , (GE): GroovyOp.GE
            , (LIKE): GroovyOp.LIKE
            , (ILIKE): GroovyOp.ILIKE
    ]

    static List<String> transform(PersistentEntity entity
                                  , List<Field> selections
                                  , Map<String, Object> queryArgs) {
        StringBuilder sb = new StringBuilder("${entity.javaClass.name}.withTransaction {\n")
        List objects = ['withTransaction']
        appendToCriteria("${entity.javaClass.name}.where {", sb, objects)
        objects.add(entity.javaClass.name)

        String filter = queryArgs.get('filter') as String
        try {
            _transform(entity, '', JSON.parse(filter ?: '{}') as Map<String, Object>, sb, objects)
        } catch (Exception exception) {
            throw new FilterException(exception.message + ":" + exception?.cause?.message)
        }
        //addAliasSelections(selections, '', sb, aliasMap, objects)
        String countCriteria = "${sb.toString()}\t\tprojections { countDistinct('${entity.identity.name}') }\n\t}.list()\n}"
        def orderByArg = queryArgs.get(GraphUtils.ORDERBY_ARG_NAME)
        List<String> orderBy = orderByArg instanceof Collection ? orderByArg as List<String> : [orderByArg as String]

        appendToCriteria("projections {", sb, objects)
        objects.add('projections')

        appendToCriteria("distinct('${entity.identity.name}')", sb, objects)
        addProjectionSelections(entity
                , GraphUtils.fieldsWithoutId(selections, entity)
                , ''
                , sb
                , objects)

        objects.pop()
        appendToCriteria("}", sb, objects)

        QueryUtils.parseOrderBy(orderBy, entity).each {
            appendToCriteria("order('${it.key}', '${it.value}')", sb, objects)
        }
        objects.pop()
        int max = queryArgs.get(GraphUtils.SIZE_ARG_NAME) as int
        int offset = queryArgs.get(GraphUtils.SKIP_ARG_NAME) as int
//        appendToCriteria("maxResults($max)", sb, objects)
//        appendToCriteria("firstResult($offset)", sb, objects)
        appendToCriteria("}.list([max:$max, offset:$offset])", sb, objects)
        objects.pop()
        appendToCriteria('}', sb, objects)

        String pagedCriteria = sb.toString()
        String message = """
------------------------------------------------------------------
$countCriteria
------------------------------------------------------------------
$pagedCriteria------------------------------------------------------------------"""
        L.debug(message)
        [countCriteria, pagedCriteria]
    }

    static String transformEagerBatch(PersistentEntity parentEntity
                                      , Collection ids
                                      , Association association
                                      , List<Field> selections
                                      , Map<String, Object> queryArgs
                                      , boolean count = false) {

        PersistentEntity entity = association.associatedEntity
        StringBuilder sb = new StringBuilder("${entity.javaClass.name}.withTransaction {\n")
        List objects = ['withTransaction']
        appendToCriteria("${entity.javaClass.name}.where {", sb, objects)
        objects.add(entity.javaClass.name)

        String filter = queryArgs.get('filter') as String
        try {
            _transform(entity, '', JSON.parse(filter ?: '{}') as Map<String, Object>, sb, objects)
        } catch (Exception exception) {
            throw new FilterException(exception.message + ":" + exception?.cause?.message)
        }
        String propertyName = GraphUtils.getPropertyNameForAssociation(association, parentEntity.javaClass)
        appendToCriteria("$propertyName {", sb, objects)
        objects.add(propertyName)
        appendToCriteria("or {", sb, objects)
        objects.add('or')
        ids.each {
            def value = resolveValue(it, '', parentEntity.identity.type.simpleName)
            appendToCriteria("${filterOpToCriteria.get(QueryUtils.DEFAULT)}('${parentEntity.identity.name}', $value)"
                    , sb, objects)
        }
        objects.pop()
        appendToCriteria("}", sb, objects)

        objects.pop()
        appendToCriteria('}', sb, objects)

        if (!count) {
            appendToCriteria("createAlias('$propertyName', '$propertyName')", sb, objects)
            appendToCriteria("projections {", sb, objects)
            objects.add('projections')
            appendToCriteria("property('${propertyName}.${parentEntity.identity.name}')", sb, objects)
            appendToCriteria("property('${entity.identity.name}')", sb, objects)
            addProjectionSelections(entity
                    , GraphUtils.fieldsWithoutId(selections, entity)
                    , ''
                    , sb
                    , objects)
            objects.pop()
            appendToCriteria('}', sb, objects)
        }

        if (count) {
            objects.pop()
            appendToCriteria('}.count()', sb, objects)
            objects.pop()
            appendToCriteria('}', sb, objects)
        } else {
            def orderByArg = queryArgs.get(GraphUtils.ORDERBY_ARG_NAME)
            List<String> orderBy = orderByArg instanceof Collection ? orderByArg as List<String> : [orderByArg as String]
            QueryUtils.parseOrderBy(orderBy, entity).each {
                appendToCriteria("order('${it.key}', '${it.value}')", sb, objects)
            }
            objects.pop()
            int max = queryArgs.containsKey(GraphUtils.SIZE_ARG_NAME) ? queryArgs.get(GraphUtils.SIZE_ARG_NAME) as int : 100
            int offset = queryArgs.containsKey(GraphUtils.SIZE_ARG_NAME) ?  queryArgs.get(GraphUtils.SKIP_ARG_NAME) as int : 0
            appendToCriteria("}.list([max:$max, offset:$offset])", sb, objects)
            objects.pop()
            appendToCriteria('}', sb, objects)
        }
        def criteriaString = sb.toString()
        String message = """
------------------------------------------------------------------
$criteriaString------------------------------------------------------------------
"""
        L.debug(message)
        criteriaString
    }

    static void addProjectionSelections(PersistentEntity entity
                                        , List<Field> selections
                                        , String path
                                        , StringBuilder sb
                                        , List objects) {
        selections.each {
            def property = path ? "${path}.${it.name}" : it.name
            def results = (it.selectionSet?.selections as List<Field>)?.find { it.name == GraphUtils.PAGED_RESULTS_NAME}
            if (results) {
                // eager selecting one to many or many to many is problematic for paging and performance
//                def associatedEntity = GraphUtils.getAssociation(entity, it.name)?.associatedEntity
//                addProjectionSelections(associatedEntity
//                        , GraphUtils.selectionsWithoutId(results, associatedEntity), path, sb, objects)
            } else if (it.selectionSet) {
                appendToCriteria("property('$property')", sb, objects)
                def associatedEntity = GraphUtils.getAssociation(entity, it.name)?.associatedEntity
                addProjectionSelections(associatedEntity
                        , GraphUtils.selectionsWithoutId(it, associatedEntity), property, sb, objects)
            } else {
                if(!it.name.startsWith('__') && !path) {
                    appendToCriteria("property('$property')", sb, objects)
                }
            }
        }
    }

    private static _transform(PersistentEntity entity
                              , String path
                              , Map jsonObject
                              , StringBuilder sb
                              , List objects = []
                              , Map vars = [:]) {
        jsonObject.each { k, v ->
            if (k == 'AND' || k == 'OR') {
                def combinator = k.toString().toLowerCase()
                appendToCriteria("$combinator {", sb, objects)
                objects.add(combinator)
                if (!(v instanceof Collection)) {
                    throw new FilterException('AND/OR combinator must be a collection')
                }
                (v as Collection).each { obj ->
                    _transform(entity, path, obj as Map, sb, objects, vars)
                }
                objects.pop()
                appendToCriteria('}', sb, objects)
            } else {
                def (String field, String operation, boolean negate, boolean ignoreCase, boolean any) = QueryUtils.parseFieldOp(k)
                PersistentProperty property = entity.getPropertyByName(field)
                QueryUtils.validateFilterQuery(field, property, entity, operation, v, negate, ignoreCase, any)

                if (v instanceof Map) {
                    Association association = GraphUtils.getAssociation(entity, field)
                    PersistentEntity associatedEntity = association?.associatedEntity
                    if (operation == QueryUtils.CONTAINS_OP && GraphUtils.propertyIsCollection(property)) {
                        String className = entity.javaClass.name
                        String varName = "${entity.javaClass.simpleName.toLowerCase()}${objects.size()}"
                        if (!vars.containsKey(varName)) {
                            appendToCriteria("def $varName = $className", sb, objects)
                            vars.put(varName, varName)
                        }

                        def jsonValue = v as Map
                        jsonValue.each { key, val ->
                            def (String f, String o, boolean n, boolean i, boolean a) = QueryUtils.parseFieldOp(key)
                            def p = associatedEntity.getPropertyByName(f)

                            String subClassName = associatedEntity.javaClass.name
                            String propertyName = GraphUtils.getPropertyNameForAssociation(association, entity.javaClass)

                            if (a) {
                                appendToCriteria('or {', sb, objects)
                                objects.add('or')
                            }

                            (val as Collection).each {
                                QueryUtils.validateFilterQuery(f, p, associatedEntity, o, it, n, i, a)
                                if (negate) appendToCriteria("not {", sb, objects)
                                appendToCriteria("exists ${subClassName}.where { ", sb, objects)
                                String expression
                                def groovyOp = filterOpToGroovy.get(o) ?: filterOpToGroovy.get(QueryUtils.DEFAULT)
                                boolean subNegate = n
                                objects.add('')
                                String subVarName = "${associatedEntity.javaClass.simpleName.toLowerCase()}${objects.size()}"
                                String type = p.type.simpleName
                                if (groovyOp == BETWEEN) {
                                    Collection range = it as Collection
                                    String val1 = resolveSingleValue(range[0], type)
                                    String val2 = resolveSingleValue(range[1], type)
                                    expression = "$f ${GroovyOp.GE} ${val1} && $f ${GroovyOp.LE} ${val2}"
                                } else {
                                    def value = resolveValue(it, o, type)
                                    if (subNegate) {
                                        if (GroovyOp.negateOp.get(groovyOp)) {
                                            groovyOp = GroovyOp.negateOp.get(groovyOp)
                                            subNegate = false
                                        }
                                    }
                                    expression = i ? "$f ${filterOpToGroovy.get(ILIKE)} $value" : "$f $groovyOp $value"
                                }
                                if (subNegate) expression = "!($expression)"
                                appendToCriteria("def $subVarName = $subClassName", sb, objects)
                                vars.put(subVarName, subVarName)
                                appendToCriteria("return $expression && ${propertyName}{${entity.identity.name} == ${varName}.${associatedEntity.identity.name}}"
                                        , sb
                                        , objects)
                                objects.pop()
                                appendToCriteria("}.id()", sb, objects)
                                if (negate) appendToCriteria("}", sb, objects)
                            }
                            if (a) {
                                objects.pop()
                                appendToCriteria('}', sb, objects)
                            }
                        }
                    } else {
                        appendToCriteria("$field { ", sb, objects)
                        objects.add(field)

                        _transform(associatedEntity, field, v as Map, sb, objects, vars)

                        objects.pop()
                        appendToCriteria('}', sb, objects)
                    }
                } else {
                    if (v == null) {
                        String nullOp = GraphUtils.propertyIsCollection(property) ? 'isEmpty' : 'isNull'
                        String expression = "$nullOp('$field')"
                        appendToCriteria(negate ? negateExpression(expression) : expression, sb, objects)
                    } else if (v instanceof Collection && (operation != QueryUtils.IN && operation != QueryUtils.BETWEEN_OP)) {
                        Collection arrayValue = v as Collection
                        if (QueryUtils.isValueProperty(arrayValue)) {
                            appendCriteriaExpression(arrayValue, operation, property, field, negate, ignoreCase, sb, objects)
                        } else {
                            def combinator = any ? 'or' : 'and'
                            appendCriteriaExpressions(arrayValue, operation, property, field, negate, ignoreCase, sb, objects, combinator)
                        }
                    } else if (operation == QueryUtils.IN && v instanceof Collection && (v as Collection).size() == 1){
                        appendCriteriaExpression(v[0], QueryUtils.DEFAULT, property, field, negate, ignoreCase, sb, objects)
                    } else {
                        appendCriteriaExpression(v, operation, property, field, negate, ignoreCase, sb, objects)
                    }
                }
            }
        }
    }

    static appendCriteriaExpressions(Collection values
                                     , String operation
                                     , PersistentProperty property
                                     , String field
                                     , boolean negate
                                     , boolean ignoreCase
                                     , StringBuilder sb
                                     , List objects
                                     , String combinator) {
        appendToCriteria("$combinator {", sb, objects)
        objects.add(combinator)
        values.each { obj ->
            appendCriteriaExpression(obj, operation, property, field, negate, ignoreCase, sb, objects)
        }
        objects.pop()
        appendToCriteria('}', sb, objects)
    }

    static appendCriteriaExpression(Object v
                                    , String operation
                                    , PersistentProperty property
                                    , String field
                                    , boolean negate
                                    , boolean ignoreCase
                                    , StringBuilder sb
                                    , List objects) {
        boolean valueIsProperty = QueryUtils.isValueProperty(v)
        if (!valueIsProperty && v instanceof Collection) {
            valueIsProperty = (v as Collection).find { QueryUtils.isValueProperty(it) } != null
        }
        if (valueIsProperty) {
            def groovyOp = filterOpToGroovy.get(ignoreCase ? ILIKE : operation) ?: filterOpToGroovy.get(QueryUtils.DEFAULT)
            String expression
            if (groovyOp == BETWEEN) {
                Collection range = v as Collection
                String val1 = resolveSingleValue(range[0], property.type.simpleName)
                String val2 = resolveSingleValue(range[1], property.type.simpleName)
                expression = "$field ${GroovyOp.GE} ${val1} && $field ${GroovyOp.LE} ${val2}"
            } else {
                expression = "$field $groovyOp ${resolveValue(v, operation, property.type.simpleName)}"
            }
            appendToCriteria(negate ? "!($expression)" : expression, sb, objects)
        } else {
            def value = resolveValue(v, operation, property.type.simpleName)
            String expression = "${ignoreCase ? ILIKE : filterOpToCriteria.get(operation)}('$field', $value)"
            appendToCriteria(negate ? negateExpression(expression) : expression, sb, objects)
        }
    }

    static String negateExpression(String s) {
        "not { $s }"
    }

    static void appendToCriteria(String s, StringBuilder sb, List objs){
        String tabs = objs.collect{ ' ' }.join('')
        sb.append("$tabs$s\n" as String)
    }

    static String resolveValue(Object v, String op, String type) {
        switch (op) {
            case QueryUtils.CONTAINS_OP: v = "%$v%".toString(); break
            case QueryUtils.STARTS_WITH_OP: v = "$v%".toString(); break
            case QueryUtils.ENDS_WITH_OP: v = "%$v".toString(); break
        }
        if (op == BETWEEN) {
            Collection value = v as Collection
            "${resolveSingleValue(value[0], type)}, ${resolveSingleValue(value[1], type)}"
        } else {
            (v instanceof Collection && !QueryUtils.isValueProperty(v)) ? resolveArrayValue(v, type) : resolveSingleValue(v, type)
        }
    }

    static String format1 = 'MM-dd-yyyy HH:mm:ss'
    static String format2 = 'MM-dd-yyyy'

    static String resolveSingleValue(Object v, String type) {
        String s = v.toString()
        if (QueryUtils.isValueProperty(v)) v[0]
        else if (type == 'Date') {
            if (s.contains(' ')) "new java.text.SimpleDateFormat('$format1').parse('$s')"
            else "new java.text.SimpleDateFormat('$format2').parse('$s')"
        } else v instanceof String ? "'$s'" : (s.isNumber() ? toNumberWrapper(v, type) : s)
    }

    static String toNumberWrapper(Object v, String type) {
        switch (type) {
            case 'Integer': v.toString(); break
            case 'Long': "new Long($v)"; break
            case 'Short': "new Short($v)"; break
            case 'Double': "new Double($v)"; break
            case 'Float': "new Float($v)"; break
            case 'BigDecimal': "new BigDecimal($v)"; break
            case 'BigInteger': "new BigInteger($v)"; break
            default: v.toString(); break
        }
    }

    static String resolveArrayValue(Object v, String type) {
        def l = '[', r = ']'
        l + (v as Collection).collect { resolveSingleValue(it, type) }.join(', ') + r
    }
}

class GroovyOp {
    static String EQ = '=='
    static String IN_LIST = 'in'
    static String LT = '<'
    static String GT = '>'
    static String LE = '<='
    static String GE = '>='
    static String LIKE = '==~'
    static String ILIKE = '=~'

    static Map negateOp = [
            (EQ): '!='
            , (LT): GE
            , (GT): LE
            , (LE): GT
            , (GE): LT
    ]
}
