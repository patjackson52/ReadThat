export type VoteValue = -1 | 0 | 1;
export type PostKind = "text" | "image" | "video" | "link";

export interface User {
  id: string;
  username: string;
  displayName: string;
  bio?: string;
  avatarUrl?: string | null;
  karma?: number;
  createdAt?: number;
  updatedAt?: number;
}

export interface Session {
  sessionId: string;
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: number;
  refreshExpiresAt: number;
}

export interface AuthState {
  user: User;
  session: Session;
}

export interface ApiErrorBody {
  error?: { code?: string; message?: string; details?: unknown };
  code?: string;
  message?: string;
}

export interface MetadataCell {
  type: "metadata";
  cellId: string;
  subreddit: string;
  author: string;
  postedAgo: string;
  createdAt: number;
  pinned: boolean;
}

export interface TitleCell { type: "title"; cellId: string; text: string }
export interface TextCell { type: "text"; cellId: string; body: string; maxLines: number }
export interface LinkCell { type: "link"; cellId: string; url: string; domain: string }
export interface AnnouncementCell {
  type: "announcement";
  cellId: string;
  text: string;
  sourcePostId: string;
}

export interface ImageCell {
  type: "image";
  cellId: string;
  url: string;
  cacheKey: string;
  placeholderColor: number;
  aspectRatio: number;
  altText: string;
}

export interface GalleryImage {
  mediaId: string | null;
  url: string | null;
  zoomUrl: string | null;
  cacheKey: string | null;
  placeholderColor: number;
  aspectRatio: number;
  altText: string;
  width: number | null;
  height: number | null;
}

export interface ImageCarouselCell {
  type: "image_carousel";
  cellId: string;
  items: GalleryImage[];
}

export interface VideoCell {
  type: "video";
  cellId: string;
  url: string | null;
  hlsUrl: string | null;
  dashUrl: string | null;
  posterUrl: string | null;
  previewUrl: string | null;
  fallbackUrl: string | null;
  deliveryStatus: "waiting" | "processing" | "ready" | "error" | "not_applicable";
  processingProgress: number;
  cachePolicy: "segments_only";
  placeholderColor: number;
  aspectRatio: number;
  durationSeconds: number;
  altText: string;
}

export interface ActionBarCell {
  type: "actionbar";
  cellId: string;
  score: number;
  commentCount: number;
  liked: boolean;
  vote: VoteValue;
  version: number;
}

export interface AdHeaderCell {
  type: "ad_header";
  cellId: string;
  adId: string;
  author: string;
  avatarUrl: string | null;
  label: string;
}

export interface AdTitleCell { type: "ad_title"; cellId: string; adId: string; text: string }

export interface AdMediaItem {
  creativeId: string;
  kind: "image" | "video";
  placeholderColor: number;
  aspectRatio: number;
  altText: string;
  imageUrl?: string | null;
  hlsUrl?: string | null;
  dashUrl?: string | null;
  posterUrl?: string | null;
  fallbackUrl?: string | null;
  durationSeconds?: number | null;
  cacheKey?: string | null;
}

export interface AdMediaCell {
  type: "ad_media";
  cellId: string;
  adId: string;
  items: AdMediaItem[];
  destinationUrl: string;
  displayDomain: string;
  ctaLabel: string;
}

export interface AdSummaryCell {
  type: "ad_summary";
  cellId: string;
  adId: string;
  text: string;
  disclosureLabel: string;
}

export interface RelatedPost {
  postId: string;
  title: string;
  subreddit: string;
  score: number;
}

export interface AdRelatedPostsCell {
  type: "ad_related_posts";
  cellId: string;
  adId: string;
  posts: RelatedPost[];
  disclosureLabel: string;
}

export interface AdActionBarCell {
  type: "ad_actionbar";
  cellId: string;
  adId: string;
  commentCount: number;
}

export type FeedCell = MetadataCell | TitleCell | TextCell | LinkCell | AnnouncementCell
  | ImageCell | ImageCarouselCell | VideoCell | ActionBarCell | AdHeaderCell | AdTitleCell
  | AdMediaCell | AdSummaryCell | AdRelatedPostsCell | AdActionBarCell;
export interface FeedGroup { groupId: string; cells: FeedCell[] }
export interface FeedPage {
  schemaVersion: number;
  feedId: string;
  serverTime: number;
  groups: FeedGroup[];
  nextCursor: string | null;
}

export interface MediaFeedPage {
  schemaVersion: number;
  feedId: string;
  snapshotAt: number;
  anchorIncluded: boolean;
  items: Post[];
  nextCursor: string | null;
}

export interface MediaAsset {
  id: string;
  contentType: string | null;
  width: number | null;
  height: number | null;
  durationSeconds: number | null;
  altText: string | null;
  url: string | null;
  zoomUrl?: string | null;
  hlsUrl: string | null;
  dashUrl: string | null;
  posterUrl: string | null;
  previewUrl: string | null;
  fallbackUrl: string | null;
  deliveryStatus: string;
  processingProgress: number;
  cachePolicy: string;
  cacheKey: string | null;
}

export interface Post {
  id: string;
  subreddit: string;
  author: string;
  authorId: string;
  kind: PostKind;
  title: string;
  body: string | null;
  url: string | null;
  media: MediaAsset | null;
  mediaItems?: MediaAsset[];
  crosspostParentId: string | null;
  score: number;
  upvotes: number;
  downvotes: number;
  commentCount: number;
  viewerVote: VoteValue;
  version: number;
  createdAt: number;
  updatedAt: number;
}

export interface CommentNode {
  type: "comment";
  id: string;
  author: string;
  body: string;
  score: number;
  viewerVote: VoteValue;
  createdAt: number;
  createdAgoMin: number;
  /** Comment nodes already present below this node; load-more cursors are excluded. */
  descendantCount: number;
  children: TreeNode[];
  pending?: boolean;
}

export type CommentSort = "best" | "top" | "qa" | "controversial" | "new" | "old";

export interface LoadMoreNode {
  type: "load_more";
  id: string;
  parentId: string | null;
  remainingCount: number;
  childIds: string[];
  sort: CommentSort;
}

export type TreeNode = CommentNode | LoadMoreNode;
export interface CommentTree {
  postId: string;
  roots: TreeNode[];
  requestedCount: number;
  requestedDepth: number;
  sort: CommentSort;
  corpusTruncated: boolean;
  cacheStatus: "hit" | "miss";
}

export interface Subreddit {
  id: string;
  name: string;
  displayName: string;
  description: string;
  accessType: "public" | "restricted" | "private";
  viewerRole: "owner" | "moderator" | "member" | "subscriber" | "banned" | null;
  subscriberCount: number;
  createdAt: number;
  updatedAt: number;
}

export interface DrawerCommunity {
  id: string;
  name: string;
  displayName: string;
  accessType: Subreddit["accessType"];
  role: Exclude<Subreddit["viewerRole"], "banned" | null>;
}

export interface CommunityDrawer {
  communities: DrawerCommunity[];
  recentlyVisited: Array<{ id: string; name: string; displayName: string; visitedAt: number }>;
  nextCursor: string | null;
  validator: string;
}

export interface UploadSession {
  id: string;
  mode: "single" | "multipart";
  uploadToken: string;
  expiresAt: number;
  partSize: number | null;
  partCount: number;
  uploadPath: string;
  completePath: string | null;
}

export interface UploadedMedia {
  id: string;
  kind: "image" | "video";
  status: string;
  delivery: {
    provider: string;
    status: string;
    progress: number;
    hlsUrl: string | null;
    thumbnailUrl: string | null;
    errorMessage: string | null;
    imageErrorMessage: string | null;
  };
}

export type OutboxKind = "vote" | "comment" | "post" | "community" | "visit";
export interface OutboxEntry {
  id: string;
  accountId: string;
  kind: OutboxKind;
  path: string;
  method: "POST" | "PUT" | "PATCH" | "DELETE";
  body: Record<string, unknown>;
  createdAt: number;
  attempts: number;
  lastError?: string;
}

export type SearchItem =
  | { type: "post"; id: string; subreddit: string; author: string; kind: PostKind; title: string; body: string | null; url: string | null; score: number; commentCount: number; viewerVote: VoteValue; createdAt: number; media?: { id: string; thumbnailUrl: string | null; width: number | null; height: number | null; durationSeconds: number | null; cacheKey: string | null } | null }
  | { type: "community"; id: string; name: string; displayName: string; description: string; accessType: string; subscriberCount: number }
  | { type: "comment"; id: string; postId: string; parentId: string | null; author: string; body: string; score: number; viewerVote: VoteValue; createdAt: number; post: { title: string; subreddit: string; score: number; commentCount: number } }
  | { type: "profile"; id: string; username: string; displayName: string; bio: string; avatarUrl: string | null; karma: number };

export type SearchType = "all" | "posts" | "communities" | "comments" | "media" | "profiles";
export type SearchSort = "relevance" | "hot" | "top" | "new" | "comments";
export type SearchTime = "all" | "year" | "month" | "week" | "day" | "hour";

export interface SearchSections {
  communities: SearchItem[];
  posts: SearchItem[];
  comments: SearchItem[];
  media: SearchItem[];
  profiles: SearchItem[];
}

export interface SearchPageResponse {
  query: string;
  type: SearchType;
  items?: SearchItem[];
  sections?: SearchSections;
  nextCursor: string | null;
}

export interface SearchTypeahead {
  query: string;
  completions: string[];
  communities: SearchItem[];
  profiles: SearchItem[];
}

export interface SearchDiscover {
  trending: Array<{
    id: string;
    query: string;
    subreddit: string;
    kind: PostKind;
    score: number;
    commentCount: number;
    createdAt: number;
  }>;
  communities: Array<{
    id: string;
    name: string;
    displayName: string;
    subscriberCount: number;
  }>;
}

export interface AdLaunchContext {
  adId: string;
  creativeId: string;
  kind: "image" | "video";
  aspectRatio: number;
  altText: string;
  imageUrl: string | null;
  hlsUrl: string | null;
  posterUrl: string | null;
  fallbackUrl: string | null;
  cacheKey: string;
  destinationUrl: string;
  displayDomain: string;
  ctaLabel: string;
  selectedIndex: number;
}
