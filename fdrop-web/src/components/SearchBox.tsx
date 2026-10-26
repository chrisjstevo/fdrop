import { useQuery } from "@tanstack/react-query";
import { useEffect, useId, useState, type KeyboardEvent } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router";
import { get, q as enc } from "../api/client";
import type { SearchSuggestion } from "../api/types";
import { parentOf } from "../util/format";
import { FileIcon, FolderIcon, SearchIcon } from "./icons";
import l from "./Layout.module.css";
import s from "./SearchBox.module.css";

function filesLink(path: string) {
  return "/files" + path.split("/").map((p) => (p ? encodeURIComponent(p) : p)).join("/");
}

/** Waits until typing pauses. */
function useDebounced(value: string, ms: number) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

/**
 * The top bar's search: a combobox. Typing narrows the folder being shown and suggests matching names from
 * everywhere; Enter searches names and contents everywhere the user can see (see "Search" in SPEC.md).
 */
export function SearchBox() {
  const location = useLocation();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const inFiles = location.pathname.startsWith("/files/");
  const onSearch = location.pathname === "/search";
  const initial = inFiles || onSearch ? (params.get("q") ?? "") : "";
  const [value, setValue] = useState(initial);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const listId = useId();
  useEffect(() => setValue(initial), [initial, location.pathname]);

  const typed = useDebounced(value.trim(), 150);
  const suggestions = useQuery({
    queryKey: ["suggest", typed],
    enabled: open && typed.length >= 2,
    queryFn: () => get<SearchSuggestion[]>("/api/v1/search/suggest?q=" + enc(typed)),
    staleTime: 10_000,
  });
  const items = typed.length >= 2 ? (suggestions.data ?? []) : [];
  const total = items.length + (value.trim() ? 1 : 0);

  const folderShown = inFiles ? decodeURIComponent(location.pathname.replace(/^\/files/, "")) : undefined;

  const searchAll = () => {
    const query = value.trim();
    if (!query) {
      return;
    }
    setOpen(false);
    const from = folderShown ?? (onSearch ? params.get("from") : null);
    navigate(`/search?q=${enc(query)}${from ? `&from=${enc(from)}` : ""}`);
  };

  const openSuggestion = (sg: SearchSuggestion) => {
    setOpen(false);
    navigate(sg.folder ? filesLink(sg.path) : `${filesLink(parentOf(sg.path))}?highlight=${enc(sg.name)}`);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown" && total > 0) {
      e.preventDefault();
      setOpen(true);
      setActive((a) => (a + 1) % total);
    } else if (e.key === "ArrowUp" && total > 0) {
      e.preventDefault();
      setActive((a) => (a <= 0 ? total - 1 : a - 1));
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (open && active >= 0 && active < items.length) {
        openSuggestion(items[active]);
      } else {
        searchAll();
      }
    } else if (e.key === "Escape") {
      setOpen(false);
      setActive(-1);
    }
  };

  const optionId = (i: number) => `${listId}-opt-${i}`;
  const showList = open && value.trim().length > 0;
  return (
    <div className={s.wrap}>
      <form
        role="search"
        className={l.search}
        style={{ maxWidth: "none" }}
        onSubmit={(e) => {
          e.preventDefault();
          searchAll();
        }}
      >
        <label htmlFor="file-search" className="visually-hidden">
          Search files and contents
        </label>
        <span className={l.searchIcon}>
          <SearchIcon size={16} />
        </span>
        <input
          id="file-search"
          type="search"
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={showList}
          aria-controls={listId}
          aria-activedescendant={showList && active >= 0 ? optionId(active) : undefined}
          placeholder="Search files and contents"
          autoComplete="off"
          value={value}
          onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 150)}
          onKeyDown={onKeyDown}
          onChange={(e) => {
            setValue(e.target.value);
            setOpen(true);
            setActive(-1);
            if (inFiles) {
              const v = e.target.value.trim();
              navigate(v ? `${location.pathname}?q=${enc(v)}` : location.pathname, { replace: true });
            }
          }}
        />
      </form>
      {showList && (
        <ul id={listId} role="listbox" aria-label="Search suggestions" className={s.list}>
          {items.map((sg, i) => (
            <li
              key={sg.path}
              id={optionId(i)}
              role="option"
              aria-selected={active === i}
              className={s.option}
              onMouseDown={(e) => {
                e.preventDefault();
                openSuggestion(sg);
              }}
            >
              <span className={s.optionIcon} aria-hidden="true">
                {sg.folder ? <FolderIcon size={16} /> : <FileIcon size={16} />}
              </span>
              <span className={s.optionText}>
                <span className={s.optionName}>{sg.name}</span>
                <span className={s.optionWhere}>{sg.location}</span>
              </span>
            </li>
          ))}
          <li
            id={optionId(items.length)}
            role="option"
            aria-selected={active === items.length}
            className={`${s.option} ${s.contents}`}
            onMouseDown={(e) => {
              e.preventDefault();
              searchAll();
            }}
          >
            <span className={s.optionIcon} aria-hidden="true">
              <SearchIcon size={16} />
            </span>
            Search contents for “{value.trim()}”
          </li>
        </ul>
      )}
    </div>
  );
}
