package org.oreto.graphql


class QueryBuilder {

    static String ln = '\n'
    public Query query

    QueryBuilder(String name) {
        query = new Query(name)
    }

    String build() {
        query.build()
    }

    private static String tabs(int i) {
        (0..i).collect { '\t' }.join('')
    }

    static class F {
        F(String name) { this.name = name }
        String name
    }

    static class Query extends F {
        Query(String name) { super(name) }

        String build() {
            "{$ln${_build(this, 0)}}"
        }

        protected Params params

        protected Params _getParams() { params ? params : (params = new Params()) }

        protected class Params {
            String filter
            Integer size
            Integer skip
            Collection<String> orderBy
            String format
        }

        Query filter(String filter) {
            _getParams().filter = filter
            this
        }

        Query size(Integer size) {
            _getParams().size = size
            this
        }

        Query skip(Integer skip) {
            _getParams().skip = skip
            this
        }

        Query orderBy(Collection<String> orderBy) {
            _getParams().orderBy = orderBy
            this
        }

        Query format(String format) {
            _getParams().format = format
            this
        }

        protected PagedResults pagedResults
        protected PagedResults getPagedResults() { pagedResults ? pagedResults : (pagedResults = new PagedResults()) }

        private Result results() {
            getPagedResults().results ? getPagedResults().results : (getPagedResults().results = new Result(GraphUtils.PAGED_RESULTS_NAME))
        }

        Query select(String... select) {
            results().select(select)
            this
        }

        Query select(F... select) {
            results().select(select)
            this
        }

        Query page(Page page) {
            getPagedResults().page = page
            this
        }

        protected static String _build(Query query, int i) {
            StringBuilder sb = new StringBuilder()
            if (query.params) {
                def params = query.params
                def args = []
                sb.append(tabs(i)).append(query.name).append('(')
                if (params.filter) args.add("${GraphUtils.FILTER_ARG_NAME}:\"${params.filter}\"")
                if (params.size != null) args.add("${GraphUtils.SIZE_ARG_NAME}:${params.size}")
                if (params.skip != null) args.add("${GraphUtils.SKIP_ARG_NAME}:${params.skip}")
                if (params.orderBy) {
                    if (params.orderBy.size() > 1)
                        args.add("${GraphUtils.ORDERBY_ARG_NAME}:[${params.orderBy.collect{ "\"$it\"" }.join(', ')}]")
                    else args.add("${GraphUtils.ORDERBY_ARG_NAME}:\"${params.orderBy[0]}\"")
                }
                if (params.format) args.add("${GraphUtils.FORMAT_ARG_NAME}:\"${params.format}\"")
                sb.append(args.join(', ')).append(')').append(" {$ln")
            } else {
                sb.append(tabs(i)).append(query.name).append(" {$ln")
            }
            if (query.results()) {
                sb.append(_build(query.results(), i + 1))
            }
            if (query.pagedResults?.page) {
                _pageInfo(query.pagedResults.page, sb, i + 1)
            }
            sb.append(tabs(i)).append("}$ln")
            sb.toString()
        }

        protected static String _build(Result result, int i) {
            StringBuilder sb = new StringBuilder()
            sb.append(tabs(i)).append("${result.name} {$ln")
            result.selections.each {
                if (it instanceof Query) {
                    sb.append(_build(it as Query, i + 1))
                } else if (it instanceof Result) {
                    sb.append(_build(it as Result, i + 1))
                } else {
                    sb.append(tabs(i + 1)).append("${it.name}$ln")
                }
            }
            sb.append(tabs(i)).append("}$ln")
            sb.toString()
        }

        private static _pageInfo(Page pageInfo, StringBuilder sb, int i) {
            sb.append(tabs(i)).append("${pageInfo.name} {$ln")
            if (pageInfo._totalCount) sb.append(tabs(i + 1)).append("${GraphUtils.INFO_TOTAL_COUNT_NAME}$ln")
            if (pageInfo._size) sb.append(tabs(i + 1)).append("${GraphUtils.INFO_SIZE_NAME}$ln")
            if (pageInfo._skip) sb.append(tabs(i + 1)).append("${GraphUtils.INFO_SKIP_NAME}$ln")
            if (pageInfo._page) sb.append(tabs(i + 1)).append("${GraphUtils.INFO_PAGE_NAME}$ln")
            if (pageInfo._totalPages) sb.append(tabs(i + 1)).append("${GraphUtils.INFO_TOTAL_PAGES_NAME}$ln")
            sb.append(tabs(i)).append("}$ln")
        }
    }

    static class Result extends F {
        Result(String name) { super(name) }
        Collection<F> selections = []

        Result select(String... select) {
            selections.addAll(select.collect{ new F(it)} )
            this
        }
        Result select(F... select) {
            selections.addAll(select)
            this
        }

        String build() {
            Query._build(this, 0)
        }
    }

    static class PagedResults {
        Result results
        Page page
    }

    static class Page extends F {
        Page(String name) { super(name) }
        protected boolean _totalCount
        protected boolean _size
        protected boolean _skip
        protected boolean _page
        protected boolean _totalPages

        def totalCount() { _totalCount = true; this }
        def size() { _size = true; this }
        def skip() { _skip = true; this }
        def page() { _page = true; this }
        def totalPages() { _totalPages = true; this }

        def pageInfo() {
            totalCount().size().skip().page().totalPages()
        }

        static Info() { new Page(GraphUtils.PAGE_INFO_NAME).pageInfo() }
    }
}
