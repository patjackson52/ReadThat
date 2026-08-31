import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "./api";
import { useApp } from "./app-context";
import type { SearchDiscover } from "./types";
import { EmptyState, formatCount, formatRelative, Icon, Spinner } from "./ui";

export function CommunityDiscoveryPage() {
  const { online } = useApp();
  const [discover, setDiscover] = useState<SearchDiscover | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try { setDiscover(await api.discover()); }
    catch (caught) { setError(caught instanceof Error ? caught.message : "Communities could not be loaded"); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void load(); document.title = "Communities · Read That"; }, [load]);

  return <div className="communities-page">
    <header className="page-title-row">
      <div><p className="eyebrow">Community discovery</p><h1>Find your next corner of Read That</h1><p>Browse active communities and conversations gaining momentum.</p></div>
      <button className="icon-button" type="button" aria-label="Refresh communities" disabled={loading} onClick={() => void load()}><Icon name="refresh" /></button>
    </header>
    {!online && <div className="cache-notice"><Icon name="offline" /> Offline · showing the last available discovery results.</div>}
    {loading && !discover ? <div className="detail-loading"><Spinner /> Finding communities…</div>
      : error && !discover ? <EmptyState icon="community" title="Discovery unavailable">{error}</EmptyState>
        : discover && <>
          <section className="discovery-section">
            <div className="section-heading"><div><h2>Popular communities</h2><p>Open a community to see its latest posts.</p></div><Link to="/search?type=communities" viewTransition>Search all</Link></div>
            <div className="community-list">{discover.communities.map((community) => <Link className="community-row" key={community.id} to={`/r/${community.name}`} viewTransition>
              <span className="community-monogram">{community.displayName.slice(0, 1).toUpperCase()}</span>
              <span><strong>r/{community.name}</strong><small>{community.displayName} · {formatCount(community.subscriberCount)} members</small></span>
              <span className="row-arrow" aria-hidden="true">→</span>
            </Link>)}</div>
          </section>
          <section className="discovery-section">
            <div className="section-heading"><div><h2>Trending now</h2><p>Conversations people are joining today.</p></div></div>
            <div className="trending-grid">{discover.trending.map((trend, index) => <Link className="trending-card" key={trend.id} to={`/post/${trend.id}`} viewTransition>
              <span className="trend-rank">{String(index + 1).padStart(2, "0")}</span>
              <div><small>r/{trend.subreddit} · {formatRelative(trend.createdAt)}</small><strong>{trend.query}</strong><span>{formatCount(trend.score)} votes · {formatCount(trend.commentCount)} comments</span></div>
            </Link>)}</div>
          </section>
        </>}
  </div>;
}
