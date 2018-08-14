package org.oreto.graphql.gorm

import grails.gorm.annotation.Entity
import org.grails.datastore.gorm.GormEntity

@Entity
class Person implements GormEntity<Person> {

    static constraints = {
        name nullable: false
        test nullable: true
    }

    static hasMany = [addresses: Address, cats: String]

    static mapping = {
        version false
        batchSize 100
    }

    Date dateCreated
    Date lastUpdated
    String name

    Test test
}
