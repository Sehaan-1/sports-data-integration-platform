#!/usr/bin/env node
/** Development only. Production continues to serve these exact files from the Spring Boot jar.
 * No fake API is exposed: an explicit ?demo=1 enables the in-browser sample-data adapter.
 * Set SDIP_API_URL to proxy the real backend on the same browser origin.
 */
import { createServer, request as httpRequest } from "node:http";
import { request as httpsRequest } from "node:https";
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const staticRoot = path.resolve(
  fileURLToPath(
    new URL("../sdip-api/src/main/resources/static/", import.meta.url),
  ),
);
const types = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".svg": "image/svg+xml",
  ".woff2": "font/woff2",
  ".txt": "text/plain; charset=utf-8",
  ".json": "application/json",
};

export function createPreviewServer({
  apiUrl = process.env.SDIP_API_URL || "",
} = {}) {
  const upstream = apiUrl ? new URL(apiUrl) : null;
  if (upstream && !["http:", "https:"].includes(upstream.protocol))
    throw new Error("SDIP_API_URL must use http or https.");
  return createServer(async (req, res) => {
    const send = (status, text, contentType = "text/plain; charset=utf-8") => {
      res.writeHead(status, {
        "Content-Type": contentType,
        "Cache-Control": "no-store",
      });
      res.end(text);
    };
    let url;
    try {
      url = new URL(req.url, "http://preview.internal");
    } catch {
      send(400, "Invalid request URL");
      return;
    }
    if (
      url.pathname.startsWith("/api/") ||
      url.pathname === "/swagger-ui.html" ||
      url.pathname.startsWith("/swagger-ui/") ||
      url.pathname.startsWith("/v3/api-docs")
    ) {
      if (!upstream) {
        send(
          503,
          JSON.stringify({
            code: "SDIP_PREVIEW_NO_BACKEND",
            message:
              "The Java backend is not running in this preview. Explore the demo workspace instead.",
          }),
          "application/json",
        );
        return;
      }
      const headers = { ...req.headers, host: upstream.host };
      delete headers.connection;
      const target = new URL(url.pathname + url.search, upstream);
      const proxy = (
        upstream.protocol === "https:" ? httpsRequest : httpRequest
      )(
        target,
        { method: req.method, headers, signal: AbortSignal.timeout(120_000) },
        (response) => {
          res.writeHead(response.statusCode || 502, response.headers);
          response.pipe(res);
        },
      );
      proxy.on("error", () => {
        if (!res.headersSent)
          send(
            502,
            JSON.stringify({
              message:
                "The SDIP backend could not be reached. Check SDIP_API_URL and start the backend.",
            }),
            "application/json",
          );
        else res.destroy();
      });
      req.on("aborted", () => proxy.destroy());
      req.pipe(proxy);
      return;
    }
    if (!["GET", "HEAD"].includes(req.method)) {
      send(405, "Method not allowed");
      return;
    }
    if (url.pathname === "/") {
      res.writeHead(302, {
        Location: `/dashboard.html${upstream ? "" : "?demo=1"}`,
        "Cache-Control": "no-store",
      });
      res.end();
      return;
    }
    if (url.pathname === "/preview-health") {
      send(
        200,
        JSON.stringify({ status: "UP", mode: upstream ? "api-proxy" : "demo" }),
        "application/json",
      );
      return;
    }
    let file;
    try {
      file = path.resolve(staticRoot, "." + decodeURIComponent(url.pathname));
    } catch {
      send(400, "Invalid file path");
      return;
    }
    if (!file.startsWith(staticRoot + path.sep) || file.includes("\0")) {
      send(403, "Access denied");
      return;
    }
    try {
      const info = await stat(file);
      if (!info.isFile()) {
        send(404, "Not found");
        return;
      }
      res.writeHead(200, {
        "Content-Type": types[path.extname(file)] || "application/octet-stream",
        "Content-Length": info.size,
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
      });
      if (req.method === "HEAD") res.end();
      else createReadStream(file).pipe(res);
    } catch {
      send(404, "Not found");
    }
  });
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const port = Number(process.env.PORT || 3000);
  const server = createPreviewServer();
  server.listen(port, "0.0.0.0", () => {
    console.log(`SDIP workspace preview is ready on port ${port}.`);
    console.log(
      process.env.SDIP_API_URL
        ? "API requests use the configured same-origin backend proxy."
        : "Opening the isolated sample-data workspace. Real sign-in requires the Java backend.",
    );
  });
  for (const signal of ["SIGINT", "SIGTERM"])
    process.on(signal, () => server.close(() => process.exit(0)));
}
