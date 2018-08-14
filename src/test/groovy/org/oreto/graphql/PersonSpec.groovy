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
        String query = new Query(collectionName).size(50).page(Page.Info()).select('id', 'name')
                .select(
                    new Query(AddressSpec.collectionName).size(20).skip(0).orderBy(['id']).select('id', 'line1')
                            .select(new Result(entityName).select('id'))
                            .select(new Query('tests').select('name', 'image'))
                )
                .select(new Query('cats').size(20).select('value').page(Page.Info()))
                .select(new Result(TestSpec.entityName).select('name'))
                .build()
        L.info(query)

        when:
        def thing = q(query)
        LinkedHashMap result = thing.data
        id = (result[collectionName][RESULTS]['id'] as List)[0] as Long
        address1 = (result[collectionName][RESULTS][AddressSpec.collectionName][RESULTS]['line1'] as List)[0][0]
        address2 = (result[collectionName][RESULTS][AddressSpec.collectionName][RESULTS]['line1'] as List)[1][0]

        then:
        result[collectionName][RESULTS].size() == Schema.numberOfPeople &&
                result[collectionName][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME] == Schema.numberOfPeople &&
                result[collectionName][RESULTS]['cats'][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME][0] == 3 &&
                result[collectionName][RESULTS][TestSpec.entityName]['name'][0] == 'one-to-one' &&
                result[collectionName][RESULTS][AddressSpec.collectionName][RESULTS][entityName]['id'].size() == Schema.numberOfPeople &&
                result[collectionName][RESULTS][AddressSpec.collectionName][RESULTS]['tests'][RESULTS]['name'][0][0].size() == 3
    }

    def "filter people"() {
        setup:
        String query = new Query(collectionName).page(Page.Info()).filter("{ id: $id, id_between: [[id], 50000]}").select('id', 'name').build()
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
        String query = new Query(collectionName).page(Page.Info()).filter("{ addresses_contains:{ dateCreated_between: [ ['7-8-2000', [dateCreated]] ] } }")
                .select('id', 'name').build()
        L.info(query)

        when:
        LinkedHashMap result = q(query).data
        List results = result[collectionName][RESULTS] as List

        then:
        results.size() > 0 &&
                result[collectionName][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME] > 0
    }

    def "save people"() {
        setup:
        String query =
                """mutation {
    savePerson(params:"{ name:'test person 1', 'addresses[0]':{ line1:'test address 1'} }") {
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
        id = result.savePerson.id
        query =
                """mutation {
    savePerson(params:"{ id:$id, name:'updated person 1', 'addresses[1]':{ line1:'test address 2'} }") {
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
        result = q(query).data

        then:
        result.savePerson.id != null
        result.savePerson.name == 'updated person 1'
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
