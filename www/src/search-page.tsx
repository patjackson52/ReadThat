import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { api } from "./api";
import type { SearchDiscover, SearchItem, SearchPageResponse, SearchSort, SearchTime, SearchType, SearchTypeahead } from "./types";
import { EmptyState, formatCount, formatRelative, Icon, Spinner } from "./ui";

const TYPES: Array<{ value: SearchType; label: string }> = [
  { value: "all", label: "All" }, { value: "posts", label: "Posts" },
  { value: "communities", label: "Communities" }, { value: "comments", label: "Comments" },
  { value: "media", label: "Media" }, { value: "profiles", label: "People" },
];
const RECENT_KEY = "read-that-recent-searches";

function recentSearches(): string[] {
  try { return JSON.parse(localStorage.getItem(RECENT_KEY) ?? "[]") as string[]; } catch { return []; }
}

function rememberSearch(query: string): string[] {
  const next = [query, ...recentSearches().filter((item) => item.toLocaleLowerCase() !== query.toLocaleLowerCase())].slice(0, 8);
  localStorage.setItem(RECENT_KEY, JSON.stringify(next));
  return next;
}

export function SearchPage() {
  const [parameters] = useSearchParams();
  const navigate = useNavigate();
  const query = parameters.get("q")?.trim() ?? "";
  const type = (parameters.get("type") as SearchType | null) ?? "all";
  const sort = (parameters.get("sort") as SearchSort | null) ?? "relevance";
  const time = (parameters.get("time") as SearchTime | null) ?? "all";
  const safe = parameters.get("safe") !== "false";
  const [draft, setDraft] = useState(query);
  const [response, setResponse] = useState<SearchPageResponse | null>(null);
  const [discover, setDiscover] = useState<SearchDiscover | null>(null);
  const [suggestions, setSuggestions] = useState<SearchTypeahead | null>(null);
  const [recent, setRecent] = useState<string[]>(recentSearches);
  const [loading, setLoading] = useState(true);
  const [appending, setAppending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => { setDraft(query); }, [query]);
  useEffect(() => {
    let live = true;
    setLoading(true); setError(null); setResponse(null); setDiscover(null); setSuggestions(null);
    const request = query
      ? api.searchPage({ query, type, sort, time, safe }).then((result) => { if (live) setResponse(result); })
      : api.discover().then((result) => { if (live) setDiscover(result); });
    void request.catch((caught) => { if (live) setError(caught instanceof Error ? caught.message : "Search failed"); })
      .finally(() => { if (live) setLoading(false); });
    document.title = query ? `Search: ${query} · Read That` : "Search · Read That";
    return () => { live = false; };
  }, [query, safe, sort, time, type]);

  useEffect(() => {
    if (!draft.trim() || draft.trim() === query) { setSuggestions(null); return; }
    let live = true;
    const timer = window.setTimeout(() => void api.typeahead(draft.trim()).then((value) => { if (live) setSuggestions(value); }).catch(() => { if (live) setSuggestions(null); }), 220);
    return () => { live = false; clearTimeout(timer); };
  }, [draft, query]);

  function navigateWith(changes: Record<string, string | null>) {
    const next = new URLSearchParams(parameters);
    Object.entries(changes).forEach(([key, value]) => value === null ? next.delete(key) : next.set(key, value));
    navigate(`/search${next.size ? `?${next.toString()}` : ""}`, { viewTransition: true });
  }

  function submit(event?: FormEvent) {
    event?.preventDefault();
    const value = draft.trim();
    if (!value) { navigate("/search", { viewTransition: true }); return; }
    setRecent(rememberSearch(value));
    setSuggestions(null);
    navigateWith({ q: value });
  }

  async function loadMore() {
    if (!query || !response?.nextCursor || type === "all") return;
    setAppending(true);
    try {
      const next = await api.searchPage({ query, type, sort, time, safe, cursor: response.nextCursor });
      setResponse((current) => current ? { ...next, items: [...(current.items ?? []), ...(next.items ?? [])] } : next);
    } catch (caught) { setError(caught instanceof Error ? caught.message : "More results could not be loaded"); }
    finally { setAppending(false); }
  }

  const items = response?.items ?? [];
  const count = useMemo(() => response?.sections ? Object.values(response.sections).reduce((total, section) => total + section.length, 0) : items.length, [items.length, response]);

  return <div className="search-page full-search-page">
    <form className="search-workbench" role="search" onSubmit={submit}>
      <button type="button" className="icon-button search-back" aria-label="Go back" onClick={() => navigate(-1)}>←</button>
      <div><Icon name="search" /><input ref={input} value={draft} onChange={(event) => setDraft(event.target.value)} aria-label="Search Read That" placeholder="Search Read That" autoComplete="off" />{draft && <button type="button" aria-label="Clear search" onClick={() => { setDraft(""); setSuggestions(null); input.current?.focus(); }}><Icon name="x" /></button>}</div>
      <button className="primary-button compact" type="submit">Search</button>
    </form>
    {suggestions && <div className="search-suggestions">
      <button type="button" onClick={() => submit()}><Icon name="search" /><span>Search for <strong>{draft.trim()}</strong></span></button>
      {suggestions.completions.filter((item) => item.toLocaleLowerCase() !== draft.trim().toLocaleLowerCase()).map((completion) => <button type="button" key={completion} onClick={() => { setDraft(completion); navigateWith({ q: completion }); }}><span className="suggestion-arrow">↗</span><span>{completion}</span></button>)}
      {suggestions.communities.map((item) => item.type === "community" && <Link key={item.id} to={`/r/${item.name}`} viewTransition><Icon name="community" /><span><strong>r/{item.name}</strong><small>{item.displayName}</small></span></Link>)}
      {suggestions.profiles.map((item) => item.type === "profile" && <Link key={item.id} to={`/u/${item.username}`} viewTransition><Icon name="user" /><span><strong>u/{item.username}</strong><small>{item.displayName}</small></span></Link>)}
    </div>}
    {!suggestions && !query && <SearchDiscoverView discover={discover} recent={recent} loading={loading} error={error} onSearch={(value) => { setDraft(value); setRecent(rememberSearch(value)); navigateWith({ q: value }); }} onDeleteRecent={(value) => { const next = recent.filter((item) => item !== value); setRecent(next); localStorage.setItem(RECENT_KEY, JSON.stringify(next)); }} onClearRecent={() => { setRecent([]); localStorage.removeItem(RECENT_KEY); }} />}
    {!suggestions && query && <>
      <nav className="search-tabs" aria-label="Search result type">{TYPES.map((option) => <button type="button" key={option.value} aria-current={type === option.value ? "page" : undefined} onClick={() => navigateWith({ type: option.value, cursor: null })}>{option.label}</button>)}</nav>
      <div className="search-filters">
        <label>Sort <select value={sort} onChange={(event) => navigateWith({ sort: event.target.value, cursor: null })}><option value="relevance">Relevance</option><option value="hot">Hot</option><option value="top">Top</option><option value="new">New</option><option value="comments">Comment count</option></select></label>
        <label>Time <select value={time} onChange={(event) => navigateWith({ time: event.target.value, cursor: null })}><option value="all">All time</option><option value="year">Past year</option><option value="month">Past month</option><option value="week">Past week</option><option value="day">Past day</option><option value="hour">Past hour</option></select></label>
        <label className="safe-toggle"><input type="checkbox" checked={safe} onChange={() => navigateWith({ safe: String(!safe), cursor: null })} /> Safe search</label>
      </div>
      <header className="search-summary"><p className="eyebrow">Search</p><h1>Results for “{query}”</h1>{!loading && <p>{count} result{count === 1 ? "" : "s"}{type === "all" ? " across Read That" : ` in ${TYPES.find((option) => option.value === type)?.label.toLocaleLowerCase()}`}.</p>}</header>
      {loading ? <div className="detail-loading"><Spinner /> Searching…</div>
        : error && !response ? <EmptyState icon="search" title="Search unavailable">{error}</EmptyState>
          : type === "all" && response?.sections ? <AllResults response={response} onTab={(next) => navigateWith({ type: next })} />
            : items.length === 0 ? <EmptyState icon="search" title="No matches">Try a broader phrase or another result type.</EmptyState>
              : <div className={type === "media" ? "search-media-grid" : "search-results"}>{items.map((item) => <SearchResult key={`${item.type}:${item.id}`} item={item} mediaMode={type === "media"} />)}</div>}
      {response?.nextCursor && <button className="secondary-button search-more" disabled={appending} type="button" onClick={() => void loadMore()}>{appending && <Spinner />} Load more results</button>}
      {error && response && <p className="inline-error">{error}</p>}
    </>}
  </div>;
}

function SearchDiscoverView({ discover, recent, loading, error, onSearch, onDeleteRecent, onClearRecent }: { discover: SearchDiscover | null; recent: string[]; loading: boolean; error: string | null; onSearch: (value: string) => void; onDeleteRecent: (value: string) => void; onClearRecent: () => void }) {
  if (loading) return <div className="detail-loading"><Spinner /> Finding conversations…</div>;
  if (error) return <EmptyState icon="search" title="Search unavailable">{error}</EmptyState>;
  return <div className="search-discover">
    {recent.length > 0 && <section><div className="section-heading"><h2>Recent</h2><button type="button" onClick={onClearRecent}>Clear</button></div>{recent.map((item) => <div className="recent-search" key={item}><button type="button" onClick={() => onSearch(item)}><span>◷</span>{item}</button><button type="button" aria-label={`Remove ${item}`} onClick={() => onDeleteRecent(item)}><Icon name="x" /></button></div>)}</section>}
    {discover && <><section><div className="section-heading"><h2>Trending</h2></div>{discover.trending.map((trend) => <button className="discover-trend" type="button" key={trend.id} onClick={() => onSearch(trend.query)}><span>↗</span><span><strong>{trend.query}</strong><small>r/{trend.subreddit} · {formatCount(trend.score)} votes</small></span></button>)}</section>
    <section><div className="section-heading"><h2>Trending communities</h2><Link to="/communities" viewTransition>Browse all</Link></div><div className="community-grid">{discover.communities.map((item) => <Link key={item.id} to={`/r/${item.name}`} viewTransition><div className="community-avatar small">{item.displayName.slice(0, 1)}</div><strong>{item.displayName}</strong><span>r/{item.name} · {formatCount(item.subscriberCount)} members</span></Link>)}</div></section></>}
  </div>;
}

function AllResults({ response, onTab }: { response: SearchPageResponse; onTab: (type: SearchType) => void }) {
  const sections = response.sections;
  if (!sections) return null;
  const ordered: Array<{ key: keyof typeof sections; label: string; type: SearchType }> = [
    { key: "communities", label: "Communities", type: "communities" }, { key: "posts", label: "Posts", type: "posts" },
    { key: "media", label: "Media", type: "media" }, { key: "comments", label: "Comments", type: "comments" }, { key: "profiles", label: "People", type: "profiles" },
  ];
  const nonempty = ordered.filter((section) => sections[section.key].length > 0);
  if (nonempty.length === 0) return <EmptyState icon="search" title="No matches">Try a broader phrase or another community name.</EmptyState>;
  return <div className="all-search-results">{nonempty.map((section) => <section key={section.key}><div className="section-heading"><h2>{section.label}</h2><button type="button" onClick={() => onTab(section.type)}>See all</button></div><div className={section.key === "media" ? "search-media-grid" : "search-results"}>{sections[section.key].map((item) => <SearchResult key={`${item.type}:${item.id}:${section.key}`} item={item} mediaMode={section.key === "media"} />)}</div></section>)}</div>;
}

function SearchResult({ item, mediaMode = false }: { item: SearchItem; mediaMode?: boolean }) {
  if (item.type === "post") {
    if (mediaMode && item.media?.thumbnailUrl) return <Link className="search-media-result" to={`/media?anchorPostId=${encodeURIComponent(item.id)}&subreddit=${encodeURIComponent(item.subreddit)}`} viewTransition><img src={item.media.thumbnailUrl} alt="" loading="lazy" /><span><strong>{item.title}</strong><small>r/{item.subreddit} · {formatCount(item.score)} votes</small></span>{item.kind === "video" && <Icon name="play" />}</Link>;
    return <Link className="search-result" to={`/post/${item.id}`} viewTransition>{item.media?.thumbnailUrl ? <img className="search-thumbnail" src={item.media.thumbnailUrl} alt="" loading="lazy" /> : <Icon name={item.kind === "video" ? "video" : "comment"} />}<div><small>r/{item.subreddit} · u/{item.author}</small><strong>{item.title}</strong>{item.body && <p>{item.body}</p>}<span>{formatCount(item.score)} points · {formatCount(item.commentCount)} comments · {formatRelative(item.createdAt)}</span></div></Link>;
  }
  if (item.type === "comment") return <Link className="search-result" to={`/post/${item.postId}/comment/${item.id}`} viewTransition><Icon name="comment" /><div><small>{item.author} in r/{item.post.subreddit}</small><strong>{item.post.title}</strong><p>{item.body}</p><span>{formatCount(item.score)} points · {formatRelative(item.createdAt)}</span></div></Link>;
  if (item.type === "community") return <Link className="search-result" to={`/r/${item.name}`} viewTransition><Icon name="community" /><div><small>r/{item.name}</small><strong>{item.displayName}</strong><p>{item.description}</p><span>{formatCount(item.subscriberCount)} members · {item.accessType}</span></div></Link>;
  return <Link className="search-result" to={`/u/${item.username}`} viewTransition>{item.avatarUrl ? <img className="search-avatar" src={item.avatarUrl} alt="" /> : <Icon name="user" />}<div><small>u/{item.username}</small><strong>{item.displayName}</strong><p>{item.bio}</p><span>{formatCount(item.karma)} karma</span></div></Link>;
}
