package org.oreto.graphql.gorm

import grails.gorm.annotation.Entity
import org.grails.datastore.gorm.GormEntity

@Entity
class Test implements GormEntity<Test> {

    static constraints = {
        name nullable: true
    }

    static hasMany = [addresses: Address]
    static belongsTo = Address

    static mapping = {
        version false
        batchSize 100
    }

    Date dateCreated
    Date lastUpdated
    String name
}
