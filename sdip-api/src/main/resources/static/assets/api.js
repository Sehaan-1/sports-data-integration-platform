export class ApiError extends Error {
  constructor(message, status = 0) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

function browserStorage() {
  try {
    return globalThis.localStorage;
  } catch {
    return null;
  }
}

export function createApiClient({
  onUnauthorized = () => {},
  fetchImpl = (...args) => fetch(...args),
  storage = browserStorage(),
} = {}) {
  const read = (key) => {
    try {
      return storage?.getItem(key) || "";
    } catch {
      return "";
    }
  };
  let access = read("sdip.at");
  let refreshToken = read("sdip.rt");
  let refreshing = null;
  let expired = false; const apiBase = (read("sdip.api") || (new URLSearchParams(location.search).get("api") || "")).replace(/\/+$/, "");
  const remember = (key, value) => {
    try {
      value ? storage?.setItem(key, value) : storage?.removeItem(key);
    } catch {
      /* Session still works without persistent storage. */
    }
  };
  const clearTokens = () => {
    access = refreshToken = "";
    remember("sdip.at", "");
    remember("sdip.rt", "");
  };
  const setTokens = (pair) => {
    if (!pair?.accessToken || !pair?.refreshToken)
      throw new ApiError(
        "The server returned an incomplete session. Please sign in again.",
      );
    access = pair.accessToken;
    refreshToken = pair.refreshToken;
    expired = false;
    remember("sdip.at", access);
    remember("sdip.rt", refreshToken);
  };
  const refresh = () => {
    if (!refreshToken) return Promise.resolve(false);
    if (!refreshing)
      refreshing = (async () => {
        try {
          const response = await fetchImpl((apiBase || "") + "/api/auth/refresh", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ refreshToken }),
            signal: AbortSignal.timeout(30_000),
          });
          if (!response.ok) return false;
          setTokens(await response.json());
          return true;
        } catch {
          return false;
        }
      })().finally(() => {
        refreshing = null;
      });
    return refreshing;
  };

  async function send(path, options = {}, retry = false) {
    const headers = new Headers(options.headers || {});
    if (options.auth !== false && access)
      headers.set("Authorization", `Bearer ${access}`);
    let body = options.body;
    if (options.json !== undefined) {
      headers.set("Content-Type", "application/json");
      body = JSON.stringify(options.json);
    }
    let response;
    try {
      const timeout = AbortSignal.timeout(30_000);
      const url = path.startsWith("http") ? path : `${apiBase}${path}`; response = await fetchImpl(url, {
        method: options.method || "GET",
        headers,
        body,
        signal: options.signal
          ? AbortSignal.any([options.signal, timeout])
          : timeout,
      });
    } catch (error) {
      if (options.signal?.aborted) throw error;
      throw new ApiError(
        error.name === "TimeoutError"
          ? "The server took too long to respond. Please try again."
          : "Cannot reach the SDIP API. Start the backend, or explore the demo workspace.",
      );
    }
    if (response.status === 401 && options.auth !== false) {
      if (!retry) {
        // Another concurrent request may already have rotated our original access token.
        const tokenHasChanged =
          headers.get("Authorization") !== (access ? `Bearer ${access}` : null);
        if (tokenHasChanged || (await refresh()))
          return send(path, options, true);
      }
      clearTokens();
      if (!expired) {
        expired = true;
        onUnauthorized();
      }
      throw new ApiError(
        "Your session has expired. Please sign in again.",
        401,
      );
    }
    if (!response.ok) {
      if (response.status === 401)
        throw new ApiError("The email address or password is incorrect.", 401);
      if (response.status === 403)
        throw new ApiError(
          "Your role does not have permission to perform this action.",
          403,
        );
      if (response.status === 404)
        throw new ApiError("This record was not found in your workspace.", 404);
      if (response.status === 413)
        throw new ApiError(
          "The file is too large. The maximum upload size is 150 MB.",
          413,
        );
      let detail = "";
      try {
        if ((response.headers.get("content-type") || "").includes("json")) {
          const payload = await response.json();
          detail =
            typeof payload.message === "string"
              ? payload.message
              : typeof payload.error === "string"
                ? payload.error
                : "";
        }
      } catch {
        /* Keep non-JSON server errors out of the interface. */
      }
      throw new ApiError(
        response.status >= 500
          ? "The server could not complete this request. Please try again."
          : detail.slice(0, 250) ||
            `The request could not be completed (HTTP ${response.status}).`,
        response.status,
      );
    }
    return response;
  }

  return {
    demo: false,
    get hasSession() {
      return Boolean(access || refreshToken);
    },
    setTokens,
    clearTokens,
    async request(path, options = {}) {
      const response = await send(path, options);
      if (
        response.status === 204 ||
        response.headers.get("content-length") === "0"
      )
        return null;
      return (response.headers.get("content-type") || "").includes("json")
        ? response.json()
        : response.text();
    },
    async download(path) {
      return (await send(path)).blob();
    },
  };
}


