package io.fdrop.search;

import io.fdrop.core.Fdrop;
import io.fdrop.core.audit.AuditIndex;
import io.fdrop.core.audit.AuditIndexFactory;

/** Registered with {@link java.util.ServiceLoader}: audit search is on whenever this module is on the classpath. */
public final class LuceneAuditIndexFactory implements AuditIndexFactory {

    @Override
    public AuditIndex create(Fdrop app) {
        return new LuceneAuditIndex(app);
    }
}
