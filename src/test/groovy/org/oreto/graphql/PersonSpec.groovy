package org.oreto.graphql

import org.oreto.graphql.data.Schema
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared

import static org.oreto.graphql.QueryBuilder.*

class PersonSpec extends GqlSpec {

    private static final Logger L = LoggerFactory.getLogger(PersonSpec.class)

    static entityName = 'person'
    static collectionName = 'people'

    @Shared Long id

    def "query people"() {
        setup:
        String query = new Query(collectionName).page(Page.Info()).select('id', 'name')
                .select(
                    new Query(AddressSpec.collectionName).size(20).skip(0).orderBy(['id']).select('id', 'line1')
                            .select(new Result(entityName).select('id'))
        ).build()
        L.info(query)

        when:
        LinkedHashMap result = q(query).data
        id = (result[collectionName][RESULTS]['id'] as List)[0] as Long

        then:
        result[collectionName][RESULTS].size() == GraphUtils.DEFAULT_SIZE &&
                result[collectionName][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME] == Schema.numberOfPeople &&
                result[collectionName][RESULTS][AddressSpec.collectionName][RESULTS][entityName]['id'].size() == GraphUtils.DEFAULT_SIZE
    }

    def "filter people"() {
        setup:
        String query = new Query(collectionName).page(Page.Info()).filter("{ id: $id}").select('id', 'name').build()
        L.info(query)

        when:
        LinkedHashMap result = q(query).data
        List results = result[collectionName][RESULTS] as List

        then:
        results.size() == 1 &&
                result[collectionName][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME] == 1 &&
                results['id'][0] as Long == id
    }
}
