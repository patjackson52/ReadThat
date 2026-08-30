import { useEffect, useMemo, useState, type CSSProperties, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError, api } from "./api";
import { useApp } from "./app-context";
import { readCache, writeCache } from "./db";
import { loadedTree, postGroup, replaceCursor, updateNode } from "./logic";
import { PostCard } from "./post-card";
import type { CommentNode, CommentTree, LoadMoreNode, Post, TreeNode, VoteValue } from "./types";
import { EmptyState, formatCount, formatRelative, Icon, Spinner } from "./ui";

interface DetailCache { post: Post; comments: CommentTree }

function appendChild(nodes: TreeNode[], parentId: string | null, child: CommentNode): TreeNode[] {
  if (!parentId) return [child, ...nodes];
  return updateNode(nodes, parentId, (node) => ({ ...node, children: [child, ...node.children] }));
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

function CommentRow({ node, depth, postId, onChange, onLoadMore }: { node: TreeNode; depth: number; postId: string; onChange: (id: string, update: (node: CommentNode) => CommentNode) => void; onLoadMore: (node: LoadMoreNode) => Promise<void> }) {
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
  return <article className={`comment${node.pending ? " pending" : ""}`} style={style} id={`comment-${node.id}`}>
    <header><button className="collapse-comment" type="button" aria-label={collapsed ? "Expand comment" : "Collapse comment"} onClick={() => setCollapsed((value) => !value)}>{collapsed ? "+" : "−"}</button><strong>{node.author}</strong><span>·</span><time dateTime={new Date(node.createdAt).toISOString()}>{formatRelative(node.createdAt)}</time>{node.pending && <em>Pending sync</em>}</header>
    {!collapsed && <div className="comment-content">
      <p>{node.body}</p>
      <div className="comment-actions">
        <button type="button" aria-label="Upvote comment" aria-pressed={node.viewerVote === 1} onClick={() => void vote(1)}><Icon name="arrow-up" /></button>
        <strong>{formatCount(node.score)}</strong>
        <button type="button" aria-label="Downvote comment" aria-pressed={node.viewerVote === -1} onClick={() => void vote(-1)}><Icon name="arrow-down" /></button>
        <button type="button" onClick={() => auth ? setReplying((value) => !value) : signInRequested()}><Icon name="comment" /> Reply</button>
      </div>
      {replying && <CommentComposer compact postId={postId} parentId={node.id} onCreated={(comment) => {
        onChange(node.id, (current) => ({ ...current, children: [comment, ...current.children] }));
        setReplying(false);
      }} />}
      <div className="comment-children">{node.children.map((child) => <CommentRow key={child.id} node={child} depth={depth + 1} postId={postId} onChange={onChange} onLoadMore={onLoadMore} />)}</div>
    </div>}
  </article>;
}

export function PostPage() {
  const { postId = "" } = useParams<{ postId: string }>();
  const { auth, online } = useApp();
  const [post, setPost] = useState<Post | null>(null);
  const [tree, setTree] = useState<CommentTree | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [fromCache, setFromCache] = useState(false);
  const cacheKey = `detail:${auth?.user.id ?? "anonymous"}:${postId}`;

  useEffect(() => {
    let live = true;
    setLoading(true); setError(null); setPost(null); setTree(null); setFromCache(false);
    void readCache<DetailCache>(cacheKey).then((cached) => {
      if (!live || !cached) return;
      setPost(cached.value.post); setTree(cached.value.comments); setFromCache(true); setLoading(false);
    }).finally(async () => {
      if (!live || !online) return;
      try {
        const [nextPost, nextTree] = await Promise.all([api.post(postId), api.comments(postId)]);
        if (!live) return;
        setPost(nextPost); setTree(nextTree); setFromCache(false); setLoading(false);
        await writeCache<DetailCache>(cacheKey, { post: nextPost, comments: nextTree });
      } catch (caught) {
        if (live) { setError(caught instanceof ApiError ? caught.message : "Post could not be loaded"); setLoading(false); }
      }
    });
    return () => { live = false; };
  }, [cacheKey, online, postId]);

  useEffect(() => { if (post) document.title = `${post.title} · ReadThat`; }, [post]);

  const group = useMemo(() => post ? postGroup(post) : null, [post]);
  function changeComment(id: string, update: (node: CommentNode) => CommentNode) {
    setTree((current) => current ? { ...current, roots: updateNode(current.roots, id, update) } : current);
  }
  async function loadMore(node: LoadMoreNode) {
    const loaded = await api.loadMoreComments(postId, node.childIds);
    setTree((current) => current ? { ...current, roots: replaceCursor(current.roots, node.id, loadedTree(loaded.comments, loaded.cursors)) } : current);
  }

  if (loading && !post) return <div className="detail-loading"><Spinner /> Loading conversation…</div>;
  if (!post || !group) return <EmptyState icon={online ? "comment" : "offline"} title="Conversation unavailable">{error ?? "This post is not in your offline cache."}</EmptyState>;

  return <div className="post-page">
    {fromCache && <div className="cache-notice"><Icon name="offline" /> Showing the saved conversation{online ? " while it refreshes." : "."}</div>}
    {error && <div className="inline-error">{error}</div>}
    <PostCard group={group} detail />
    <section className="comments-section" id="comments">
      <header><div><p className="eyebrow">Conversation</p><h2>{post.commentCount.toLocaleString()} comments</h2></div><span>Best</span></header>
      <CommentComposer postId={post.id} parentId={null} onCreated={(comment) => setTree((current) => current ? { ...current, roots: appendChild(current.roots, null, comment) } : current)} />
      {!tree || tree.roots.length === 0 ? <EmptyState icon="comment" title="Start the conversation">Share what you think.</EmptyState>
        : <div className="comment-tree">{tree.roots.map((node) => <CommentRow key={node.id} node={node} depth={0} postId={post.id} onChange={changeComment} onLoadMore={loadMore} />)}</div>}
      {tree?.corpusTruncated && <p className="field-note">This very large thread is showing a bounded view. Open individual branches to continue.</p>}
    </section>
    <Link className="back-to-feed" to={post.subreddit ? `/r/${post.subreddit}` : "/"} viewTransition>← Back to r/{post.subreddit}</Link>
  </div>;
}
