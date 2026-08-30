import { useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { ApiError, api } from "./api";
import { useApp } from "./app-context";
import { readCache, writeCache } from "./db";
import { mergeGroups } from "./logic";
import { PostCard } from "./post-card";
import type { FeedGroup, FeedPage, Subreddit } from "./types";
import { EmptyState, Icon, Spinner } from "./ui";

interface CachedFeed { groups: FeedGroup[]; nextCursor: string | null; serverTime: number }

export function FeedPage() {
  const { name } = useParams<{ name?: string }>();
  const subredditName = name?.toLowerCase();
  const { auth, online, notify, signInRequested } = useApp();
  const [groups, setGroups] = useState<FeedGroup[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [appending, setAppending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [cachedAt, setCachedAt] = useState<number | null>(null);
  const [community, setCommunity] = useState<Subreddit | null>(null);
  const sentinel = useRef<HTMLDivElement>(null);
  const cacheKey = `feed:${auth?.user.id ?? "anonymous"}:${subredditName ?? "home"}`;
  const cursorRef = useRef<string | null>(null);
  const busyRef = useRef(false);

  const load = useCallback(async (append: boolean) => {
    if (busyRef.current || (!online && groups.length > 0)) return;
    const nextCursor = append ? cursorRef.current : null;
    if (append && !nextCursor) return;
    busyRef.current = true;
    append ? setAppending(true) : setLoading(groups.length === 0);
    setError(null);
    try {
      const page: FeedPage = await api.feed(nextCursor, subredditName);
      const nextGroups = append ? mergeGroups(groups, page.groups) : page.groups;
      setGroups(nextGroups);
      setCursor(page.nextCursor);
      cursorRef.current = page.nextCursor;
      setCachedAt(null);
      await writeCache<CachedFeed>(cacheKey, { groups: nextGroups, nextCursor: page.nextCursor, serverTime: page.serverTime });
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "The feed could not be refreshed");
    } finally {
      busyRef.current = false;
      setLoading(false);
      setAppending(false);
    }
  }, [cacheKey, groups, online, subredditName]);

  useEffect(() => {
    let live = true;
    setGroups([]); setCursor(null); cursorRef.current = null; setCommunity(null); setLoading(true); setError(null);
    void readCache<CachedFeed>(cacheKey).then((cached) => {
      if (!live || !cached) return;
      setGroups(cached.value.groups);
      setCursor(cached.value.nextCursor);
      cursorRef.current = cached.value.nextCursor;
      setCachedAt(cached.updatedAt);
      setLoading(false);
    }).finally(() => { if (live && online) void load(false); });
    if (subredditName) {
      void api.subreddit(subredditName).then((value) => { if (live) setCommunity(value); }).catch((caught) => {
        if (live) setError(caught instanceof Error ? caught.message : "Community unavailable");
      });
      if (auth) void api.markCommunityVisited(subredditName);
    }
    document.title = subredditName ? `r/${subredditName} · ReadThat` : "ReadThat — your communities";
    return () => { live = false; };
    // `load` deliberately resets with the cache key; including it would restart after every page merge.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auth?.user.id, cacheKey, online, subredditName]);

  useEffect(() => {
    const element = sentinel.current;
    if (!element) return;
    const observer = new IntersectionObserver(([entry]) => {
      if (entry?.isIntersecting && cursorRef.current && !busyRef.current) void load(true);
    }, { rootMargin: "800px 0px" });
    observer.observe(element);
    return () => observer.disconnect();
  }, [load]);

  async function toggleJoin() {
    if (!auth) { signInRequested(); return; }
    if (!community) return;
    try {
      if (community.viewerRole) await api.leaveCommunity(community.name);
      else await api.joinCommunity(community.name);
      setCommunity(await api.subreddit(community.name));
      notify(community.viewerRole ? "Left community" : "Joined community", "success");
    } catch (caught) { notify(caught instanceof Error ? caught.message : "Membership update failed", "error"); }
  }

  return <div className="feed-page">
    {subredditName && <section className="community-hero">
      <div className="community-avatar">r/</div>
      <div><p className="eyebrow">Community</p><h1>{community?.displayName ?? `r/${subredditName}`}</h1><p>{community?.description || "Posts from this community"}</p><span>{community ? `${community.subscriberCount.toLocaleString()} members · ${community.accessType}` : "Loading community…"}</span></div>
      <button className={community?.viewerRole ? "secondary-button" : "primary-button"} type="button" onClick={() => void toggleJoin()}>{community?.viewerRole ? "Joined" : "Join"}</button>
    </section>}
    {!subredditName && <header className="feed-heading"><div><p className="eyebrow">Server-driven feed</p><h1>Your front page</h1><p>Fresh conversations, rendered from safe, versioned SDUI cells.</p></div><button className="icon-button" type="button" aria-label="Refresh feed" onClick={() => void load(false)}><Icon name="refresh" /></button></header>}
    {cachedAt && <div className="cache-notice"><Icon name={online ? "refresh" : "offline"} /> Showing your saved feed from {new Date(cachedAt).toLocaleString()}{online ? " while it refreshes." : "."}</div>}
    {error && groups.length > 0 && <div className="inline-error" role="status">{error} <button type="button" onClick={() => void load(false)}>Try again</button></div>}
    {loading && groups.length === 0 ? <div className="feed-skeleton" aria-label="Loading feed">{[0, 1, 2].map((index) => <div key={index}><i /><i /><i /></div>)}</div>
      : groups.length === 0 ? <EmptyState icon={online ? "community" : "offline"} title={online ? "No posts yet" : "Nothing saved offline"}>{online ? "Create the first post in this feed." : "Reconnect once to save this feed for offline reading."}</EmptyState>
        : <div className="feed-list">{groups.map((group) => <PostCard key={group.groupId} group={group} />)}</div>}
    <div ref={sentinel} className="feed-sentinel" aria-hidden={!appending}>{appending && <><Spinner /> Loading more</>}</div>
  </div>;
}
