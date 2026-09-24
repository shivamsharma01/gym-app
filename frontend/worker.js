const ORIGIN = "https://app.kainazi.com";

/**
 * Invoked only for paths in wrangler.jsonc assets.run_worker_first.
 * Proxies API / live / gateway / actuator to the VPS (app.kainazi.com).
 *
 * Strip browser Origin/Referer so Spring does not treat the request as
 * cross-origin CORS (SPA is same-origin on gym.*; upstream is app.*).
 */
export default {
  async fetch(request) {
    const url = new URL(request.url);
    const target = new URL(url.pathname + url.search, ORIGIN);
    const headers = new Headers(request.headers);
    headers.set("Host", "app.kainazi.com");
    headers.set("X-Forwarded-Proto", "https");
    headers.set(
      "X-Forwarded-For",
      request.headers.get("CF-Connecting-IP") ?? "",
    );
    headers.delete("Origin");
    headers.delete("Referer");

    return fetch(
      new Request(target.toString(), {
        method: request.method,
        headers,
        body: request.body,
        redirect: "manual",
      }),
    );
  },
};
