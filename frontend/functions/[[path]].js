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

/**
 * Cloudflare Pages Function (not a standalone Worker).
 * Must export onRequest* — export default { fetch } is ignored.
 */
export async function onRequest(context) {
  const { request, next } = context;
  const url = new URL(request.url);

  if (!shouldProxy(url.pathname)) {
    return next();
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
}
