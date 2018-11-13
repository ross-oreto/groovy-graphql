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

    @Shared String id
    @Shared Integer addressId
    @Shared Integer testId
    @Shared String address1
    @Shared String address2

    def "query people"() {
        setup:
        String query = new Query(collectionName).size(50).page(Page.Info())
                .select('id', 'name', 'testDate', 'dateCreated')
                .select(
                    new Query(AddressSpec.collectionName).size(20).skip(0).orderBy(['id']).select('id', 'line1')
                            .select(new Result(entityName).select('id', 'name'))
                            .select(new Query('tests').select('name', 'image'))
                )
                .select(new Query('cats').size(20).select('value').page(Page.Info()))
                .select(new Result(TestSpec.entityName).select('name'))
                .build()
        L.info(query)

        when:
        def thing = q(query)
        LinkedHashMap result = thing.data
        id = (result[collectionName][RESULTS]['id'] as List)[0] as String
        address1 = (result[collectionName][RESULTS][AddressSpec.collectionName][RESULTS]['line1'] as List)[0][0]
        address2 = (result[collectionName][RESULTS][AddressSpec.collectionName][RESULTS]['line1'] as List)[1][0]

        then:
        result[collectionName][RESULTS].size() == Schema.numberOfPeople &&
                result[collectionName][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME] == Schema.numberOfPeople &&
                result[collectionName][RESULTS]['cats'][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME][0] == 3 &&
                result[collectionName][RESULTS][TestSpec.entityName]['name'][0] == 'one-to-one' &&
                result[collectionName][RESULTS][0][AddressSpec.collectionName][RESULTS][0][entityName]['name'] != null &&
                result[collectionName][RESULTS][AddressSpec.collectionName][RESULTS][entityName]['id'].size() == Schema.numberOfPeople &&
                result[collectionName][RESULTS][AddressSpec.collectionName][RESULTS]['tests'][RESULTS]['name'][0][0].size() == 3
    }

    def "query people test"() {
        setup:
        String query = new Query(collectionName).size(50).page(Page.Info()).select('id', 'name')
                .select(new Result(TestSpec.entityName).select('name')
                .select(
                    new Query(AddressSpec.collectionName).size(20).skip(0).orderBy(['id', 'line1']).select('id', 'line1')
                            .select(new Result(entityName).select('id', 'name'))
                            .select(new Query('tests').select('name', 'image'))
                 )).build()
        L.info(query)

        when:
        LinkedHashMap result = q(query).data
        List results = result[collectionName][RESULTS] as List

        then:
        results.size() == Schema.numberOfPeople &&
                result[collectionName][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME] == Schema.numberOfPeople
    }

    def "filter people"() {
        setup:
        String query = new Query(collectionName).page(Page.Info()).filter("{ id: '$id'}").select('id', 'name').build()
        L.info(query)

        when:
        LinkedHashMap result = q(query).data
        List results = result[collectionName][RESULTS] as List

        then:
        results.size() == 1 &&
                result[collectionName][PAGE_INFO][GraphUtils.INFO_TOTAL_COUNT_NAME] == 1 &&
                results['id'][0] as String == id
    }

    def "filter person addresses"() {
        setup:
        String query = new Query(collectionName).page(Page.Info()).filter("{ addresses_contains:{ line1_contains:['a', 'e'], dateCreated_between: [ ['7-8-2000', [dateCreated]] ] } }")
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
    savePerson(params:"{ name:'test person 1', testDate:'3-22-2018', test:{name: 'testing'}, 'addresses':[{ line1:'test address 1', 'tests':[ { name: 'blah' }] }] }") {
        id
        name
        testDate
        test {
            id
            name
        }
        addresses {
            results {
                id
                line1
                tests {
                    results {
                        id
                        name
                   }
                }
            }
        }
    }
}"""
        L.info(query)

        when:
        LinkedHashMap result = q(query).data
        id = result.savePerson.id
        addressId = result.savePerson.addresses.results[0].id
        testId = result.savePerson.addresses.results[0][TestSpec.collectionName][RESULTS][0].id

        then:
        result.savePerson.id != null
        result.savePerson.name == 'test person 1'
        result.savePerson.testDate.contains('3-22-2018')
        result.savePerson.test.name == 'testing'
        result.savePerson.addresses.results[0].line1 == 'test address 1'
        result.savePerson.addresses.results[0][TestSpec.collectionName][RESULTS][0].name == 'blah'
    }

    def "update person"() {
        setup:
        String query =
                """mutation {
    savePerson(params:"{ id:'$id', name:'updated person 1', test:null, test3:{id:$testId}, 'addresses':[{ id: $addressId, tests:null }] }") {
        id
        name
        test {
            id
            name
        }
        test3 {
            id
            name
        }
        addresses {
            results {
                id
                line1
                tests {
                    results {
                        id
                        name
                   }
                }
            }
        }
    }
}"""
        L.info(query)

        when:
        LinkedHashMap result = q(query).data

        then:
        result.savePerson.id != null
        result.savePerson.name == 'updated person 1'
        result.savePerson['test'] == null
        result.savePerson['test3'].name == 'blah'
        result.savePerson.addresses.results.size() == 1
        result.savePerson.addresses.results[0][TestSpec.collectionName][RESULTS].size() == 0
    }

    def "get person"() {
        setup:
        String getQuery =
                """query {
    getPerson(id:"$id") {
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
        LinkedHashMap result = q(getQuery).data

        then:
        result.getPerson.id == id
    }

//    def "criteria test"() {
//        setup:
//        def results = []
//
//        String g = """
//org.oreto.graphql.gorm.Address.where {
//person {
//    addresses {
//        like('line1','%a%')
//    }
//}}.distinct('id').list([max:20, offset:0])
//"""
//        //def list = Eval.me(g)
//
//        when:
//        org.oreto.graphql.gorm.Address.withTransaction {
//            org.oreto.graphql.gorm.Address.where {
//                inList 'id', Eval.me(g)
//                projections {
//                    property('id')
//                    property('line1')
//                    property('person.id')
//                }
//                order('id', 'desc')
//            }.list([max:20, offset:0])
//        }
//
//        then:
//        results.size() == Schema.numberOfPeople
//    }
}
