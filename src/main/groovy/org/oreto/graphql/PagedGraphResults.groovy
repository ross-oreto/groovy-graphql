package org.oreto.graphql

class PagedGraphResults {
    Collection results
    PageInfo pageInfo

    PagedGraphResults(Collection results, int size, int skip, int totalCount) {
        this.results = results
        this.pageInfo = new PageInfo(size, skip, totalCount)
    }
}

class PageInfo {
    int size
    int skip
    int totalCount
    int page
    int totalPages

    PageInfo(int size, int skip, int totalCount) {
        this.size = size
        this.skip = skip
        this.totalCount = totalCount
        this.totalPages = (int) ((totalCount / size) + ((totalCount % size) > 0 ? 1 : 0))
        this.page = (int) (((skip + 1) / size) + (((skip + 1) % size) > 0 ? 1 : 0))
    }
}
