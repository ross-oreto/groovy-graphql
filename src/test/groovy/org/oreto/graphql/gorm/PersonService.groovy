package org.oreto.graphql.gorm

import grails.gorm.services.Service

@Service(Person)
abstract class PersonService {
    abstract Person savePerson(Person person)
    abstract Integer count()

    Collection<Person> savePeople(Collection<Person> people) {
        people.collect {
            savePerson(it)
        }
    }
}
