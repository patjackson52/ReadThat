const encoder = new TextEncoder();
const decoder = new TextDecoder();

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

export function base64UrlEncode(value: Uint8Array | string): string {
  const bytes = typeof value === "string" ? encoder.encode(value) : value;
  return bytesToBase64(bytes)
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/u, "");
}

export function base64UrlDecode(value: string): Uint8Array {
  const normalized = value.replaceAll("-", "+").replaceAll("_", "/");
  const padding = "=".repeat((4 - (normalized.length % 4)) % 4);
  return base64ToBytes(normalized + padding);
}

export function randomToken(byteCount = 32): string {
  const bytes = new Uint8Array(byteCount);
  crypto.getRandomValues(bytes);
  return base64UrlEncode(bytes);
}

export async function sha256(value: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
}

export async function hmac(secret: string, value: string): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(value)));
}

export async function keyedHash(secret: string, value: string): Promise<string> {
  return base64UrlEncode(await hmac(secret, value));
}

export async function signOpaquePayload<T>(secret: string, value: T): Promise<string> {
  const payload = base64UrlEncode(JSON.stringify(value));
  const signature = base64UrlEncode(await hmac(secret, payload));
  return `${payload}.${signature}`;
}

export async function verifyOpaquePayload<T>(secret: string, token: string): Promise<T | null> {
  const separator = token.lastIndexOf(".");
  if (separator <= 0) return null;
  const payload = token.slice(0, separator);
  const provided = token.slice(separator + 1);

  try {
    const expectedBytes = await hmac(secret, payload);
    const providedBytes = base64UrlDecode(provided);
    const fixedProvided = await sha256(base64UrlEncode(providedBytes));
    const fixedExpected = await sha256(base64UrlEncode(expectedBytes));
    if (!crypto.subtle.timingSafeEqual(fixedProvided, fixedExpected)) return null;
    return JSON.parse(decoder.decode(base64UrlDecode(payload))) as T;
  } catch {
    return null;
  }
}

export interface PasswordDigest {
  hash: string;
  salt: string;
  iterations: number;
}

export async function hashPassword(
  password: string,
  pepper: string,
  salt = randomToken(16),
  // Cloudflare Workers' WebCrypto runtime currently caps PBKDF2 at 100k.
  // The per-deployment pepper is a separate secret defense if D1 is leaked.
  iterations = 100_000,
): Promise<PasswordDigest> {
  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    encoder.encode(`${pepper}\u0000${password}`),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const bits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      hash: "SHA-256",
      salt: base64UrlDecode(salt),
      iterations,
    },
    keyMaterial,
    256,
  );
  return { hash: base64UrlEncode(new Uint8Array(bits)), salt, iterations };
}

export async function verifyPassword(
  password: string,
  pepper: string,
  expected: PasswordDigest,
): Promise<boolean> {
  const actual = await hashPassword(password, pepper, expected.salt, expected.iterations);
  const actualFixed = await sha256(actual.hash);
  const expectedFixed = await sha256(expected.hash);
  return crypto.subtle.timingSafeEqual(actualFixed, expectedFixed);
}

export async function secureTextEqual(left: string, right: string): Promise<boolean> {
  const [leftHash, rightHash] = await Promise.all([sha256(left), sha256(right)]);
  return crypto.subtle.timingSafeEqual(leftHash, rightHash);
}
