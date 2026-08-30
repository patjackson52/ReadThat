import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, api } from "./api";
import { useApp } from "./app-context";
import type { DrawerCommunity, PostKind, Subreddit } from "./types";
import { Icon, Spinner } from "./ui";

function errorMessage(error: unknown): string {
  return error instanceof ApiError || error instanceof Error ? error.message : "Something went wrong";
}

function Modal({ title, open, onClose, children, wide = false }: { title: string; open: boolean; onClose: () => void; children: React.ReactNode; wide?: boolean }) {
  const titleId = useId();
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!open) return;
    closeRef.current?.focus();
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape") onClose(); };
    addEventListener("keydown", escape);
    return () => removeEventListener("keydown", escape);
  }, [onClose, open]);
  if (!open) return null;
  return <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <section className={`modal-card${wide ? " modal-wide" : ""}`} role="dialog" aria-modal="true" aria-labelledby={titleId}>
      <header><h2 id={titleId}>{title}</h2><button ref={closeRef} type="button" className="icon-button" aria-label="Close" onClick={onClose}><Icon name="x" /></button></header>
      {children}
    </section>
  </div>;
}

export function AuthDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { notify } = useApp();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    const values = new FormData(event.currentTarget);
    try {
      const username = String(values.get("username") ?? "");
      const password = String(values.get("password") ?? "");
      if (mode === "login") await api.login({ username, password });
      else await api.register({ username, password, displayName: String(values.get("displayName") ?? "") || undefined });
      notify(mode === "login" ? "Welcome back" : "Your account is ready", "success");
      onClose();
      void api.flushOutbox();
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(false);
    }
  }

  return <Modal title={mode === "login" ? "Sign in to ReadThat" : "Create your account"} open={open} onClose={onClose}>
    <div className="segmented" role="tablist" aria-label="Authentication mode">
      <button type="button" role="tab" aria-selected={mode === "login"} onClick={() => { setMode("login"); setError(null); }}>Sign in</button>
      <button type="button" role="tab" aria-selected={mode === "register"} onClick={() => { setMode("register"); setError(null); }}>Create account</button>
    </div>
    <form className="stack-form" onSubmit={(event) => void submit(event)}>
      {mode === "register" && <label>Display name <input name="displayName" autoComplete="name" maxLength={50} placeholder="How people will see you" /></label>}
      <label>Username <input name="username" autoComplete="username" required minLength={3} maxLength={24} pattern="[A-Za-z0-9_]+" placeholder="letters, numbers, underscores" /></label>
      <label>Password <input name="password" type="password" autoComplete={mode === "login" ? "current-password" : "new-password"} required minLength={10} maxLength={128} /></label>
      {mode === "register" && <p className="field-note">Use at least 10 characters. Your password is salted and hashed at the edge.</p>}
      {error && <p className="form-error" role="alert">{error}</p>}
      <button className="primary-button" disabled={busy}>{busy && <Spinner />} {mode === "login" ? "Sign in" : "Create account"}</button>
    </form>
  </Modal>;
}

async function mediaMetadata(file: File, kind: "image" | "video"): Promise<{ width?: number; height?: number; durationSeconds?: number }> {
  if (kind === "image") {
    const bitmap = await createImageBitmap(file);
    const metadata = { width: bitmap.width, height: bitmap.height };
    bitmap.close();
    return metadata;
  }
  return new Promise((resolve, reject) => {
    const element = document.createElement("video");
    const url = URL.createObjectURL(file);
    element.preload = "metadata";
    element.onloadedmetadata = () => {
      URL.revokeObjectURL(url);
      resolve({ width: element.videoWidth, height: element.videoHeight, durationSeconds: Math.ceil(element.duration) });
    };
    element.onerror = () => { URL.revokeObjectURL(url); reject(new Error("Could not read video metadata")); };
    element.src = url;
  });
}

export function ComposerDialog({ open, initialMode, onClose }: { open: boolean; initialMode: "post" | "community"; onClose: () => void }) {
  const [mode, setMode] = useState(initialMode);
  const [communities, setCommunities] = useState<DrawerCommunity[]>([]);
  const [kind, setKind] = useState<PostKind>("text");
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const { online, notify } = useApp();
  const navigate = useNavigate();

  useEffect(() => setMode(initialMode), [initialMode, open]);
  useEffect(() => {
    if (!open) return;
    void api.drawer().then((drawer) => setCommunities(drawer.communities)).catch(() => setCommunities([]));
  }, [open]);

  async function submitPost(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true); setError(null); setProgress(0);
    const values = new FormData(event.currentTarget);
    try {
      const subreddit = String(values.get("subreddit") ?? "").replace(/^r\//u, "");
      const title = String(values.get("title") ?? "");
      const input: { subreddit: string; kind: PostKind; title: string; body?: string; url?: string; mediaId?: string; mediaIds?: string[] } = { subreddit, kind, title };
      if (kind === "text") input.body = String(values.get("body") ?? "");
      if (kind === "link") input.url = String(values.get("url") ?? "");
      if (kind === "image") {
        if (!online) throw new Error("Media uploads need a connection. Text and link posts can be queued offline.");
        const files = values.getAll("media").filter((value): value is File => value instanceof File && value.size > 0);
        if (files.length === 0) throw new Error("Choose at least one image");
        if (files.length > 20) throw new Error("Choose up to 20 images");
        input.mediaIds = [];
        for (const [index, file] of files.entries()) {
          const metadata = await mediaMetadata(file, kind);
          const uploaded = await api.uploadMedia(file, kind, {
            ...metadata,
            altText: String(values.get("altText") ?? ""),
          }, (fileProgress) => setProgress(Math.round(((index + fileProgress / 100) / files.length) * 100)));
          if (uploaded.delivery.status === "error") {
            throw new Error(uploaded.delivery.errorMessage ?? uploaded.delivery.imageErrorMessage ?? "Media processing failed");
          }
          input.mediaIds.push(uploaded.id);
        }
      }
      if (kind === "video") {
        if (!online) throw new Error("Media uploads need a connection. Text and link posts can be queued offline.");
        const file = values.get("media");
        if (!(file instanceof File) || file.size === 0) throw new Error("Choose a video file");
        const metadata = await mediaMetadata(file, kind);
        const uploaded = await api.uploadMedia(file, kind, { ...metadata, altText: String(values.get("altText") ?? "") }, setProgress);
        if (uploaded.delivery.status === "error") throw new Error(uploaded.delivery.errorMessage ?? "Media processing failed");
        input.mediaId = uploaded.id;
      }
      const result = await api.createPost(input);
      if (result) {
        notify("Post published", "success");
        navigate(`/post/${result.post.id}`, { viewTransition: true });
      } else {
        notify("Post queued and will publish when you reconnect", "success");
      }
      onClose();
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(false);
    }
  }

  async function submitCommunity(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true); setError(null);
    const values = new FormData(event.currentTarget);
    try {
      const input = {
        name: String(values.get("name") ?? ""),
        displayName: String(values.get("displayName") ?? ""),
        description: String(values.get("description") ?? ""),
        accessType: String(values.get("accessType") ?? "public") as Subreddit["accessType"],
      };
      const result = await api.createCommunity(input);
      if (result) {
        notify(`r/${result.subreddit.name} created`, "success");
        navigate(`/r/${result.subreddit.name}`, { viewTransition: true });
      } else {
        notify("Community creation queued", "success");
      }
      onClose();
    } catch (caught) {
      setError(errorMessage(caught));
    } finally { setBusy(false); }
  }

  return <Modal title={mode === "post" ? "Create a post" : "Create a community"} open={open} onClose={onClose} wide>
    <div className="segmented" role="tablist" aria-label="Create">
      <button type="button" role="tab" aria-selected={mode === "post"} onClick={() => { setMode("post"); setError(null); }}>Post</button>
      <button type="button" role="tab" aria-selected={mode === "community"} onClick={() => { setMode("community"); setError(null); }}>Community</button>
    </div>
    {mode === "post" ? <form className="stack-form" onSubmit={(event) => void submitPost(event)}>
      <label>Community <input name="subreddit" list="my-communities" required minLength={3} maxLength={21} placeholder="community_name" /></label>
      <datalist id="my-communities">{communities.map((community) => <option key={community.id} value={community.name}>{community.displayName}</option>)}</datalist>
      <div className="post-kind-picker" role="radiogroup" aria-label="Post type">
        {(["text", "image", "video", "link"] as PostKind[]).map((value) => <button key={value} type="button" role="radio" aria-checked={kind === value} onClick={() => setKind(value)}>{value}</button>)}
      </div>
      <label>Title <input name="title" required maxLength={300} /></label>
      {kind === "text" && <label>Body <textarea name="body" required maxLength={40_000} rows={8} /></label>}
      {kind === "link" && <label>Link <input name="url" type="url" required maxLength={2_048} placeholder="https://" /></label>}
      {(kind === "image" || kind === "video") && <>
        <label>{kind === "image" ? "Images (up to 20)" : "Video"} <input name="media" type="file" required multiple={kind === "image"} accept={kind === "image" ? "image/jpeg,image/png,image/webp,image/avif,image/gif" : "video/mp4,video/webm,video/quicktime"} /></label>
        <label>Alt text <textarea name="altText" maxLength={1_000} rows={3} placeholder="Describe the media for people using screen readers" /></label>
      </>}
      {busy && progress > 0 && <div className="upload-progress"><span style={{ width: `${progress}%` }} /><small>Uploading · {progress}%</small></div>}
      {error && <p className="form-error" role="alert">{error}</p>}
      <button className="primary-button" disabled={busy}>{busy ? <Spinner label="Publishing" /> : <Icon name="send" />} Publish</button>
    </form> : <form className="stack-form" onSubmit={(event) => void submitCommunity(event)}>
      <label>Name <div className="input-prefix"><span>r/</span><input name="name" required minLength={3} maxLength={21} pattern="[A-Za-z0-9_]+" placeholder="community_name" /></div></label>
      <label>Display name <input name="displayName" required maxLength={100} /></label>
      <label>Description <textarea name="description" maxLength={1_000} rows={5} /></label>
      <label>Access <select name="accessType" defaultValue="public"><option value="public">Public — anyone can view and post</option><option value="restricted">Restricted — anyone can view, members post</option><option value="private">Private — members only</option></select></label>
      {error && <p className="form-error" role="alert">{error}</p>}
      <button className="primary-button" disabled={busy}>{busy && <Spinner />} Create community</button>
    </form>}
  </Modal>;
}
