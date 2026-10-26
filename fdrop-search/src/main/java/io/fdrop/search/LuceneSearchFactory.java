package io.fdrop.search;

import io.fdrop.core.Fdrop;
import io.fdrop.core.search.SearchService;
import io.fdrop.core.search.SearchServiceFactory;

/** Registered with {@link java.util.ServiceLoader}: search is on whenever this module is on the classpath. */
public final class LuceneSearchFactory implements SearchServiceFactory {

    @Override
    public SearchService create(Fdrop app) {
        return new LuceneSearchService(app);
    }
}
