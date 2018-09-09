package org.oreto.graphql.gorm

import grails.gorm.annotation.Entity
import org.grails.datastore.gorm.GormEntity
import org.oreto.graphql.EntityTrait

@Entity
class Person implements GormEntity<Person>, EntityTrait {

    static constraints = {
        name nullable: false
        test nullable: true
        test2 nullable: true
        test3 nullable: true
        test4 nullable: true
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

    static mappedBy = [test:"none", test2:"none", test3:"none",test4:"none"]

    Test test
    Test test2
    Test test3
    Test test4
}
