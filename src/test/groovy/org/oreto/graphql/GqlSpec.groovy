package org.oreto.graphql

import gql.DSL
import graphql.ExecutionResult
import graphql.schema.GraphQLSchema
import org.oreto.graphql.data.Schema
import org.oreto.graphql.gorm.Person
import spock.lang.Specification

class GqlSpec extends Specification {

    static RESULTS = GraphUtils.PAGED_RESULTS_NAME
    static PAGE_INFO = GraphUtils.PAGE_INFO_NAME

    static GraphQLSchema schema

    def setupSpec() {
        schema = Schema.getSchema()
    }

    def "count people"() {
        expect:
        Person.withNewSession { Person.count() == Schema.numberOfPeople }
    }

    static ExecutionResult q(String query) {
        DSL.execute(schema, query)
    }
}
