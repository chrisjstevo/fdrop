package io.fdrop.core.audit;

import io.fdrop.core.error.FdropException;

/** The audit index when the fdrop-search module is missing: browsing works, searching does not. */
public final class NoAuditIndex implements AuditIndex {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Result search(Query q) {
        throw FdropException.unavailable("audit search is not available on this server; filter by date instead")
                .withType("audit-search-unavailable");
    }

    @Override
    public Status status() {
        return new Status(false, 0, 0, 0, 0, 0, false);
    }

    @Override
    public void rebuild() {
        throw FdropException.unavailable("audit search is not available on this server")
                .withType("audit-search-unavailable");
    }

    @Override
    public void start() {
    }

    @Override
    public void close() {
    }
}
