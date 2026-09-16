import { useEffect, useState } from 'react';

export function QueryError({ error }: { error: unknown }) {
  const [visible, setVisible] = useState(true);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setVisible(false);
    }, 5000);

    return () => window.clearTimeout(timer);
  }, [error]);

  if (!visible) {
    return null;
  }

  const message =
      error instanceof Error
          ? error.message
          : 'Something went wrong. Please try again.';

  return (
      <div className="rounded-xl border border-red-500/30 bg-red-500/10 px-5 py-4 text-red-300 shadow-lg">
        <div className="font-semibold">
          Unable to complete the request
        </div>

        <div className="mt-1 text-sm text-red-300/90">
          {message}
        </div>
      </div>
  );
}