import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const catalog = JSON.parse(await readFile(
  join(scriptDirectory, "../fixtures/rick-and-morty-demo.json"),
  "utf8",
));
const planOnly = process.argv.includes("--plan");
const rebalanceFeedOnly = process.argv.includes("--rebalance-feed");
const local = process.env.D1_LOCAL === "1";
const database = process.env.D1_DATABASE || catalog.collection.database;
const r2Bucket = process.env.R2_BUCKET || catalog.collection.r2Bucket;
const wranglerConfig = process.env.WRANGLER_CONFIG?.trim() || null;
const baseUrl = (process.env.API_BASE_URL
  || (local ? "http://127.0.0.1:8787" : catalog.collection.deployedApi)).replace(/\/$/u, "");
const fixtureVersion = catalog.collection.fixtureVersion;
const fixtureNamespace = `rick-and-morty:v${fixtureVersion}`;
const baseTimestamp = Date.parse(catalog.collection.createdAt);

function deterministicUuid(value) {
  const bytes = createHash("sha256").update(value).digest().subarray(0, 16);
  bytes[6] = (bytes[6] & 0x0f) | 0x50;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function sqlString(value) {
  return value === null ? "NULL" : `'${String(value).replaceAll("'", "''")}'`;
}

function unique(values) {
  return [...new Set(values)];
}

function postVoters(post) {
  return unique([post.author, ...post.comments.map((comment) => comment.author)]);
}

function postTimestamp(post) {
  return Date.parse(post.publishedAt);
}

function expectedPostRank(post) {
  return post.seedUpvotes * 1_000_000_000 + postTimestamp(post);
}

function run(command, args, { parseJson = false } = {}) {
  const result = spawnSync(command, args, {
    cwd: join(scriptDirectory, ".."),
    encoding: "utf8",
    maxBuffer: 20 * 1024 * 1024,
  });
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed (${result.status}): ${result.stderr || result.stdout}`);
  }
  return parseJson ? JSON.parse(result.stdout) : result.stdout;
}

function runSql(sql) {
  const args = ["wrangler", "d1", "execute", database, "--json", "--command", sql];
  if (wranglerConfig) args.push("--config", wranglerConfig);
  args.push(local ? "--local" : "--remote");
  return run(process.platform === "win32" ? "npx.cmd" : "npx", args, { parseJson: true });
}

function uploadR2(path, key, contentType) {
  const args = [
    "wrangler", "r2", "object", "put", `${r2Bucket}/${key}`,
    local ? "--local" : "--remote",
    "--file", path,
    "--content-type", contentType,
    "--cache-control", "private, max-age=31536000, immutable",
  ];
  if (wranglerConfig) args.push("--config", wranglerConfig);
  run(process.platform === "win32" ? "npx.cmd" : "npx", args);
}

async function fetchJson(url, attempts = 5) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await fetch(url, { headers: { accept: "application/json" } });
      if (response.ok) return response.json();
      lastError = new Error(`${url}: ${response.status} ${await response.text()}`);
      if (response.status !== 429 && response.status < 500) break;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, attempt * 500));
  }
  throw lastError;
}

async function fetchBytes(url, attempts = 5) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await fetch(url, { headers: { accept: "image/jpeg,image/*" } });
      if (response.ok) {
        return {
          bytes: Buffer.from(await response.arrayBuffer()),
          contentType: response.headers.get("content-type")?.split(";", 1)[0] ?? "",
        };
      }
      lastError = new Error(`${url}: ${response.status} ${await response.text()}`);
      if (response.status !== 429 && response.status < 500) break;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, attempt * 500));
  }
  throw lastError;
}

function validateCatalog() {
  if (!Number.isInteger(fixtureVersion) || fixtureVersion < 1) throw new Error("fixtureVersion must be positive");
  if (!Number.isInteger(catalog.collection.feedBalanceRevision) || catalog.collection.feedBalanceRevision < 1) {
    throw new Error("collection.feedBalanceRevision must be positive");
  }
  if (!Number.isFinite(baseTimestamp)) throw new Error("collection.createdAt must be an ISO timestamp");
  if (catalog.collection.fictionalProfiles !== true) throw new Error("Profiles must be explicitly fictional");

  const usernames = catalog.characters.map((character) => character.username);
  if (new Set(usernames).size !== usernames.length) throw new Error("Duplicate character username");
  const characterIds = catalog.characters.map((character) => character.api.id);
  if (new Set(characterIds).size !== characterIds.length) throw new Error("Duplicate Rick and Morty API character id");
  for (const character of catalog.characters) {
    if (!/^[a-z0-9_]{3,24}$/u.test(character.username)) {
      throw new Error(`${character.username}: invalid ReadThat username`);
    }
    if (character.displayName.length < 1 || character.displayName.length > 50) {
      throw new Error(`${character.username}: display name must be 1–50 characters`);
    }
    if (character.bio.length > 500) throw new Error(`${character.username}: bio exceeds 500 characters`);
  }

  const characterByUsername = new Map(catalog.characters.map((character) => [character.username, character]));
  const characterById = new Map(catalog.characters.map((character) => [character.api.id, character]));
  const mediaByCharacterId = new Map(catalog.media.map((media) => [media.characterId, media]));
  if (mediaByCharacterId.size !== catalog.media.length) throw new Error("Duplicate media character id");
  if (
    mediaByCharacterId.size !== characterById.size
    || [...characterById.keys()].some((characterId) => !mediaByCharacterId.has(characterId))
  ) {
    throw new Error("Every character must have one pinned portrait media record");
  }
  for (const media of catalog.media) {
    if (!characterById.has(media.characterId)) throw new Error(`Media character ${media.characterId} is missing`);
    if (!/^[a-f0-9]{64}$/u.test(media.sha256)) throw new Error(`Media ${media.characterId}: invalid SHA-256`);
  }

  const communityNames = catalog.communities.map((community) => community.name);
  if (new Set(communityNames).size !== communityNames.length) throw new Error("Duplicate community name");
  for (const community of catalog.communities) {
    if (!/^[a-z0-9_]{3,21}$/u.test(community.name)) throw new Error(`${community.name}: invalid community name`);
    if (!characterByUsername.has(community.owner)) throw new Error(`${community.name}: missing owner ${community.owner}`);
    if (!characterById.has(community.avatarCharacterId)) {
      throw new Error(`${community.name}: missing avatar character ${community.avatarCharacterId}`);
    }
    for (const member of community.members) {
      if (!characterByUsername.has(member)) throw new Error(`${community.name}: missing member ${member}`);
    }
  }

  const communityByName = new Map(catalog.communities.map((community) => [community.name, community]));
  const fixtureIds = catalog.posts.map((post) => post.fixtureId);
  if (new Set(fixtureIds).size !== fixtureIds.length) throw new Error("Duplicate post fixture id");
  for (const post of catalog.posts) {
    if (!communityByName.has(post.subreddit)) throw new Error(`${post.fixtureId}: missing community`);
    if (!characterByUsername.has(post.author)) throw new Error(`${post.fixtureId}: missing author`);
    if (post.title.length < 1 || post.title.length > 300) throw new Error(`${post.fixtureId}: invalid title length`);
    if (post.body.length < 1 || post.body.length > 40_000) throw new Error(`${post.fixtureId}: invalid body length`);
    const publishedAt = postTimestamp(post);
    const voters = postVoters(post);
    if (!Number.isFinite(publishedAt) || publishedAt < baseTimestamp) {
      throw new Error(`${post.fixtureId}: publishedAt must be an ISO timestamp after collection.createdAt`);
    }
    if (!Number.isInteger(post.seedUpvotes) || post.seedUpvotes < 1 || post.seedUpvotes > voters.length) {
      throw new Error(`${post.fixtureId}: seedUpvotes must be between 1 and ${voters.length}`);
    }
    if (post.kind === "image") {
      const author = characterByUsername.get(post.author);
      if (!mediaByCharacterId.has(post.mediaCharacterId)) throw new Error(`${post.fixtureId}: media is missing`);
      if (author.api.id !== post.mediaCharacterId) {
        throw new Error(`${post.fixtureId}: image media must be owned by the author`);
      }
    } else if (post.kind !== "text" || post.mediaCharacterId !== undefined) {
      throw new Error(`${post.fixtureId}: unsupported post/media combination`);
    }
    const refs = new Set();
    for (const comment of post.comments) {
      if (refs.has(comment.ref)) throw new Error(`${post.fixtureId}: duplicate comment ref ${comment.ref}`);
      if (comment.parentRef && !refs.has(comment.parentRef)) {
        throw new Error(`${post.fixtureId}: parent ${comment.parentRef} must precede its child`);
      }
      if (!characterByUsername.has(comment.author)) {
        throw new Error(`${post.fixtureId}: missing comment author ${comment.author}`);
      }
      if (comment.body.length < 1 || comment.body.length > 10_000) {
        throw new Error(`${post.fixtureId}/${comment.ref}: invalid comment length`);
      }
      refs.add(comment.ref);
    }
  }

  if (catalog.episodes.length !== 5 || catalog.episodes.some((episode, index) => episode.rank !== index + 1)) {
    throw new Error("Exactly five episodes in rank order are required");
  }
  for (const episode of catalog.episodes) {
    if (!communityByName.has(episode.community)) throw new Error(`${episode.name}: missing episode community`);
  }
}

async function validateRickAndMortyApi() {
  const requestedCharacterIds = catalog.characters.map((character) => character.api.id).join(",");
  const requestedEpisodeIds = catalog.episodes.map((episode) => episode.id).join(",");
  const [charactersPayload, episodesPayload] = await Promise.all([
    fetchJson(`${catalog.collection.characterApi}/character/${requestedCharacterIds}`),
    fetchJson(`${catalog.collection.characterApi}/episode/${requestedEpisodeIds}`),
  ]);
  const characters = Array.isArray(charactersPayload) ? charactersPayload : [charactersPayload];
  const episodes = Array.isArray(episodesPayload) ? episodesPayload : [episodesPayload];
  const liveCharacterById = new Map(characters.map((character) => [character.id, character]));
  const liveEpisodeById = new Map(episodes.map((episode) => [episode.id, episode]));

  for (const expected of catalog.characters) {
    const actual = liveCharacterById.get(expected.api.id);
    const fields = {
      name: actual?.name,
      status: actual?.status,
      species: actual?.species,
      type: actual?.type,
      gender: actual?.gender,
      origin: actual?.origin?.name,
      image: actual?.image,
    };
    for (const [key, value] of Object.entries(fields)) {
      if (value !== expected.api[key]) {
        throw new Error(`${expected.username}: API ${key} changed (expected ${expected.api[key]}, found ${value})`);
      }
    }
  }
  for (const expected of catalog.episodes) {
    const actual = liveEpisodeById.get(expected.id);
    if (
      actual?.name !== expected.name
      || actual?.episode !== expected.code
      || actual?.air_date !== expected.airDate
      || actual?.url !== expected.apiUrl
    ) {
      throw new Error(`${expected.name}: Rick and Morty API episode metadata changed`);
    }
  }
}

async function downloadAndValidateMedia() {
  const characterById = new Map(catalog.characters.map((character) => [character.api.id, character]));
  const sources = new Map();
  for (const media of catalog.media) {
    const character = characterById.get(media.characterId);
    const source = await fetchBytes(character.api.image);
    const digest = createHash("sha256").update(source.bytes).digest("hex");
    if (source.contentType !== media.contentType) {
      throw new Error(`${character.username}: expected ${media.contentType}, found ${source.contentType}`);
    }
    if (source.bytes.length !== media.byteSize) {
      throw new Error(`${character.username}: expected ${media.byteSize} bytes, found ${source.bytes.length}`);
    }
    if (digest !== media.sha256) throw new Error(`${character.username}: portrait SHA-256 mismatch`);
    sources.set(media.characterId, source.bytes);
  }
  return sources;
}

function userId(username) {
  return deterministicUuid(`${fixtureNamespace}:user:${username}`);
}

function communityId(name) {
  return deterministicUuid(`${fixtureNamespace}:community:${name}`);
}

function flairId(name) {
  return deterministicUuid(`${fixtureNamespace}:flair:${name}`);
}

function mediaId(characterId) {
  return deterministicUuid(`${fixtureNamespace}:media:${characterId}`);
}

function postId(fixtureId) {
  return deterministicUuid(`${fixtureNamespace}:post:${fixtureId}`);
}

function commentId(fixtureId, ref) {
  return deterministicUuid(`${fixtureNamespace}:comment:${fixtureId}:${ref}`);
}

function assertNoIdentityCollisions() {
  const expectedUsers = new Map(catalog.characters.map((character) => [character.username, userId(character.username)]));
  const expectedUserIds = new Map([...expectedUsers].map(([username, id]) => [id, username]));
  const userRows = runSql(
    `SELECT id, username FROM users WHERE username IN (${[...expectedUsers.keys()].map(sqlString).join(",")})`
    + ` OR id IN (${[...expectedUserIds.keys()].map(sqlString).join(",")})`,
  )[0]?.results ?? [];
  for (const row of userRows) {
    if (expectedUsers.get(row.username) !== row.id || expectedUserIds.get(row.id) !== row.username) {
      throw new Error(`Fixture user identity collision: ${JSON.stringify(row)}`);
    }
  }

  const expectedCommunities = new Map(catalog.communities.map((community) => [community.name, communityId(community.name)]));
  const expectedCommunityIds = new Map([...expectedCommunities].map(([name, id]) => [id, name]));
  const communityRows = runSql(
    `SELECT id, name FROM subreddits WHERE name IN (${[...expectedCommunities.keys()].map(sqlString).join(",")})`
    + ` OR id IN (${[...expectedCommunityIds.keys()].map(sqlString).join(",")})`,
  )[0]?.results ?? [];
  for (const row of communityRows) {
    if (expectedCommunities.get(row.name) !== row.id || expectedCommunityIds.get(row.id) !== row.name) {
      throw new Error(`Fixture community identity collision: ${JSON.stringify(row)}`);
    }
  }
}

function seedUsers() {
  const sql = catalog.characters.map((character, index) => {
    const timestamp = baseTimestamp - (catalog.characters.length - index) * 60_000;
    return `
      INSERT INTO users (
        id, username, display_name, bio, avatar_url, password_hash, password_salt,
        password_iterations, created_at, updated_at
      ) VALUES (
        ${sqlString(userId(character.username))}, ${sqlString(character.username)},
        ${sqlString(character.displayName)}, ${sqlString(character.bio)}, ${sqlString(character.api.image)},
        'bm90LWEtbG9naW4tYWNjb3VudA', 'cmljay1tb3J0eS1kZW1v', 100000,
        ${timestamp}, ${timestamp}
      )
      ON CONFLICT(id) DO UPDATE SET
        display_name = excluded.display_name,
        bio = excluded.bio,
        avatar_url = excluded.avatar_url,
        created_at = excluded.created_at,
        updated_at = excluded.updated_at;
    `;
  }).join("\n");
  runSql(sql);
}

function seedCommunities() {
  const characterById = new Map(catalog.characters.map((character) => [character.api.id, character]));
  const statements = [];
  for (const [index, community] of catalog.communities.entries()) {
    const timestamp = baseTimestamp + index * 10_000;
    const avatarUrl = characterById.get(community.avatarCharacterId).api.image;
    statements.push(`
      INSERT INTO subreddits (
        id, name, display_name, description, access_type, created_by,
        avatar_url, client_mutation_id, created_at, updated_at
      ) VALUES (
        ${sqlString(communityId(community.name))}, ${sqlString(community.name)},
        ${sqlString(community.displayName)}, ${sqlString(community.description)}, 'public',
        ${sqlString(userId(community.owner))}, ${sqlString(avatarUrl)},
        ${sqlString(`${fixtureNamespace}:community:${community.name}`)}, ${timestamp}, ${timestamp}
      )
      ON CONFLICT(id) DO UPDATE SET
        display_name = excluded.display_name,
        description = excluded.description,
        avatar_url = excluded.avatar_url,
        created_at = excluded.created_at,
        updated_at = excluded.updated_at;

      INSERT OR IGNORE INTO subreddit_members (subreddit_id, user_id, role, created_at, updated_at)
      VALUES (${sqlString(communityId(community.name))}, ${sqlString(userId(community.owner))}, 'owner', ${timestamp}, ${timestamp});

      INSERT OR IGNORE INTO post_flairs (
        id, subreddit_id, text, background_color, text_color, sort_order, enabled, created_at, updated_at
      ) VALUES (
        ${sqlString(flairId(community.name))}, ${sqlString(communityId(community.name))},
        ${sqlString(community.name === "dimension_c137" ? "Family Update" : "Episode Discussion")},
        '#39FF14', '#0B1416', 0, 1, ${timestamp}, ${timestamp}
      );
    `);
    for (const member of unique(community.members).filter((username) => username !== community.owner)) {
      statements.push(`
        INSERT OR IGNORE INTO subreddit_members (subreddit_id, user_id, role, created_at, updated_at)
        VALUES (${sqlString(communityId(community.name))}, ${sqlString(userId(member))}, 'subscriber', ${timestamp}, ${timestamp});
      `);
    }
  }
  runSql(statements.join("\n"));
}

async function seedMedia(sources) {
  const characterById = new Map(catalog.characters.map((character) => [character.api.id, character]));
  const tempDirectory = await mkdtemp(join(tmpdir(), "readthat-rick-morty-"));
  try {
    for (const media of catalog.media) {
      const character = characterById.get(media.characterId);
      const path = join(tempDirectory, `${media.characterId}.jpeg`);
      const key = `demo/rick-and-morty/v${fixtureVersion}/${media.characterId}.jpeg`;
      await writeFile(path, sources.get(media.characterId));
      console.error(`Uploading media for u/${character.username}`);
      uploadR2(path, key, media.contentType);
    }
  } finally {
    await rm(tempDirectory, { recursive: true, force: true });
  }

  const sql = catalog.media.map((media, index) => {
    const character = characterById.get(media.characterId);
    const id = mediaId(media.characterId);
    const key = `demo/rick-and-morty/v${fixtureVersion}/${media.characterId}.jpeg`;
    const timestamp = baseTimestamp + index * 1_000;
    const altText = `${character.displayName} portrait from The Rick and Morty API character ${media.characterId}.`;
    return `
      INSERT INTO media (
        id, uploader_id, kind, content_type, byte_size, r2_key, status,
        upload_mode, r2_upload_id, upload_token_hash, upload_expires_at,
        etag, width, height, duration_seconds, alt_text, created_at, completed_at,
        delivery_provider, stream_status, image_status, source_deleted_at
      ) VALUES (
        ${sqlString(id)}, ${sqlString(userId(character.username))}, 'image', ${sqlString(media.contentType)},
        ${media.byteSize}, ${sqlString(key)}, 'ready', 'single', NULL, 'c2VlZGVkLWRlbW8tbWVkaWE',
        ${timestamp}, ${sqlString(media.sha256)}, ${media.width}, ${media.height}, NULL,
        ${sqlString(altText)}, ${timestamp}, ${timestamp}, 'r2', 'not_applicable', 'not_applicable', NULL
      )
      ON CONFLICT(id) DO UPDATE SET
        content_type = excluded.content_type,
        byte_size = excluded.byte_size,
        r2_key = excluded.r2_key,
        status = 'ready',
        etag = excluded.etag,
        width = excluded.width,
        height = excluded.height,
        alt_text = excluded.alt_text,
        created_at = excluded.created_at,
        completed_at = excluded.completed_at,
        delivery_provider = 'r2',
        image_uid = NULL,
        image_status = 'not_applicable',
        image_error_message = NULL,
        source_deleted_at = NULL;

      UPDATE users
      SET avatar_media_id = ${sqlString(id)}, updated_at = ${timestamp}
      WHERE id = ${sqlString(userId(character.username))};
    `;
  }).join("\n");
  runSql(sql);
}

function seedMediaTimestamps() {
  const sql = catalog.media.map((media, index) => {
    const timestamp = baseTimestamp + index * 1_000;
    return `
      UPDATE media
      SET created_at = ${timestamp}, completed_at = ${timestamp}
      WHERE id = ${sqlString(mediaId(media.characterId))};
    `;
  }).join("\n");
  runSql(sql);
}

function seedPostsAndComments() {
  for (const post of catalog.posts) {
    const id = postId(post.fixtureId);
    const timestamp = postTimestamp(post);
    const postMediaId = post.kind === "image" ? mediaId(post.mediaCharacterId) : null;
    const statements = [`
      INSERT INTO posts (
        id, subreddit_id, author_id, kind, title, body, url, media_id, flair_id,
        crosspost_parent_id, client_mutation_id, created_at, updated_at
      ) VALUES (
        ${sqlString(id)}, ${sqlString(communityId(post.subreddit))}, ${sqlString(userId(post.author))},
        ${sqlString(post.kind)}, ${sqlString(post.title)}, ${sqlString(post.body)}, NULL,
        ${sqlString(postMediaId)}, ${sqlString(flairId(post.subreddit))}, NULL,
        ${sqlString(`${fixtureNamespace}:post:${post.fixtureId}`)}, ${timestamp}, ${timestamp}
      )
      ON CONFLICT(id) DO UPDATE SET
        subreddit_id = excluded.subreddit_id,
        author_id = excluded.author_id,
        kind = excluded.kind,
        title = excluded.title,
        body = excluded.body,
        url = excluded.url,
        media_id = excluded.media_id,
        flair_id = excluded.flair_id,
        created_at = excluded.created_at,
        updated_at = excluded.updated_at;
    `];
    if (postMediaId) {
      statements.push(`
        INSERT OR IGNORE INTO post_media (post_id, media_id, position)
        VALUES (${sqlString(id)}, ${sqlString(postMediaId)}, 0);
      `);
    }
    const voters = postVoters(post);
    for (const [voterIndex, voter] of voters.entries()) {
      const value = voterIndex < post.seedUpvotes ? 1 : 0;
      statements.push(`
        INSERT INTO votes (user_id, target_type, target_id, value, version, last_mutation_id, updated_at)
        VALUES (
          ${sqlString(userId(voter))}, 'post', ${sqlString(id)}, ${value}, 1,
          ${sqlString(`${fixtureNamespace}:vote:post:${post.fixtureId}:${voter}`)}, ${timestamp}
        )
        ON CONFLICT(user_id, target_type, target_id) DO UPDATE SET
          value = excluded.value,
          version = CASE WHEN votes.value <> excluded.value THEN votes.version + 1 ELSE votes.version END,
          last_mutation_id = excluded.last_mutation_id,
          updated_at = excluded.updated_at;
      `);
    }

    const commentByRef = new Map();
    for (const [commentIndex, sourceComment] of post.comments.entries()) {
      const parent = sourceComment.parentRef ? commentByRef.get(sourceComment.parentRef) : null;
      const comment = {
        ...sourceComment,
        id: commentId(post.fixtureId, sourceComment.ref),
        depth: parent ? parent.depth + 1 : 0,
      };
      commentByRef.set(comment.ref, comment);
      const commentTimestamp = timestamp + (commentIndex + 1) * 1_000;
      statements.push(`
        INSERT INTO comments (
          id, post_id, parent_id, author_id, body, depth,
          client_mutation_id, created_at, updated_at
        ) VALUES (
          ${sqlString(comment.id)}, ${sqlString(id)}, ${sqlString(parent?.id ?? null)},
          ${sqlString(userId(comment.author))}, ${sqlString(comment.body)}, ${comment.depth},
          ${sqlString(`${fixtureNamespace}:comment:${post.fixtureId}:${comment.ref}`)},
          ${commentTimestamp}, ${commentTimestamp}
        )
        ON CONFLICT(id) DO UPDATE SET
          parent_id = excluded.parent_id,
          author_id = excluded.author_id,
          body = excluded.body,
          depth = excluded.depth,
          created_at = excluded.created_at,
          updated_at = excluded.updated_at;
      `);
      const commentVoters = unique([comment.author, post.author, parent?.author].filter(Boolean));
      for (const voter of commentVoters) {
        statements.push(`
          INSERT OR IGNORE INTO votes (user_id, target_type, target_id, value, version, last_mutation_id, updated_at)
          VALUES (
            ${sqlString(userId(voter))}, 'comment', ${sqlString(comment.id)}, 1, 1,
            ${sqlString(`${fixtureNamespace}:vote:comment:${post.fixtureId}:${comment.ref}:${voter}`)},
            ${commentTimestamp}
          );
        `);
      }
    }
    statements.push(`
      UPDATE posts
      SET created_at = ${timestamp},
          updated_at = ${timestamp + post.comments.length * 1_000},
          rank_value = score * 1000000000 + ${timestamp}
      WHERE id = ${sqlString(id)};
    `);
    runSql(statements.join("\n"));
  }
}

function verifyStoredRows() {
  const expected = {
    users: catalog.characters.length,
    communities: catalog.communities.length,
    posts: catalog.posts.length,
    comments: catalog.posts.reduce((sum, post) => sum + post.comments.length, 0),
    media: catalog.media.length,
  };
  const result = runSql(`
    SELECT
      (SELECT COUNT(*) FROM users WHERE id IN (${catalog.characters.map((character) => sqlString(userId(character.username))).join(",")})) AS users,
      (SELECT COUNT(*) FROM subreddits WHERE id IN (${catalog.communities.map((community) => sqlString(communityId(community.name))).join(",")})) AS communities,
      (SELECT COUNT(*) FROM posts WHERE id IN (${catalog.posts.map((post) => sqlString(postId(post.fixtureId))).join(",")})) AS posts,
      (SELECT COUNT(*) FROM comments WHERE id IN (${catalog.posts.flatMap((post) => post.comments.map((comment) => sqlString(commentId(post.fixtureId, comment.ref)))).join(",")})) AS comments,
      (SELECT COUNT(*) FROM media WHERE id IN (${catalog.media.map((media) => sqlString(mediaId(media.characterId))).join(",")})) AS media;
  `)[0]?.results?.[0];
  for (const [key, count] of Object.entries(expected)) {
    if (Number(result?.[key]) !== count) throw new Error(`Stored ${key}: expected ${count}, found ${result?.[key]}`);
  }
  return expected;
}

function flattenComments(nodes, output = []) {
  for (const node of nodes ?? []) {
    if (node.type !== "comment") continue;
    output.push(node);
    flattenComments(node.children, output);
  }
  return output;
}

async function verifyLiveApi() {
  const mediaByCharacterId = new Map(catalog.media.map((media) => [media.characterId, media]));
  for (const character of catalog.characters) {
    const payload = await fetchJson(`${baseUrl}/v1/users/${encodeURIComponent(character.username)}`);
    if (
      payload.user?.displayName !== character.displayName
      || payload.user?.bio !== character.bio
      || !payload.user?.avatarUrl?.startsWith(
        `${baseUrl}/v1/users/${encodeURIComponent(character.username)}/avatar?v=`,
      )
    ) {
      throw new Error(`${character.username}: live profile verification failed`);
    }
    const avatarResponse = await fetch(payload.user.avatarUrl);
    if (!avatarResponse.ok || !avatarResponse.headers.get("content-type")?.startsWith("image/")) {
      throw new Error(`${character.username}: live avatar delivery failed (${avatarResponse.status})`);
    }
    const avatarBytes = Buffer.from(await avatarResponse.arrayBuffer());
    const avatarDigest = createHash("sha256").update(avatarBytes).digest("hex");
    if (avatarDigest !== mediaByCharacterId.get(character.api.id)?.sha256) {
      throw new Error(`${character.username}: live avatar digest mismatch`);
    }
  }

  for (const community of catalog.communities) {
    const payload = await fetchJson(`${baseUrl}/v1/subreddits/${encodeURIComponent(community.name)}`);
    if (
      payload.subreddit?.displayName !== community.displayName
      || payload.subreddit?.description !== community.description
    ) {
      throw new Error(`${community.name}: live community verification failed`);
    }
    const feed = await fetchJson(`${baseUrl}/v1/feed?subreddit=${encodeURIComponent(community.name)}&limit=20`);
    const expectedPostIds = catalog.posts
      .filter((post) => post.subreddit === community.name)
      .map((post) => postId(post.fixtureId));
    const livePostIds = new Set(feed.groups?.map((group) => group.groupId));
    for (const id of expectedPostIds) {
      if (!livePostIds.has(id)) throw new Error(`${community.name}: feed is missing post ${id}`);
    }
  }

  const verifiedPosts = [];
  for (const post of catalog.posts) {
    const id = postId(post.fixtureId);
    const [postPayload, commentsPayload] = await Promise.all([
      fetchJson(`${baseUrl}/v1/posts/${id}`),
      fetchJson(`${baseUrl}/v1/posts/${id}/comments?count=200&depth=10`),
    ]);
    const livePost = postPayload.post;
    if (
      livePost?.author !== post.author
      || livePost?.subreddit !== post.subreddit
      || livePost?.title !== post.title
      || livePost?.body !== post.body
      || livePost?.kind !== post.kind
      || livePost?.createdAt !== postTimestamp(post)
      || livePost?.score !== post.seedUpvotes
    ) {
      throw new Error(`${post.fixtureId}: live post verification failed`);
    }
    const liveComments = flattenComments(commentsPayload.roots);
    if (liveComments.length < post.comments.length) {
      throw new Error(`${post.fixtureId}: expected at least ${post.comments.length} comments, found ${liveComments.length}`);
    }
    for (const comment of post.comments) {
      const expectedId = commentId(post.fixtureId, comment.ref);
      const liveComment = liveComments.find((candidate) => candidate.id === expectedId);
      if (liveComment?.body !== comment.body || liveComment?.author !== `u/${comment.author}`) {
        throw new Error(`${post.fixtureId}/${comment.ref}: live comment verification failed`);
      }
    }
    if (post.kind === "image") {
      const expectedMedia = catalog.media.find((media) => media.characterId === post.mediaCharacterId);
      const response = await fetch(livePost.media?.url);
      if (!response.ok || !response.headers.get("content-type")?.startsWith("image/")) {
        throw new Error(`${post.fixtureId}: live image delivery failed (${response.status})`);
      }
      const bytes = Buffer.from(await response.arrayBuffer());
      const digest = createHash("sha256").update(bytes).digest("hex");
      if (digest !== expectedMedia.sha256) throw new Error(`${post.fixtureId}: live image digest mismatch`);
    }
    verifiedPosts.push({ post, livePost });
  }
  return verifiedPosts;
}

function feedSchedule() {
  return catalog.posts
    .map((post) => ({
      fixtureId: post.fixtureId,
      title: post.title,
      publishedAt: post.publishedAt,
      seedUpvotes: post.seedUpvotes,
      expectedRankValue: expectedPostRank(post),
    }))
    .sort((left, right) => right.expectedRankValue - left.expectedRankValue);
}

validateCatalog();
if (rebalanceFeedOnly && planOnly) {
  console.log(JSON.stringify({
    ok: true,
    planOnly: true,
    rebalanceFeedOnly: true,
    fixtureVersion,
    feedBalanceRevision: catalog.collection.feedBalanceRevision,
    feedSchedule: feedSchedule(),
  }, null, 2));
  process.exit(0);
}

if (rebalanceFeedOnly) {
  console.error(`Checking fixture identity collisions in ${local ? "local" : "remote"} D1 ${database}`);
  assertNoIdentityCollisions();
  console.error("Backdating fixture identities, communities, media metadata, posts, and comments");
  seedUsers();
  seedCommunities();
  seedMediaTimestamps();
  seedPostsAndComments();
  const counts = verifyStoredRows();
  console.error(`Verifying rebalanced posts and scores through ${baseUrl}`);
  const verifiedPosts = await verifyLiveApi();
  console.log(JSON.stringify({
    ok: true,
    planOnly: false,
    rebalanceFeedOnly: true,
    fixtureVersion,
    feedBalanceRevision: catalog.collection.feedBalanceRevision,
    api: baseUrl,
    database,
    counts,
    feedSchedule: feedSchedule(),
    verifiedPosts: verifiedPosts.map(({ post, livePost }) => ({
      fixtureId: post.fixtureId,
      id: livePost.id,
      score: livePost.score,
      createdAt: livePost.createdAt,
      rankValue: expectedPostRank(post),
    })),
  }, null, 2));
  process.exit(0);
}

console.error("Validating Rick and Morty API records and portrait bytes");
await validateRickAndMortyApi();
const mediaSources = await downloadAndValidateMedia();

if (planOnly) {
  console.log(JSON.stringify({
    ok: true,
    planOnly: true,
    fixtureVersion,
    source: catalog.collection.characterApi,
    rankedListSource: catalog.collection.rankedListSource,
    characters: catalog.characters.length,
    principalCharacters: catalog.characters.filter((character) => character.profileRole === "principal").length,
    recurringAndEpisodeCharacters: catalog.characters.filter((character) => character.profileRole !== "principal").length,
    episodes: catalog.episodes.map(({ rank, id, name, code, community }) => ({ rank, id, name, code, community })),
    communities: catalog.communities.length,
    posts: catalog.posts.length,
    comments: catalog.posts.reduce((sum, post) => sum + post.comments.length, 0),
    avatarMedia: catalog.media.length,
    feedBalanceRevision: catalog.collection.feedBalanceRevision,
    feedSchedule: feedSchedule(),
  }, null, 2));
  process.exit(0);
}

console.error(`Checking fixture identity collisions in ${local ? "local" : "remote"} D1 ${database}`);
assertNoIdentityCollisions();
console.error(`Seeding ${catalog.characters.length} profiles and ${catalog.communities.length} communities`);
seedUsers();
seedCommunities();
console.error(`Uploading and indexing ${catalog.media.length} character portrait media objects`);
await seedMedia(mediaSources);
console.error(`Seeding ${catalog.posts.length} posts and ${catalog.posts.reduce((sum, post) => sum + post.comments.length, 0)} comments`);
seedPostsAndComments();
const counts = verifyStoredRows();
console.error(`Verifying profiles, community feeds, posts, comment trees, and media through ${baseUrl}`);
const verifiedPosts = await verifyLiveApi();

console.log(JSON.stringify({
  ok: true,
  planOnly: false,
  fixtureVersion,
  feedBalanceRevision: catalog.collection.feedBalanceRevision,
  api: baseUrl,
  database,
  r2Bucket,
  fictionalProfiles: catalog.collection.fictionalProfiles,
  profileNotice: catalog.collection.profileNotice,
  source: catalog.collection.characterApi,
  rankedListSource: catalog.collection.rankedListSource,
  rankedListMethodology: catalog.collection.rankedListMethodology,
  counts,
  characters: catalog.characters.map((character) => ({
    userId: userId(character.username),
    username: character.username,
    displayName: character.displayName,
    role: character.profileRole,
    characterId: character.api.id,
    profileUrl: `${baseUrl}/v1/users/${encodeURIComponent(character.username)}`,
    avatarSourceUrl: character.api.image,
  })),
  episodes: catalog.episodes.map((episode) => ({
    rank: episode.rank,
    id: episode.id,
    name: episode.name,
    code: episode.code,
    airDate: episode.airDate,
    apiUrl: episode.apiUrl,
    community: episode.community,
    communityUrl: `${baseUrl}/r/${encodeURIComponent(episode.community)}`,
  })),
  communities: catalog.communities.map((community) => ({
    id: communityId(community.name),
    name: community.name,
    displayName: community.displayName,
    webUrl: `${baseUrl}/r/${encodeURIComponent(community.name)}`,
    feedUrl: `${baseUrl}/v1/feed?subreddit=${encodeURIComponent(community.name)}&limit=20`,
  })),
  posts: verifiedPosts.map(({ post, livePost }) => ({
    fixtureId: post.fixtureId,
    id: livePost.id,
    kind: livePost.kind,
    title: livePost.title,
    author: livePost.author,
    subreddit: livePost.subreddit,
    commentCount: livePost.commentCount,
    score: livePost.score,
    mediaId: livePost.media?.id ?? null,
    apiUrl: `${baseUrl}/v1/posts/${livePost.id}`,
    webUrl: `${baseUrl}/post/${livePost.id}`,
  })),
}, null, 2));
