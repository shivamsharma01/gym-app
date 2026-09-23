const ORIGIN = "https://app.kainazi.com";
function shouldProxy(pathname) {
  return (
    pathname.startsWith("/api/") ||
    pathname.startsWith("/actuator/") ||
    pathname === "/live" ||
    pathname.startsWith("/live/") ||
    pathname === "/gateway" ||
    pathname.startsWith("/gateway/")
  );
}
export default {
  async fetch(request) {
    const url = new URL(request.url);
    if (!shouldProxy(url.pathname)) {
      // Not an API path — let Pages handle it (shouldn't hit Worker for /app)
      return fetch(request);
    }
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