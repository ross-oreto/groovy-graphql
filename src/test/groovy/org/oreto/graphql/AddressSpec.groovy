package org.oreto.graphql

import org.oreto.graphql.data.Schema
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared

import static org.oreto.graphql.QueryBuilder.*

class AddressSpec extends GqlSpec {

    private static final Logger L = LoggerFactory.getLogger(AddressSpec.class)

    static collectionName = 'addresses'

    @Shared Long id

    def "query addresses"() {
        setup:
        String query = new Query(collectionName).page(Page.Info()).select('id', 'line1')
                .select(
                new Result(PersonSpec.entityName).select('id')
        ).build()
        L.info(query)

        when:
        LinkedHashMap result = q(query).data
        id = (result[collectionName][RESULTS]['id'] as List)[0] as Long

        then:
        result[collectionName][RESULTS].size() == GraphUtils.DEFAULT_SIZE &&
                result[collectionName][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME] >= Schema.numberOfPeople &&
                result[collectionName][RESULTS][PersonSpec.entityName]['id'].size() > 0
    }

    def "filter addresses"() {
        setup:
        String query = new Query(collectionName).page(Page.Info()).filter("{ id: $id, person: { addresses: { line1_not:null }} }")
                .select('id', 'line1').select(new Result(PersonSpec.getEntityName()).select('id')).build()
        L.info(query)

        when:
        LinkedHashMap result = q(query).data
        List results = result[collectionName][RESULTS] as List

        then:
        results.size() == 1 &&
                result[collectionName][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME] == 1 &&
                result[collectionName][RESULTS][PersonSpec.getEntityName()]['id'][0] != '' &&
                results['id'][0] as Long == id
    }
}
