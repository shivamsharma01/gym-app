const ORIGIN = "https://app.kainazi.com";

/**
 * Invoked only for paths in wrangler.jsonc assets.run_worker_first.
 * Proxies API / live / gateway / actuator to the VPS (app.kainazi.com).
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
