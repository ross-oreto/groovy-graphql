package org.oreto.graphql.data

import com.owlike.genson.Genson
import com.owlike.genson.GensonBuilder
import graphql.schema.GraphQLSchema
import org.grails.orm.hibernate.HibernateDatastore
import org.oreto.graphql.GraphUtils
import org.oreto.graphql.gorm.Address
import org.oreto.graphql.gorm.Person
import org.oreto.graphql.gorm.PersonService

class Schema {

    private static GraphQLSchema schema

    static int numberOfPeople

    static getSchema() {
        schema ? schema : buildSchema()
    }

    private static GraphQLSchema buildSchema() {
        def ds = new HibernateDatastore([
                "dataSource.pooled": true
                , "dataSource.jmxExport": true
                , "dataSource.logSql": true
                , "dataSource.formatSql": true
                , "dataSource.driverClassName": "org.h2.Driver"
                , "dataSource.url": "jdbc:h2:mem:test"
                //, "dataSource.url": "jdbc:h2:file:./data/local;MODE=Oracle;AUTO_SERVER=TRUE"
                , "dataSource.username": "sa"
                , "dataSource.password": "sa"
                , "dataSource.dbCreate": "create-drop"
        ], Person)
        populateTestData(ds)
        schema = GraphUtils.createGqlSchema(['people':Person.gormPersistentEntity, 'addresses': Address.gormPersistentEntity])
    }

    private static Random random = new Random()

    private static void populateTestData(HibernateDatastore ds) {
        PersonService personService = ds.getService(PersonService)
        Genson genson = new GensonBuilder().useStrictDoubleParse(false).create()
        def namesData = new FileInputStream('src/test/resources/names.json')
        def nounsData = new FileInputStream('src/test/resources/nouns.json')
        Collection names = genson.deserialize(namesData, String[].class)
        Collection nouns = genson.deserialize(nounsData, String[].class)

        def people = names.collect {
            def p = new Person(name: it)
            createAddresses(p, nouns)
            p
        }
        numberOfPeople = people.size()
        personService.savePeople(people)
    }

    private static Collection<Address> createAddresses(Person person, Collection<String> nouns) {
        (1..random.nextInt(3)).collect {
            def address = new Address(
                    line1: "${random.nextInt(200)} ${nouns[random.nextInt(999)]}"
                    , city: 'Nashville'
                    , state: 'TN'
                    , postalCode: '37216'
            )
            person.addToAddresses(address)
            address
        }
    }
}
