import type { ButtonHTMLAttributes, ReactNode, SVGProps } from "react";

export type IconName = "arrow-down" | "arrow-up" | "check" | "comment" | "community" | "create" | "home" | "install" | "menu" | "offline" | "pause" | "play" | "refresh" | "search" | "send" | "share" | "user" | "video" | "volume" | "volume-off" | "x";

const paths: Record<IconName, ReactNode> = {
  "arrow-down": <path d="m7 10 5 5 5-5" />,
  "arrow-up": <path d="m7 14 5-5 5 5" />,
  check: <path d="m5 12 4 4L19 6" />,
  comment: <><path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4Z" /><path d="M8 9h8M8 13h5" /></>,
  community: <><circle cx="8" cy="9" r="3" /><circle cx="17" cy="8" r="2.5" /><path d="M2.5 20c.5-4 2.5-6 5.5-6s5 2 5.5 6M14 14c3.8-.7 6.3 1.3 7 4.5" /></>,
  create: <><path d="M12 5v14M5 12h14" /><circle cx="12" cy="12" r="10" /></>,
  home: <><path d="m3 11 9-8 9 8" /><path d="M5 10v10h14V10M9 20v-6h6v6" /></>,
  install: <><path d="M12 3v12m0 0 4-4m-4 4-4-4" /><path d="M5 17v3h14v-3" /></>,
  menu: <path d="M4 7h16M4 12h16M4 17h16" />,
  offline: <><path d="M3 3l18 18" /><path d="M8.5 8.5A9 9 0 0 1 21 10M3 10a9 9 0 0 1 2.5-2M6 14a8 8 0 0 1 6-2c1 0 2 .2 3 .5M9 18a4 4 0 0 1 6 0M12 21h.01" /></>,
  pause: <><path d="M9 7v10" /><path d="M15 7v10" /></>,
  play: <path d="m9 7 8 5-8 5Z" />,
  refresh: <><path d="M20 6v5h-5" /><path d="M4 18v-5h5" /><path d="M6.1 9a7 7 0 0 1 11.7-2.6L20 9M4 15l2.2 2.6A7 7 0 0 0 18 15" /></>,
  search: <><circle cx="11" cy="11" r="7" /><path d="m20 20-4-4" /></>,
  send: <><path d="m22 2-7 20-4-9-9-4Z" /><path d="M22 2 11 13" /></>,
  share: <><circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle cx="18" cy="19" r="3" /><path d="m8.6 10.5 6.8-4M8.6 13.5l6.8 4" /></>,
  user: <><circle cx="12" cy="8" r="4" /><path d="M4 21c.7-5 3.3-7 8-7s7.3 2 8 7" /></>,
  video: <><rect x="3" y="5" width="14" height="14" rx="2" /><path d="m17 10 4-2v8l-4-2Z" /></>,
  volume: <><path d="M11 5 6 9H3v6h3l5 4Z" /><path d="M15 9a4 4 0 0 1 0 6M18 6a8 8 0 0 1 0 12" /></>,
  "volume-off": <><path d="M11 5 6 9H3v6h3l5 4Z" /><path d="m16 10 5 5m0-5-5 5" /></>,
  x: <path d="m6 6 12 12M18 6 6 18" />,
};

export function Icon({ name, ...props }: { name: IconName } & SVGProps<SVGSVGElement>) {
  return <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" {...props}>{paths[name]}</svg>;
}

export function Spinner({ label = "Loading" }: { label?: string }) {
  return <span className="spinner" role="status"><span aria-hidden="true" /> <span className="sr-only">{label}</span></span>;
}

export function EmptyState({ icon, title, children }: { icon: IconName; title: string; children: ReactNode }) {
  return <div className="empty-state"><Icon name={icon} /><h2>{title}</h2><p>{children}</p></div>;
}

export function IconButton({ label, icon, ...props }: { label: string; icon: IconName } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button type="button" className="icon-button" aria-label={label} title={label} {...props}><Icon name={icon} /></button>;
}

export function formatCount(value: number): string {
  return Intl.NumberFormat(undefined, { notation: value >= 1_000 ? "compact" : "standard", maximumFractionDigits: 1 }).format(value);
}

export function formatRelative(epochMs: number): string {
  const seconds = Math.round((epochMs - Date.now()) / 1_000);
  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
  if (Math.abs(seconds) < 60) return formatter.format(seconds, "second");
  const minutes = Math.round(seconds / 60);
  if (Math.abs(minutes) < 60) return formatter.format(minutes, "minute");
  const hours = Math.round(minutes / 60);
  if (Math.abs(hours) < 24) return formatter.format(hours, "hour");
  return formatter.format(Math.round(hours / 24), "day");
}
