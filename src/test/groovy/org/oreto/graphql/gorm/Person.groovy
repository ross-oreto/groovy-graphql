package org.oreto.graphql.gorm

import grails.gorm.annotation.Entity
import org.grails.datastore.gorm.GormEntity

@Entity
class Person implements GormEntity<Person> {

    static constraints = {
        name nullable: false
    }

    static hasMany = [addresses: Address]
    static mappedBy = [addresses: 'person']

    static mapping = {
        version false
        batchSize 100
    }

    String name
}
