package org.oreto.graphql

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared

import static org.oreto.graphql.QueryBuilder.Page
import static org.oreto.graphql.QueryBuilder.Query

class TestSpec extends GqlSpec {

    private static final Logger L = LoggerFactory.getLogger(TestSpec.class)

    static collectionName = 'tests'

    @Shared Long id

    def "query tests"() {
        setup:
        String query = new Query(collectionName).page(Page.Info()).select('id', 'name', 'picture').build()
        L.info(query)

        when:
        LinkedHashMap result = q(query).data

        then:
        result[collectionName][RESULTS].size() == GraphUtils.DEFAULT_SIZE
    }
}
