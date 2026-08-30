import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { api } from "./api";
import type { User } from "./types";
import { EmptyState, formatCount, Icon, Spinner } from "./ui";

export function ProfilePage() {
  const { username = "" } = useParams<{ username: string }>();
  const [user, setUser] = useState<User | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    setUser(null); setError(null);
    void api.user(username).then((value) => { if (live) setUser(value); }).catch((caught) => { if (live) setError(caught instanceof Error ? caught.message : "Profile unavailable"); });
    return () => { live = false; };
  }, [username]);
  useEffect(() => { if (user) document.title = `${user.displayName} · ReadThat`; }, [user]);
  if (error) return <EmptyState icon="user" title="Profile unavailable">{error}</EmptyState>;
  if (!user) return <div className="detail-loading"><Spinner /> Loading profile…</div>;
  return <div className="profile-page">
    <section className="profile-hero">
      {user.avatarUrl ? <img src={user.avatarUrl} alt="" /> : <div className="profile-placeholder"><Icon name="user" /></div>}
      <div><p className="eyebrow">u/{user.username}</p><h1>{user.displayName}</h1><p>{user.bio || "This person has not written a bio yet."}</p></div>
    </section>
    <div className="profile-stats"><div><strong>{formatCount(user.karma ?? 0)}</strong><span>Karma</span></div><div><strong>{user.createdAt ? new Date(user.createdAt).toLocaleDateString(undefined, { month: "short", year: "numeric" }) : "—"}</strong><span>Joined</span></div></div>
  </div>;
}
