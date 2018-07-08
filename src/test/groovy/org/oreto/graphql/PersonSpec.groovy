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
    @Shared String address1
    @Shared String address2

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
        address1 = (result[collectionName][RESULTS][AddressSpec.collectionName][RESULTS]['line1'] as List)[0][0]
        address2 = (result[collectionName][RESULTS][AddressSpec.collectionName][RESULTS]['line1'] as List)[1][0]

        then:
        result[collectionName][RESULTS].size() == GraphUtils.DEFAULT_SIZE &&
                result[collectionName][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME] == Schema.numberOfPeople &&
                result[collectionName][RESULTS][AddressSpec.collectionName][RESULTS][entityName]['id'].size() == GraphUtils.DEFAULT_SIZE
    }

    def "filter people"() {
        setup:
        String query = new Query(collectionName).page(Page.Info()).filter("{ id: $id, name: [name]}").select('id', 'name').build()
        L.info(query)

        when:
        LinkedHashMap result = q(query).data
        List results = result[collectionName][RESULTS] as List

        then:
        results.size() == 1 &&
                result[collectionName][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME] == 1 &&
                results['id'][0] as Long == id
    }

    def "filter person addresses"() {
        setup:
        String query = new Query(collectionName).page(Page.Info()).filter("{ addresses_contains:{ line1_contains:['$address1'], line1_not_contains:['$address1'] } }")
                .select('id', 'name').build()
        L.info(query)

        when:
        LinkedHashMap result = q(query).data
        List results = result[collectionName][RESULTS] as List

        then:
        results.size() > 0 &&
                result[collectionName][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME] > 0
    }

//    def "criteria test"() {
//        setup:
//        List<Person> results = []
//
//        when:
//        Person.withNewSession {
//            results = Person.where {
//                name == name
//            }.list()
//        }
//
//        then:
//        results.size() == Schema.numberOfPeople
//    }
}
