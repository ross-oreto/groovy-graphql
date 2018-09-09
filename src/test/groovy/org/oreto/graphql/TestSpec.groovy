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
    @Shared Long addressId

    def "query tests"() {
        setup:
        String query = new Query(collectionName).page(Page.Info()).select('id', 'name', 'image').build()
        L.info(query)

        when:
        LinkedHashMap result = q(query).data

        then:
        result[collectionName][RESULTS].size() == GraphUtils.DEFAULT_SIZE
    }

    def "filter tests"() {
        setup:
        String query = new Query(collectionName)
                .filter("{ addresses_not:null }")
                .page(Page.Info())
                .select('id', 'name', 'image')
                .select(new Query(AddressSpec.collectionName).select('id', 'line1'))
                .build()
        L.info(query)

        when:
        LinkedHashMap result = q(query).data
        addressId = result[collectionName][RESULTS][0][AddressSpec.collectionName][RESULTS][0].id

        then:
        result[collectionName][RESULTS].size() == GraphUtils.DEFAULT_SIZE
    }

    def "save tests"() {
        setup:
        String query =
                """mutation {
    saveTest(params:"{ name:'new test', addresses:[{line1:'new test address'}] }") {
        id
        name
        addresses {
            results {
                id
                line1
            }
        }
    }
}"""
        L.info(query)

        when:
        LinkedHashMap result = q(query).data
        id = result.saveTest.id

        then:
        result.saveTest.name == 'new test'
        result.saveTest.addresses.results[0].line1 == 'new test address'
    }

    def "update test"() {
        setup:
        String query =
                """mutation {
    saveTest(params:"{ id:$id, addresses:[{id:$addressId}] }") {
        id
        name
        addresses {
            results {
                id
                line1
            }
        }
    }
}"""
        L.info(query)

        when:
        LinkedHashMap result = q(query).data

        then:
        result.saveTest.id == id
        result.saveTest.addresses.results[0].id == addressId
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
        addresses {
            results {
                id
                line1
            }
        }
    }
}"""
        when:
        LinkedHashMap result = q(deleteQuery).data

        then:
        result.deleteTest.id == id
    }
}
