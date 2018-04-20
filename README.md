# groovy-graphql
Graphql schema integration with groovy and GORM.

### Features
* Transforms GORM classes into a Graphql Schema
* graphql queries are transformed into GORM criteria queries which conduct efficient data fetching
* Query methods support a filter parameter which supports powerful sub queries
* supports paging
* QueryBuilder api included for creating graphql queries specific for the generated schema structure

### Usage
- The best examples are in the test directory /src/main/test/groovy (These are spock integration tests)

- create a graphql schema (Person and Address are GORM domain classes and the keys are the query names that the schema will use)
```groovy
GraphUtils.createGqlSchema(['people':Person.gormPersistentEntity, 'addresses': Address.gormPersistentEntity])
```
- executing queries (import gql.DSL)
```groovy
def query = """
{
	people(filter:"{ name: 'test', size: 20, skip: 0, orderBy: ['name_asc']}") {
		results {
			id
			name
		}
		pageInfo {
			totalCount
			size
			skip
			page
			totalPages
		}
	}
}
"""
DSL.execute(schema, query)
```

### Query Builder
* The QueryBuilder provides a fluent api to build valid graphql queries for the generated schema
* Usage:
```groovy
import static org.oreto.graphql.QueryBuilder.*
String query = new Query('addresses').size(20).skip(0).orderBy(['id']).select('id', 'line1')
    .select(new Result('person').select('id')).page(Page.Info()).build()
```

### filter api
- The filter parameter like the one above expects a valid json string and the query looks for all people with name equal to 'test'
- Aside from equals the default there are several other operators that can be specified such as name_contains : 'test'
  * String operators
    * text: exact value match
    * text_contains: text contains a value
    * text_starts_with: text begins with a value
    * text_ends_with: text ends with a value
    * text_in: text in a collection of values ["smith", "vador", "sparrow"]
    * text_i: equals ignore case (i can be combined with any operator such as text_i_contains: 'ith' which is text contains and ignore case)
    * text_not: text not equal to a value (not also can be combined with other operators)
    * text_starts_with_any: text starts with any of a specified list ["s", "t", "o"]
  * Number operators
    * number: exact value match
    * number_lt: number less than a value
    * number_lte: number less than or equal to a value
    * number_gt: number greater than a value
    * number_gte: number greater than or equal to a value
    * number_in: in a collection of values [19, 31, 66]
  * Boolean operators
    * flag: boolean is true or false flag: true or flag: false
    * flag_not boolean is not true or not false flag_not: true or flag_not: true
  * not, i, and any operators
    * _not: negates expression: can be applied to any expression
    * _any: matches and value in a collection
    * _i: ignore case, can be applied to any text operator
    * text_not_i_any: demonstrates a combination of all. text not equal to any value in a collection, ignores case
  * AND/OR logical operators, must be uppercase
    * AND: group expressions together using logical and. ex: AND: [{firstName: 'Ross'}, {lastName: 'Oreto'}]
    * OR: group expressions together using logical or. ex: OR: [{firstName: 'Ross'}, {lastName: 'Oreto'}]
    * Nesting is supported. AND: [{lastName: 'Ross'}, OR: [{firstName: 'Ross'}, {firstName: 'Michael'}] ]
  * Paging: two parameters support all collection queries. ex: size: 100, skip: 0
    * size: the max number to return
    * skip: the number to skip before matching expressions are returned
  * Ordering: order results according to one or multiple fields. ex: orderBy: ["lastName_asc", "firstName_asc"]
    * field_asc: ascending order
    * field_desc: descending order
