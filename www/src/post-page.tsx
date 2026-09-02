import { useEffect, useMemo, useRef, useState, type CSSProperties, type FormEvent } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { ApiError, api } from "./api";
import { useApp } from "./app-context";
import { readCache, writeCache } from "./db";
import { commentPermalink, focusedCommentId } from "./deep-links";
import { collapsedCommentCountLabel, loadedTree, postGroup, replaceCursor, updateNode } from "./logic";
import { PostCard } from "./post-card";
import { setSocialMetadata } from "./social";
import type { CommentNode, CommentSort, CommentTree, LoadMoreNode, Post, TreeNode, VoteValue } from "./types";
import { EmptyState, formatCount, formatRelative, Icon, Spinner } from "./ui";

interface DetailCache { post: Post; comments: CommentTree }

function appendChild(nodes: TreeNode[], parentId: string | null, child: CommentNode): TreeNode[] {
  if (!parentId) return [child, ...nodes];
  return updateNode(nodes, parentId, (node) => ({
    ...node,
    children: [child, ...node.children],
    descendantCount: node.descendantCount + 1 + child.descendantCount,
  }));
}

function CommentComposer({ postId, parentId, onCreated, compact = false }: { postId: string; parentId: string | null; onCreated: (comment: CommentNode) => void; compact?: boolean }) {
  const { auth, notify, signInRequested } = useApp();
  const [busy, setBusy] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!auth) { signInRequested(); return; }
    const form = event.currentTarget;
    const body = String(new FormData(form).get("body") ?? "").trim();
    if (!body) return;
    setBusy(true);
    try {
      const result = await api.createComment(postId, body, parentId);
      onCreated({
        type: "comment",
        id: result?.comment.id ?? `pending-${crypto.randomUUID()}`,
        author: `u/${auth.user.username}`,
        body,
        score: 1,
        viewerVote: 1,
        createdAt: Date.now(),
        createdAgoMin: 0,
        descendantCount: 0,
        children: [],
        pending: !result,
      });
      form.reset();
      notify(result ? "Comment posted" : "Comment queued for sync", "success");
    } catch (caught) { notify(caught instanceof Error ? caught.message : "Could not post comment", "error"); }
    finally { setBusy(false); }
  }
  return <form className={`comment-composer${compact ? " compact" : ""}`} onSubmit={(event) => void submit(event)}>
    <textarea name="body" aria-label={parentId ? "Write a reply" : "Write a comment"} placeholder={auth ? (parentId ? "Write a reply…" : "Join the conversation…") : "Sign in to join the conversation"} rows={compact ? 2 : 3} maxLength={10_000} onFocus={() => { if (!auth) signInRequested(); }} />
    <button className="primary-button" disabled={busy}>{busy ? <Spinner /> : <Icon name="send" />} {parentId ? "Reply" : "Comment"}</button>
  </form>;
}

function CommentRow({ node, depth, postId, focusedId, onChange, onLoadMore }: { node: TreeNode; depth: number; postId: string; focusedId?: string; onChange: (id: string, update: (node: CommentNode) => CommentNode) => void; onLoadMore: (node: LoadMoreNode) => Promise<void> }) {
  const { auth, notify, signInRequested } = useApp();
  const [collapsed, setCollapsed] = useState(false);
  const [replying, setReplying] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  if (node.type === "load_more") return <button className="load-more-comments" type="button" disabled={loadingMore} onClick={() => {
    setLoadingMore(true);
    void onLoadMore(node)
      .catch((caught) => notify(caught instanceof Error ? caught.message : "Replies could not be loaded"))
      .finally(() => setLoadingMore(false));
  }}>{loadingMore ? <Spinner /> : <Icon name="comment" />} Load {node.remainingCount} more repl{node.remainingCount === 1 ? "y" : "ies"}</button>;
  const commentNode: CommentNode = node;

  async function vote(value: VoteValue) {
    if (!auth) { signInRequested(); return; }
    const oldVote = commentNode.viewerVote;
    const nextVote = oldVote === value ? 0 : value;
    onChange(node.id, (comment) => ({ ...comment, viewerVote: nextVote, score: comment.score - oldVote + nextVote }));
    try {
      const result = await api.vote("comment", node.id, nextVote);
      if (result) onChange(node.id, (comment) => ({ ...comment, viewerVote: result.vote.value, score: result.vote.score }));
      else notify("Vote queued", "success");
    } catch (caught) {
      onChange(commentNode.id, (comment) => ({ ...comment, viewerVote: oldVote, score: commentNode.score }));
      notify(caught instanceof Error ? caught.message : "Vote failed", "error");
    }
  }

  const style = { "--comment-depth": Math.min(depth, 6) } as CSSProperties;
  const collapsedCountLabel = collapsed ? collapsedCommentCountLabel(node.descendantCount) : null;
  return <article className={`comment${node.pending ? " pending" : ""}${node.id === focusedId ? " focused-comment" : ""}`} style={style} id={`comment-${node.id}`} tabIndex={node.id === focusedId ? -1 : undefined}>
    <header><button className={`collapse-comment${collapsedCountLabel ? " with-count" : ""}`} type="button" aria-label={collapsedCountLabel ?? (collapsed ? "Expand comment" : "Collapse comment")} onClick={() => setCollapsed((value) => !value)}>{collapsedCountLabel ? `+ ${collapsedCountLabel}` : collapsed ? "+" : "−"}</button><strong>{node.author}</strong><span>·</span><time dateTime={new Date(node.createdAt).toISOString()}>{formatRelative(node.createdAt)}</time>{node.pending && <em>Pending sync</em>}</header>
    {!collapsed && <div className="comment-content">
      <p>{node.body}</p>
      <div className="comment-actions">
        <button type="button" aria-label="Upvote comment" aria-pressed={node.viewerVote === 1} onClick={() => void vote(1)}><Icon name="arrow-up" /></button>
        <strong>{formatCount(node.score)}</strong>
        <button type="button" aria-label="Downvote comment" aria-pressed={node.viewerVote === -1} onClick={() => void vote(-1)}><Icon name="arrow-down" /></button>
        <button type="button" onClick={() => auth ? setReplying((value) => !value) : signInRequested()}><Icon name="comment" /> Reply</button>
        <Link to={commentPermalink(postId, node.id)} viewTransition>Permalink</Link>
      </div>
      {replying && <CommentComposer compact postId={postId} parentId={node.id} onCreated={(comment) => {
        onChange(node.id, (current) => ({ ...current, children: [comment, ...current.children] }));
        setReplying(false);
      }} />}
      <div className="comment-children">{node.children.map((child) => <CommentRow key={child.id} node={child} depth={depth + 1} postId={postId} focusedId={focusedId} onChange={onChange} onLoadMore={onLoadMore} />)}</div>
    </div>}
  </article>;
}

export function PostPage() {
  const { postId = "", commentId } = useParams<{ postId: string; commentId?: string }>();
  const routeLocation = useLocation();
  const focusedId = focusedCommentId(commentId, routeLocation.search, routeLocation.hash);
  const { auth, online } = useApp();
  const [post, setPost] = useState<Post | null>(null);
  const [tree, setTree] = useState<CommentTree | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [fromCache, setFromCache] = useState(false);
  const [sort, setSort] = useState<CommentSort>("best");
  const conversationIdentity = `${postId}:${focusedId ?? "root"}`;
  const previousConversation = useRef<string | null>(null);
  const postRef = useRef<Post | null>(null);
  const appliedSort = useRef<CommentSort>("best");
  const cacheKey = `detail:v2:${auth?.user.id ?? "anonymous"}:${conversationIdentity}:${sort}`;

  useEffect(() => { postRef.current = post; }, [post]);

  useEffect(() => {
    let live = true;
    const conversationChanged = previousConversation.current !== conversationIdentity;
    previousConversation.current = conversationIdentity;
    setLoading(true); setError(null); setFromCache(false);
    if (conversationChanged) { setPost(null); setTree(null); }
    void readCache<DetailCache>(cacheKey).then((cached) => {
      if (!live || !cached) return;
      appliedSort.current = cached.value.comments.sort;
      setPost(cached.value.post); setTree(cached.value.comments); setFromCache(true); setLoading(false);
    }).finally(async () => {
      if (!live || !online) return;
      try {
        const currentPost = postRef.current?.id === postId ? postRef.current : null;
        const [nextPost, nextTree] = await Promise.all([
          currentPost ? Promise.resolve(currentPost) : api.post(postId),
          api.comments(postId, focusedId, sort),
        ]);
        if (!live) return;
        appliedSort.current = nextTree.sort;
        setPost(nextPost); setTree(nextTree); setFromCache(false); setLoading(false);
        await writeCache<DetailCache>(cacheKey, { post: nextPost, comments: nextTree });
      } catch (caught) {
        if (live) {
          setSort(appliedSort.current);
          setError(caught instanceof ApiError ? caught.message : "Post could not be loaded");
          setLoading(false);
        }
      }
    });
    return () => { live = false; };
  }, [cacheKey, conversationIdentity, focusedId, online, postId, sort]);

  useEffect(() => {
    if (!tree || !focusedId) return;
    const frame = requestAnimationFrame(() => {
      const comment = document.getElementById(`comment-${focusedId}`);
      comment?.scrollIntoView({ block: "center" });
      comment?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [focusedId, tree]);

  useEffect(() => {
    if (!post) return;
    const title = `${post.title} · Read That`;
    const description = (post.body?.trim() || `${formatCount(post.score)} points · ${formatCount(post.commentCount)} comments in r/${post.subreddit}`).slice(0, 200);
    const primary = post.mediaItems?.[0] ?? post.media;
    const image = primary ? primary.zoomUrl ?? primary.url ?? primary.posterUrl : null;
    document.title = title;
    setSocialMetadata({ title, description, image });
  }, [post]);

  const group = useMemo(() => post ? postGroup(post) : null, [post]);
  function changeComment(id: string, update: (node: CommentNode) => CommentNode) {
    setTree((current) => current ? { ...current, roots: updateNode(current.roots, id, update) } : current);
  }
  async function loadMore(node: LoadMoreNode) {
    const loaded = await api.loadMoreComments(postId, node.childIds, node.sort);
    setTree((current) => current ? { ...current, roots: replaceCursor(current.roots, node.id, loadedTree(loaded.comments, loaded.cursors)) } : current);
  }

  if (loading && !post) return <div className="detail-loading"><Spinner /> Loading conversation…</div>;
  if (!post || !group) return <EmptyState icon={online ? "comment" : "offline"} title="Conversation unavailable">{error ?? "This post is not in your offline cache."}</EmptyState>;

  return <div className="post-page">
    {fromCache && <div className="cache-notice"><Icon name="offline" /> Showing the saved conversation{online ? " while it refreshes." : "."}</div>}
    {error && <div className="inline-error">{error}</div>}
    <PostCard group={group} detail />
    <section className="comments-section" id="comments">
      <header><div><p className="eyebrow">Conversation</p><h2>{post.commentCount.toLocaleString()} comments</h2></div><div className="comment-sort-control">{loading && <Spinner />}<select aria-label="Sort comments" value={sort} onChange={(event) => setSort(event.target.value as CommentSort)}><option value="best">Best</option><option value="top">Top</option><option value="qa">Q&amp;A</option><option value="controversial">Controversial</option><option value="new">New</option><option value="old">Old</option></select></div></header>
      <CommentComposer postId={post.id} parentId={null} onCreated={(comment) => setTree((current) => current ? { ...current, roots: appendChild(current.roots, null, comment) } : current)} />
      {!tree || tree.roots.length === 0 ? <EmptyState icon="comment" title="Start the conversation">Share what you think.</EmptyState>
        : <div className="comment-tree">{tree.roots.map((node) => <CommentRow key={node.id} node={node} depth={0} postId={post.id} focusedId={focusedId} onChange={changeComment} onLoadMore={loadMore} />)}</div>}
      {tree?.corpusTruncated && <p className="field-note">This very large thread is showing a bounded view. Open individual branches to continue.</p>}
    </section>
    <Link className="back-to-feed" to={post.subreddit ? `/r/${post.subreddit}` : "/"} viewTransition>← Back to r/{post.subreddit}</Link>
  </div>;
}
