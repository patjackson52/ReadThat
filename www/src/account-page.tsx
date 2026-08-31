import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { api } from "./api";
import { useApp } from "./app-context";
import { formatCount, Icon, Spinner } from "./ui";

export function AccountPage() {
  const { auth, authLoading, notify, signInRequested } = useApp();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { document.title = auth ? `${auth.user.displayName} · Read That` : "Sign in · Read That"; }, [auth]);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!auth) return;
    const form = event.currentTarget;
    const values = new FormData(form);
    setBusy(true); setError(null);
    try {
      let avatarMediaId: string | undefined;
      const avatar = values.get("avatar");
      if (avatar instanceof File && avatar.size > 0) {
        const bitmap = await createImageBitmap(avatar);
        const width = bitmap.width;
        const height = bitmap.height;
        bitmap.close();
        const uploaded = await api.uploadMedia(avatar, "image", { width, height, altText: `${auth.user.displayName} avatar` }, () => undefined);
        if (uploaded.delivery.status === "error") throw new Error(uploaded.delivery.imageErrorMessage ?? "Avatar processing failed");
        avatarMediaId = uploaded.id;
      }
      await api.updateProfile({
        displayName: String(values.get("displayName") ?? ""),
        bio: String(values.get("bio") ?? ""),
        ...(avatarMediaId ? { avatarMediaId } : {}),
      });
      const avatarInput = form.elements.namedItem("avatar") as HTMLInputElement | null;
      if (avatarInput) avatarInput.value = "";
      notify("Profile updated", "success");
    } catch (caught) { setError(caught instanceof Error ? caught.message : "Profile could not be updated"); }
    finally { setBusy(false); }
  }

  if (authLoading) return <div className="detail-loading"><Spinner /> Restoring your account…</div>;
  if (!auth) return <div className="account-signin-card">
    <div className="account-signin-mark"><Icon name="user" /></div>
    <p className="eyebrow">Your Read That account</p>
    <h1>Join the conversation</h1>
    <p>Sign in to vote, comment, create posts, follow communities, and sync changes after reconnecting.</p>
    <button className="primary-button" type="button" onClick={signInRequested}>Sign in or create account</button>
  </div>;

  return <div className="account-page">
    <section className="account-hero">
      {auth.user.avatarUrl ? <img src={auth.user.avatarUrl} alt="" /> : <span>{auth.user.displayName.slice(0, 1).toUpperCase()}</span>}
      <div><p className="eyebrow">Your account</p><h1>{auth.user.displayName}</h1><Link to={`/u/${auth.user.username}`} viewTransition>u/{auth.user.username} · {formatCount(auth.user.karma ?? 0)} karma</Link></div>
    </section>
    <section className="account-settings">
      <div><h2>Edit profile</h2><p>These details appear on your public profile.</p></div>
      <form className="stack-form" onSubmit={(event) => void save(event)}>
        <label>Display name <input name="displayName" required maxLength={50} defaultValue={auth.user.displayName} /></label>
        <label>Bio <textarea name="bio" maxLength={500} rows={5} defaultValue={auth.user.bio ?? ""} /></label>
        <label>Profile photo <input name="avatar" type="file" accept="image/jpeg,image/png,image/webp,image/avif" /></label>
        <p className="field-note">Up to 10 MB. JPEG, PNG, WebP, or AVIF.</p>
        {error && <p className="form-error" role="alert">{error}</p>}
        <button className="primary-button" disabled={busy}>{busy ? <Spinner label="Saving profile" /> : <Icon name="check" />} Save profile</button>
      </form>
    </section>
    <button className="secondary-button account-signout" type="button" onClick={() => void api.logout().then(() => notify("Signed out", "success"))}>Sign out</button>
  </div>;
}
