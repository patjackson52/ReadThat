interface SocialMetadata {
  title: string;
  description: string;
  image: string | null;
}

const DEFAULTS: SocialMetadata = {
  title: "Read That — Community conversations",
  description: "Good conversations, immersive media, and communities worth following.",
  image: "/og.png",
};

function upsert(attribute: "name" | "property", key: string, content: string | null) {
  const selector = `meta[${attribute}="${key}"]`;
  const existing = document.head.querySelector<HTMLMetaElement>(selector);
  if (content === null) { existing?.remove(); return; }
  const element = existing ?? document.createElement("meta");
  element.setAttribute(attribute, key);
  element.content = content;
  if (!existing) document.head.append(element);
}

function absolute(url: string): string {
  return new URL(url, location.origin).toString();
}

export function setSocialMetadata(metadata: Partial<SocialMetadata> = {}): void {
  const value = { ...DEFAULTS, ...metadata };
  const image = value.image ? absolute(value.image) : null;
  upsert("property", "og:title", value.title);
  upsert("property", "og:description", value.description);
  upsert("property", "og:type", "website");
  upsert("property", "og:image", image);
  upsert("name", "twitter:card", image ? "summary_large_image" : "summary");
  upsert("name", "twitter:title", value.title);
  upsert("name", "twitter:description", value.description);
  upsert("name", "twitter:image", image);
}

export function clearDetailSocialMetadata(): void {
  setSocialMetadata({
    title: "Conversation · Read That",
    description: "A conversation on Read That.",
    image: null,
  });
}
