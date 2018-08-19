package org.oreto.graphql

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared

import static org.oreto.graphql.QueryBuilder.Page
import static org.oreto.graphql.QueryBuilder.Query

class TestSpec extends GqlSpec {

    private static final Logger L = LoggerFactory.getLogger(TestSpec.class)

    static collectionName = 'tests'
    static entityName = 'test'

    @Shared Long id

    def "query tests"() {
        setup:
        String query = new Query(collectionName).page(Page.Info()).select('id', 'name', 'image').build()
        L.info(query)

        when:
        LinkedHashMap result = q(query).data

        then:
        result[collectionName][RESULTS].size() == GraphUtils.DEFAULT_SIZE
    }

    def "save tests"() {
        setup:
        String query =
                """mutation {
    saveTest(params:"{ name:'new test' }") {
        id
        name
    }
}"""
        L.info(query)

        when:
        LinkedHashMap result = q(query).data
        id = result.saveTest.id
        query =
                """mutation {
    saveTest(params:"{ id:$id, name:'updated test' }") {
        id
        name
    }
}"""
        result = q(query).data

        then:
        result.saveTest.name == 'updated test'
    }

    def "get test"() {
        setup:
        String getQuery =
                """query {
    getTest(id:$id) {
        id
        name
    }
}"""
        when:
        LinkedHashMap getResult = q(getQuery).data

        then:
        getResult.getTest.id == id
    }

    def "delete test"() {
        setup:
        String deleteQuery =
                """mutation {
    deleteTest(id:$id) {
        id
        name
    }
}"""
        when:
        LinkedHashMap result = q(deleteQuery).data

        then:
        result.deleteTest.id == id
    }
}
