package io.fdrop.core.search;

import io.fdrop.core.auth.Principal;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.service.DestinationService;

import java.util.List;
import java.util.Map;

/** Search when it is turned off or the fdrop-search module is missing. */
public final class NoSearch implements SearchService {

    private static FdropException off() {
        return FdropException.unavailable("search is not available on this server").withType("search-unavailable");
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Result search(Principal p, Query q) {
        throw off();
    }

    @Override
    public List<Suggestion> suggest(Principal p, String q) {
        return List.of();
    }

    @Override
    public Status status(Principal admin) {
        DestinationService.requireAdmin(admin);
        return new Status(false, 0, 0, 0, 0, 0, Map.of(), false);
    }

    @Override
    public void rebuild(Principal admin) {
        throw off();
    }

    @Override
    public void start() {
    }

    @Override
    public void close() {
    }
}
