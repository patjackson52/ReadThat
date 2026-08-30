import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { api } from "./api";
import type { SearchItem } from "./types";
import { EmptyState, formatCount, formatRelative, Icon, Spinner } from "./ui";

export function SearchPage() {
  const [parameters] = useSearchParams();
  const query = parameters.get("q")?.trim() ?? "";
  const [items, setItems] = useState<SearchItem[]>([]);
  const [discover, setDiscover] = useState<Awaited<ReturnType<typeof api.discover>> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setLoading(true); setError(null); setItems([]); setDiscover(null);
    const request = query ? api.search(query).then((result) => { if (live) setItems(result); })
      : api.discover().then((result) => { if (live) setDiscover(result); });
    void request.catch((caught) => { if (live) setError(caught instanceof Error ? caught.message : "Search failed"); })
      .finally(() => { if (live) setLoading(false); });
    document.title = query ? `Search: ${query} · ReadThat` : "Discover · ReadThat";
    return () => { live = false; };
  }, [query]);

  if (loading) return <div className="detail-loading"><Spinner /> {query ? "Searching…" : "Finding conversations…"}</div>;
  if (error) return <EmptyState icon="search" title="Search unavailable">{error}</EmptyState>;

  if (!query && discover) return <div className="search-page"><header><p className="eyebrow">Explore</p><h1>Discover</h1><p>Popular conversations and growing communities.</p></header>
    <section><h2>Trending now</h2><div className="search-results">{discover.trending.map((item) => <Link className="search-result" key={item.id} to={`/post/${item.id}`} viewTransition><Icon name="comment" /><div><strong>{item.query}</strong><span>r/{item.subreddit}</span></div></Link>)}</div></section>
    <section><h2>Communities</h2><div className="community-grid">{discover.communities.map((item) => <Link key={item.id} to={`/r/${item.name}`} viewTransition><div className="community-avatar small">r/</div><strong>{item.displayName}</strong><span>r/{item.name} · {formatCount(item.subscriberCount)} members</span></Link>)}</div></section>
  </div>;

  return <div className="search-page"><header><p className="eyebrow">Search</p><h1>Results for “{query}”</h1><p>{items.length} result{items.length === 1 ? "" : "s"} across posts, comments, communities, and people.</p></header>
    {items.length === 0 ? <EmptyState icon="search" title="No matches">Try a broader phrase or another community name.</EmptyState>
      : <div className="search-results">{items.map((item) => <SearchResult key={`${item.type}:${item.id}`} item={item} />)}</div>}
  </div>;
}

function SearchResult({ item }: { item: SearchItem }) {
  if (item.type === "post") return <Link className="search-result" to={`/post/${item.id}`} viewTransition><Icon name={item.kind === "video" ? "video" : "comment"} /><div><small>r/{item.subreddit} · u/{item.author}</small><strong>{item.title}</strong>{item.body && <p>{item.body}</p>}<span>{formatCount(item.score)} points · {formatCount(item.commentCount)} comments · {formatRelative(item.createdAt)}</span></div></Link>;
  if (item.type === "comment") return <Link className="search-result" to={`/post/${item.postId}#comment-${item.id}`} viewTransition><Icon name="comment" /><div><small>{item.author} in r/{item.post.subreddit}</small><strong>{item.post.title}</strong><p>{item.body}</p><span>{formatCount(item.score)} points · {formatRelative(item.createdAt)}</span></div></Link>;
  if (item.type === "community") return <Link className="search-result" to={`/r/${item.name}`} viewTransition><Icon name="community" /><div><small>r/{item.name}</small><strong>{item.displayName}</strong><p>{item.description}</p><span>{formatCount(item.subscriberCount)} members · {item.accessType}</span></div></Link>;
  return <Link className="search-result" to={`/u/${item.username}`} viewTransition><Icon name="user" /><div><small>u/{item.username}</small><strong>{item.displayName}</strong><p>{item.bio}</p><span>{formatCount(item.karma)} karma</span></div></Link>;
}
