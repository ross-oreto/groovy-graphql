package org.oreto.graphql.gorm

import grails.gorm.annotation.Entity
import org.grails.datastore.gorm.GormEntity

@Entity
class Address implements GormEntity<Address> {

    static constraints = {
        line1 nullable: true
        line2 nullable: true
        city nullable: true
        state nullable: true
        country nullable: true
        postalCode nullable: true
    }

    static hasMany = [tests: Test]

    static mapping = {
        version false
        person lazy: false
        batchSize 100
    }

    Date dateCreated
    Date lastUpdated

    String line1
    String line2
    String city
    String state
    String country
    String postalCode

    Person person
}
