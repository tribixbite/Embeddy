/**
 * Accept-filtering for files arriving outside the file picker: drag-and-drop
 * anywhere on the page and clipboard paste (GlobalFileIntake.svelte). The
 * picker filters by `accept` itself; these paths must do it explicitly.
 */

/**
 * True when `file` matches an `<input accept>` string: comma-separated MIME
 * types, `type/*` wildcards, `.ext` suffixes, or `*` / `*\/*` for anything.
 */
export function acceptsFile(file: { name: string; type: string }, accept: string): boolean {
  const tokens = accept
    .split(",")
    .map((t) => t.trim().toLowerCase())
    .filter(Boolean);
  if (tokens.length === 0) return true;
  const type = file.type.toLowerCase();
  const name = file.name.toLowerCase();
  return tokens.some((token) => {
    if (token === "*" || token === "*/*") return true;
    if (token.startsWith(".")) return name.endsWith(token);
    if (token.endsWith("/*")) return type.startsWith(token.slice(0, -1));
    return type === token;
  });
}

/** The accepted subset of a FileList/array, in order */
export function pickAcceptedFiles<F extends { name: string; type: string }>(
  files: Iterable<F> | ArrayLike<F>,
  accept: string,
): F[] {
  return Array.from(files as ArrayLike<F>).filter((f) => acceptsFile(f, accept));
}

/**
 * True when a paste/keyboard event target is a text field — pasting a URL
 * into the Inspect input must not be hijacked as a file paste.
 */
export function isTextEntryTarget(target: EventTarget | null): boolean {
  if (!target || typeof (target as HTMLElement).tagName !== "string") return false;
  const el = target as HTMLElement;
  return el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.isContentEditable === true;
}
