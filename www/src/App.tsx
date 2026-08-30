import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link, NavLink, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { api } from "./api";
import { useApp } from "./app-context";
import { AuthDialog, ComposerDialog } from "./dialogs";
import { FeedPage } from "./feed-page";
import { PostPage } from "./post-page";
import { ProfilePage } from "./profile-page";
import { activatePwaUpdate } from "./pwa";
import { SearchPage } from "./search-page";
import type { CommunityDrawer } from "./types";
import { Icon, Spinner } from "./ui";

interface InstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
}

export default function App() {
  const { auth, authLoading, authRequestId, online, pendingMutations, notify, signInRequested } = useApp();
  const [authOpen, setAuthOpen] = useState(false);
  const [composer, setComposer] = useState<"post" | "community" | null>(null);
  const [mobileMenu, setMobileMenu] = useState(false);
  const [drawer, setDrawer] = useState<CommunityDrawer | null>(null);
  const [installPrompt, setInstallPrompt] = useState<InstallPromptEvent | null>(null);
  const [updateReady, setUpdateReady] = useState(false);
  const navigate = useNavigate();

  useEffect(() => { if (authRequestId > 0) setAuthOpen(true); }, [authRequestId]);
  useEffect(() => {
    if (!auth) { setDrawer(null); return; }
    void api.drawer().then(setDrawer).catch(() => setDrawer(null));
  }, [auth, pendingMutations]);
  useEffect(() => {
    const install = (event: Event) => { event.preventDefault(); setInstallPrompt(event as InstallPromptEvent); };
    const updated = () => setUpdateReady(true);
    const offlineReady = () => notify("ReadThat is ready offline", "success");
    addEventListener("beforeinstallprompt", install);
    addEventListener("readthat:pwa-update", updated);
    addEventListener("readthat:pwa-offline-ready", offlineReady);
    return () => {
      removeEventListener("beforeinstallprompt", install);
      removeEventListener("readthat:pwa-update", updated);
      removeEventListener("readthat:pwa-offline-ready", offlineReady);
    };
  }, [notify]);

  const openComposer = useCallback((mode: "post" | "community") => {
    if (!auth) { signInRequested(); return; }
    setComposer(mode);
  }, [auth, signInRequested]);

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const query = String(new FormData(event.currentTarget).get("q") ?? "").trim();
    if (query) navigate(`/search?q=${encodeURIComponent(query)}`, { viewTransition: true });
  }

  async function install() {
    if (!installPrompt) return;
    await installPrompt.prompt();
    await installPrompt.userChoice;
    setInstallPrompt(null);
  }

  return <>
    <header className="app-header">
      <div className="header-inner">
        <button className="icon-button mobile-only" type="button" aria-label="Open menu" aria-expanded={mobileMenu} onClick={() => setMobileMenu((value) => !value)}><Icon name="menu" /></button>
        <Link className="brand" to="/" viewTransition aria-label="ReadThat home"><span className="brand-mark"><i /><i /><i /></span><strong>ReadThat</strong></Link>
        <form className="global-search" role="search" onSubmit={search}><button className="search-submit" type="submit" aria-label="Search"><Icon name="search" /></button><input name="q" aria-label="Search ReadThat" placeholder="Search conversations" /><kbd>⌘ K</kbd></form>
        <div className="header-actions">
          {installPrompt && <button className="header-button desktop-only" type="button" onClick={() => void install()}><Icon name="install" /> Install</button>}
          <button className="header-button desktop-only" type="button" onClick={() => openComposer("post")}><Icon name="create" /> Create</button>
          {authLoading ? <Spinner label="Restoring session" /> : auth ? <div className="account-menu"><button className="avatar-button" type="button" aria-label="Account menu"><span>{auth.user.displayName.slice(0, 1).toUpperCase()}</span><small>u/{auth.user.username}</small></button><div className="account-popover"><Link to={`/u/${auth.user.username}`} viewTransition>View profile</Link><button type="button" onClick={() => void api.logout().then(() => notify("Signed out", "success"))}>Sign out</button></div></div>
            : <button className="primary-button compact" type="button" onClick={() => setAuthOpen(true)}>Sign in</button>}
        </div>
      </div>
    </header>
    {!online && <div className="network-banner"><Icon name="offline" /> You’re offline. Saved feeds still work, and changes will sync later.</div>}
    {pendingMutations > 0 && <div className="sync-banner"><span className="sync-dot" /> {pendingMutations} change{pendingMutations === 1 ? "" : "s"} waiting to sync</div>}
    {updateReady && <div className="update-banner">A fresh version is ready. <button type="button" onClick={() => void activatePwaUpdate()}>Update now</button></div>}
    <div className="app-grid">
      <aside className={`sidebar${mobileMenu ? " sidebar-open" : ""}`}>
        <nav aria-label="Primary">
          <NavLink to="/" end viewTransition onClick={() => setMobileMenu(false)}><Icon name="home" /> Home</NavLink>
          <NavLink to="/search" viewTransition onClick={() => setMobileMenu(false)}><Icon name="search" /> Discover</NavLink>
        </nav>
        <button className="create-community" type="button" onClick={() => openComposer("community")}><Icon name="community" /> Create community</button>
        {drawer && drawer.communities.length > 0 && <section><h2>Your communities</h2>{drawer.communities.map((community) => <NavLink key={community.id} to={`/r/${community.name}`} viewTransition onClick={() => setMobileMenu(false)}><span className="mini-community">r/</span><span><strong>{community.displayName}</strong><small>r/{community.name}</small></span></NavLink>)}</section>}
        {!auth && <section className="sidebar-callout"><span>✦</span><h2>Your communities, anywhere</h2><p>Sign in to post, vote, and carry offline changes across reconnects.</p><button type="button" onClick={() => setAuthOpen(true)}>Join ReadThat</button></section>}
      </aside>
      <main id="main-content">
        <Routes>
          <Route path="/" element={<FeedPage />} />
          <Route path="/r/:name" element={<FeedPage />} />
          <Route path="/post/:postId" element={<PostPage />} />
          <Route path="/search" element={<SearchPage />} />
          <Route path="/u/:username" element={<ProfilePage />} />
          <Route path="*" element={<Navigate replace to="/" />} />
        </Routes>
      </main>
      <aside className="right-rail">
        <section className="about-card"><p className="eyebrow">About this build</p><h2>Community, without the wait</h2><p>Versioned server-driven cards, optimistic offline writes, bounded media prefetching, and one adaptive video at a time.</p><div><span><i className={online ? "online" : "offline"} /> {online ? "Edge connected" : "Offline mode"}</span><span>PWA ready</span></div></section>
        <footer><span>ReadThat</span><span>Privacy · Accessibility · About</span></footer>
      </aside>
    </div>
    <nav className="mobile-nav" aria-label="Mobile navigation">
      <NavLink to="/" end viewTransition><Icon name="home" /><span>Home</span></NavLink>
      <NavLink to="/search" viewTransition><Icon name="search" /><span>Search</span></NavLink>
      <button type="button" onClick={() => openComposer("post")}><Icon name="create" /><span>Create</span></button>
      <button type="button" onClick={() => auth ? navigate(`/u/${auth.user.username}`) : setAuthOpen(true)}><Icon name="user" /><span>Account</span></button>
    </nav>
    <AuthDialog open={authOpen} onClose={() => setAuthOpen(false)} />
    <ComposerDialog open={composer !== null} initialMode={composer ?? "post"} onClose={() => setComposer(null)} />
  </>;
}
