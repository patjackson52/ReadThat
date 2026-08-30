import type { ZodType } from "zod";

export class AppError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: unknown,
  ) {
    super(message);
    this.name = "AppError";
  }
}

export function jsonResponse(
  body: unknown,
  init: ResponseInit = {},
): Response {
  const headers = new Headers(init.headers);
  headers.set("content-type", "application/json; charset=utf-8");
  headers.set("x-content-type-options", "nosniff");
  if (!headers.has("cache-control")) headers.set("cache-control", "private, no-store");
  return new Response(JSON.stringify(body), { ...init, headers });
}

export function emptyResponse(status = 204, headers?: HeadersInit): Response {
  return new Response(null, { status, headers });
}

export async function readBoundedBody(
  request: Request,
  limitBytes: number,
  label = "Request body",
): Promise<string> {
  const declaredLength = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(declaredLength) && declaredLength > limitBytes) {
    throw new AppError(413, "payload_too_large", `${label} exceeds ${limitBytes} bytes`);
  }
  if (!request.body) throw new AppError(400, "missing_body", `${label} is required`);

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    length += value.byteLength;
    if (length > limitBytes) {
      await reader.cancel("payload too large");
      throw new AppError(413, "payload_too_large", `${label} exceeds ${limitBytes} bytes`);
    }
    chunks.push(value);
  }

  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

export async function readJson<T>(
  request: Request,
  schema: ZodType<T>,
  limitBytes = 64 * 1024,
): Promise<T> {
  const contentType = request.headers.get("content-type")?.split(";", 1)[0]?.trim();
  if (contentType !== "application/json") {
    throw new AppError(415, "unsupported_media_type", "Content-Type must be application/json");
  }
  let value: unknown;
  try {
    value = JSON.parse(await readBoundedBody(request, limitBytes, "JSON body"));
  } catch (error) {
    if (error instanceof AppError) throw error;
    throw new AppError(400, "invalid_json", "Request body is not valid JSON");
  }
  const parsed = schema.safeParse(value);
  if (!parsed.success) {
    throw new AppError(422, "validation_failed", "Request validation failed", parsed.error.issues);
  }
  return parsed.data;
}

export function errorResponse(error: unknown, requestId: string): Response {
  const headers = new Headers({
    "x-request-id": requestId,
    "referrer-policy": "no-referrer",
    "permissions-policy": "camera=(), microphone=(), geolocation=()",
  });
  if (error instanceof AppError) {
    if (error.status === 429 && typeof error.details === "object" && error.details !== null) {
      const retryAfterMs = Reflect.get(error.details, "retryAfterMs");
      if (typeof retryAfterMs === "number" && Number.isFinite(retryAfterMs)) {
        headers.set("retry-after", String(Math.max(1, Math.ceil(retryAfterMs / 1_000))));
      }
    }
    return jsonResponse(
      {
        error: {
          code: error.code,
          message: error.message,
          details: error.details ?? null,
          requestId,
        },
      },
      { status: error.status, headers },
    );
  }
  console.error(JSON.stringify({
    level: "error",
    message: "unhandled request error",
    requestId,
    error: error instanceof Error ? error.message : String(error),
    stack: error instanceof Error ? error.stack : undefined,
  }));
  return jsonResponse(
    { error: { code: "internal_error", message: "Internal server error", requestId } },
    { status: 500, headers },
  );
}

export function isUniqueConstraint(error: unknown): boolean {
  return error instanceof Error && error.message.includes("UNIQUE constraint failed");
}

export function clientIp(request: Request): string {
  return request.headers.get("cf-connecting-ip") ?? "local-development";
}

export function applyCors(request: Request, response: Response, allowedOrigins: string): Response {
  const origin = request.headers.get("origin");
  if (!origin) return response;
  const allowed = new Set(allowedOrigins.split(",").map((item) => item.trim()).filter(Boolean));
  if (!allowed.has(origin)) return response;

  const headers = new Headers(response.headers);
  headers.set("access-control-allow-origin", origin);
  headers.set("access-control-allow-credentials", "true");
  headers.set("access-control-expose-headers", "etag,x-d1-bookmark,x-request-id,retry-after,server-timing");
  headers.append("vary", "Origin");
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

export function preflightResponse(request: Request, allowedOrigins: string): Response {
  const origin = request.headers.get("origin");
  const allowed = new Set(allowedOrigins.split(",").map((item) => item.trim()).filter(Boolean));
  if (!origin || !allowed.has(origin)) {
    throw new AppError(403, "origin_not_allowed", "Origin is not allowed");
  }
  return emptyResponse(204, {
    "access-control-allow-origin": origin,
    "access-control-allow-credentials": "true",
    "access-control-allow-methods": "GET,HEAD,POST,PUT,PATCH,DELETE,OPTIONS",
    "access-control-allow-headers": "authorization,content-type,if-none-match,range,x-d1-bookmark,x-upload-token",
    "access-control-max-age": "86400",
    vary: "Origin",
  });
}
