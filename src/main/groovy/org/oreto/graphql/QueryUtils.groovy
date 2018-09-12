package org.oreto.graphql

import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty

import java.text.ParsePosition

class QueryUtils {

    static DEFAULT = ''
    static LESS_THAN = 'lt'
    static LESS_THAN_EQUAL = 'lte'
    static GREATER_THAN = 'gt'
    static GREATER_THAN_EQUAL = 'gte'
    static IN = 'in'
    static CONTAINS_OP = 'contains'
    static STARTS_WITH_OP = 'starts_with'
    static ENDS_WITH_OP = 'ends_with'
    static BETWEEN_OP = 'between'

    static filterOps = [DEFAULT
                        , LESS_THAN
                        , LESS_THAN_EQUAL
                        , GREATER_THAN
                        , GREATER_THAN_EQUAL
                        , IN
                        , CONTAINS_OP
                        , STARTS_WITH_OP
                        , ENDS_WITH_OP
                        , BETWEEN_OP]

    static substringOpMap = [(CONTAINS_OP): GqlToCriteria.LIKE
                             , (STARTS_WITH_OP): GqlToCriteria.LIKE
                             , (ENDS_WITH_OP): GqlToCriteria.LIKE]

    static parseFieldOp(def k) {
        def fieldOp = k.toString().split('_', 2)
        String field = fieldOp[0]
        String op = fieldOp.size() > 1 ? fieldOp[1] : ''
        boolean negate = false
        boolean ignoreCase = false
        boolean any = false
        if (op.startsWith('not')) {
            negate = true
            op = op.replaceFirst('not(_)?', '')
        }
        if (op.startsWith('i_') || op == 'i') {
            ignoreCase = true
            op = op.replaceFirst('i(_)?', '')
        }
        if (op.endsWith('_any') || op == 'any') {
            any = true
            op = op.replaceFirst('(_)?any', '')
        }

        [field, op, negate, ignoreCase, any]
    }

    static Map<String, String> parseOrderBy(List<String> orderBy, PersistentEntity persistentEntity) {
        orderBy?.collectEntries {
            def (sort, order) = parseOrderByString(it, persistentEntity)
            [(sort): order]
        }
    }

    static List<String> parseOrderByString(String orderBy, PersistentEntity persistentEntity) {
        def sortOrder = orderBy?.split('_')
        def sort = sortOrder ? sortOrder[0] : persistentEntity.identity.name
        if (!persistentEntity.getPropertyByName(sort)) {
            throw new PropertyNotFoundException(sort, persistentEntity.name)
        }
        def order = sortOrder && sortOrder.size() > 1 ? sortOrder[1] : 'desc'
        [sort, order]
    }

    static validateOp = { op ->
        if (!filterOps.contains(op)) {
            throw new FilterException("$op is not a valid operator")
        }
    }

    static validateFilterQuery(String field, PersistentProperty property, PersistentEntity persistentEntity
                               , String op, Object val, boolean negate
                               , boolean ignoreCase, boolean any) {
        if (!property) {
            throw new PropertyNotFoundException(field, persistentEntity.name)
        }
        validateOp(op)

        if(op == CONTAINS_OP && GraphUtils.propertyIsCollection(property)) {
            if (any || ignoreCase) {
                throw new FilterException("$op does not support the (i/any) modifiers when $field is a collection")
            }
            def msg = "$op requires the value to be in the form { field:[x, y] } when $field is a collection"
            if (val instanceof Map) {
                Map jsonVal = val as Map
                jsonVal.keySet().each {
                    (field, op, negate, ignoreCase, any) = parseFieldOp(it)
                    def groovyOp = GqlToCriteria.filterOpToGroovy.get(op)
                    if (!groovyOp) throw new FilterException("$op cannot be used here")
                }
                jsonVal.values().each {
                    if (!(it instanceof Collection)) {
                        throw new FilterException(msg)
                    }
                }
            } else {
                throw new FilterException(msg)
            }
        } else {
            if (op == IN) {
                if (!(val instanceof Collection)) {
                    throw new FilterException("$op requires $field value to be a list, instead of ${val?.class?.simpleName}")
                }
                if (any) {
                    throw new FilterException("$op does not support the any modifier")
                }
                (val as Collection).each {
                    if (isValueProperty(it)) {
                        throw new FilterException("$op does not support property values")
                    }
                }
            } else if (op == BETWEEN_OP) {
                if (any || ignoreCase) {
                    throw new FilterException("$op does not support the (i/any) modifiers when $field is a collection")
                }
                if (!(val instanceof Collection) || (val as Collection).size() != 2) {
                    throw new FilterException("$op requires a collection of 2 values in the form [x, y]")
                }
                (val as Collection).each {
                    if (!isValueProperty(it)) {
                        String value = it.toString()
                        if (!value.isNumber()) {
                            def date = GraphUtils.dateTimeFormatter.parse(value, new ParsePosition(0))
                            if (!date) {
                                date = GraphUtils.dateFormatter.parse(value, new ParsePosition(0))
                                if (!date)
                                    throw new FilterException("$op requires a number or date (${GraphUtils.dateTimeFormat}, ${GraphUtils.dateFormat})")
                            }
                        }
                    }
                }
            }
            if (isValueProperty(val)) {
                validateFieldVal(field, property, persistentEntity, getValueProperty(val, persistentEntity), op, ignoreCase)
            } else if (val instanceof Collection) {
                Collection valueArray = val as Collection
                valueArray.each {
                    if (isValueProperty(it))
                        validateFieldVal(field, property, persistentEntity, getValueProperty(it, persistentEntity), op, ignoreCase)
                    else validateFieldVal(field, property, persistentEntity, it, op, ignoreCase)
                }
            } else {
                validateFieldVal(field, property, persistentEntity, val, op, ignoreCase)
            }

        }
    }

    static PersistentProperty getValueProperty(Object value, PersistentEntity persistentEntity) {
        Collection valueArray = value as Collection
        String propertyName = valueArray[0] as String
        def valueProperty = persistentEntity.getPropertyByName(propertyName)
        if (!valueProperty) {
            throw new PropertyNotFoundException(propertyName, persistentEntity.name)
        }
        valueProperty
    }

    static boolean isValueProperty(Object v) {
        v instanceof Collection && (v as Collection).size() == 1 && v[0] instanceof String
    }

    static validateFieldVal(String field, PersistentProperty property, PersistentEntity persistentEntity, Object val, String op, boolean ignoreCase) {
        if (ignoreCase && property.type != String) {
            throw new FilterException("ignore case requires $field type to be a string, instead of ${property.type.simpleName}")
        }
        if (substringOpMap.containsKey(op) && property.type != String) {
            throw new FilterException("$op requires $field value to be a string, instead of ${val?.class?.simpleName}")
        }
        if (val == null) {
            if(property.type == Boolean || property.type == boolean) {
                throw new FilterException("$field value must be 'true' or 'false', instead of ${null}")
            }
        } else if (val instanceof PersistentProperty) {
            PersistentProperty propertyValue = val as PersistentProperty
            if (!propertyValue.type.isAssignableFrom(property.type)) {
                throw new FilterException("$field property value must be a ${property.type.simpleName}, instead of ${propertyValue.type.simpleName}")
            }
        } else if (property.type == String && !(val instanceof String)) {
            throw new FilterException("$field value must be a string, instead of ${val?.class?.simpleName}")
        } else if (Number.isAssignableFrom(property.type) && !(Number.isAssignableFrom(val.class))) {
            throw new FilterException("$field value must be a number, instead of ${val?.class?.simpleName}")
        } else if ((property.type == Boolean || property.type == boolean) && !(val instanceof Boolean)) {
            throw new FilterException("$field value must be 'true' or 'false', instead of ${val?.class?.simpleName}")
        } else if (Date.isAssignableFrom(property.type) && !(val instanceof String)) {
            throw new FilterException("$field value must be a date string, instead of ${val?.class?.simpleName}")
        } else if(persistentEntity.associations?.find{ it.name == field}) {
            if (!(val instanceof Map)) {
                throw new FilterException("$field value must be an association object, instead of ${val?.class?.simpleName}")
            } else if (op) {
                throw new FilterException("$op is invalid on $field")
            }
        }
    }
}

class FilterException extends Exception {
    FilterException(String message) { super(message) }
}

class PropertyNotFoundException extends FilterException {
    PropertyNotFoundException(String field, String entityName) { super("$field is not a property of $entityName") }
}