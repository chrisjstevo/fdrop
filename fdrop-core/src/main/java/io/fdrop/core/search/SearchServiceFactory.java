package io.fdrop.core.search;

import io.fdrop.core.Fdrop;

/** Found with {@link java.util.ServiceLoader}: the fdrop-search module provides the Lucene implementation. */
public interface SearchServiceFactory {

    SearchService create(Fdrop app);
}
