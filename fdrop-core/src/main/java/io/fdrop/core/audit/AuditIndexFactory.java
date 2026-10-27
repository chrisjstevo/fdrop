package io.fdrop.core.audit;

import io.fdrop.core.Fdrop;

/** Found with {@link java.util.ServiceLoader}: the fdrop-search module provides the Lucene implementation. */
public interface AuditIndexFactory {

    AuditIndex create(Fdrop app);
}
