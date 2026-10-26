package io.fdrop.core.search;

import io.fdrop.core.auth.Principal;
import io.fdrop.core.db.FolderRepo;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Records.Folder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where a stored file belongs, for the search index, and which of those places a user may search now. A file's area
 * is {@code home:<sub>} under {@code users/<sub>/} or {@code folder:<id>} under {@code folders/<id>/}; anything else
 * (the {@code system/} prefix) is never indexed.
 */
public final class SearchAccess {

    /** A stored key split into its area and its path inside that area ({@code ""} for the area itself). */
    public record Place(String area, String relPath) {
        public boolean home() {
            return area.startsWith("home:");
        }

        public String id() {
            return area.substring(area.indexOf(':') + 1);
        }
    }

    private final FolderRepo folders;
    private final boolean includeForward;

    public SearchAccess(FolderRepo folders, boolean includeForward) {
        this.folders = folders;
        this.includeForward = includeForward;
    }

    public static Optional<Place> place(String storageKey) {
        String[] parts = storageKey.split("/", 3);
        if (parts.length < 2) {
            return Optional.empty();
        }
        String rel = parts.length == 3 ? parts[2] : "";
        return switch (parts[0]) {
            case "users" -> Optional.of(new Place("home:" + parts[1], rel));
            case "folders" -> Optional.of(new Place("folder:" + parts[1], rel));
            default -> Optional.empty();
        };
    }

    /** The areas the user may search right now: their home and the folders they own or belong to. */
    public List<String> areas(Principal p) {
        List<String> out = new ArrayList<>();
        out.add("home:" + p.sub());
        for (Folder f : folders.visibleTo(p.sub())) {
            if (f.type() == FolderType.SHARED || includeForward) {
                out.add("folder:" + f.id());
            }
        }
        return out;
    }

    /** Whether an area's files are indexed at all (forward folders only when configured). */
    public boolean indexed(Place place) {
        if (place.home()) {
            return true;
        }
        return folders.find(place.id()).map(f -> f.type() == FolderType.SHARED || includeForward).orElse(false);
    }

    /** The user-visible path of a stored file, from its folder's current name; empty when the folder is gone. */
    public Optional<String> virtualPath(Place place) {
        String base;
        if (place.home()) {
            base = "/home";
        } else {
            Optional<Folder> f = folders.find(place.id());
            if (f.isEmpty()) {
                return Optional.empty();
            }
            base = (f.get().type() == FolderType.SHARED ? "/shared/" : "/forward/") + f.get().name();
        }
        return Optional.of(place.relPath().isEmpty() ? base : base + "/" + place.relPath());
    }

    /** "Home / reports" or "Shared: Invoices / 2026": where a result lives, without its own name. */
    public Optional<String> location(Place place) {
        String base;
        if (place.home()) {
            base = "Home";
        } else {
            Optional<Folder> f = folders.find(place.id());
            if (f.isEmpty()) {
                return Optional.empty();
            }
            base = (f.get().type() == FolderType.SHARED ? "Shared: " : "Forward: ") + f.get().name();
        }
        int slash = place.relPath().lastIndexOf('/');
        return Optional.of(slash < 0 ? base : base + " / " + place.relPath().substring(0, slash).replace("/", " / "));
    }
}
