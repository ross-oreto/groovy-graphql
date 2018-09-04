package org.oreto.graphql.gorm

import grails.gorm.annotation.Entity
import org.grails.datastore.gorm.GormEntity
import org.oreto.graphql.EntityTrait

@Entity
class Person implements GormEntity<Person>, EntityTrait {

    static constraints = {
        name nullable: false
        test nullable: true
    }

    static hasMany = [addresses: Address, cats: String]

    static mapping = {
        id generator:'uuid'
        version false
        addresses cascade: 'all'
        batchSize 100
    }

    Date dateCreated
    Date lastUpdated
    String id
    String name

    Test test
}
