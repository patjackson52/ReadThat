import { AppError } from "./http";
import type { RouteHandler, RouteParams } from "./types";

interface Route {
  method: string;
  pattern: RegExp;
  parameterNames: string[];
  handler: RouteHandler;
}

function compilePath(path: string): Pick<Route, "pattern" | "parameterNames"> {
  const parameterNames: string[] = [];
  const segments = path.split("/").map((segment) => {
    if (!segment.startsWith(":")) return segment.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
    parameterNames.push(segment.slice(1));
    return "([^/]+)";
  });
  return { pattern: new RegExp(`^${segments.join("/")}$`, "u"), parameterNames };
}

export class Router {
  private readonly routes: Route[] = [];

  on(method: string, path: string, handler: RouteHandler): this {
    this.routes.push({ method, handler, ...compilePath(path) });
    return this;
  }

  match(method: string, pathname: string): { handler: RouteHandler; params: RouteParams } {
    for (const route of this.routes) {
      if (route.method !== method) continue;
      const match = route.pattern.exec(pathname);
      if (!match) continue;
      const params: Record<string, string> = {};
      route.parameterNames.forEach((name, index) => {
        const encoded = match[index + 1];
        if (encoded === undefined) return;
        try {
          params[name] = decodeURIComponent(encoded);
        } catch {
          throw new AppError(400, "invalid_path", "Path contains invalid percent encoding");
        }
      });
      return { handler: route.handler, params };
    }
    throw new AppError(404, "not_found", "Route not found");
  }
}
