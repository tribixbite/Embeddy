/**
 * Validation for Discord component embeds — the `discord:component-embed`
 * JSON payload a page can expose to replace its link preview with a
 * Components V2 layout.
 *
 * Rules come from two Discord pages and are tagged by severity:
 *   error   — documented to break the embed (Discord falls back to the
 *             Open Graph preview, or shows nothing)
 *   warning — a limit from the general Components reference, or a likely
 *             mistake Discord doesn't document either way
 * https://docs.discord.com/developers/link-previews/component-embeds
 * https://docs.discord.com/developers/components/reference
 */

/** Payload size limit; escape sequences count at their full written length */
export const COMPONENT_EMBED_MAX_BYTES = 3_000;
/** Components allowed in one embed, the parent Container included */
export const COMPONENT_EMBED_MAX_COMPONENTS = 40;
/** Media Gallery items across the whole embed (thumbnails don't count) */
export const COMPONENT_EMBED_MAX_GALLERY_ITEMS = 10;
/** Any media URL */
export const MEDIA_URL_MAX_CHARS = 2_048;
/** The `type` attributes Discord accepts on the script/link element */
export const COMPONENT_EMBED_MIME_TYPES = ["application/json", "application/vnd.discord.component-embed+json"] as const;
/** The element id / link rel Discord looks for */
export const COMPONENT_EMBED_ID = "discord:component-embed";

/** Component type numbers usable in a component embed */
export const ComponentType = {
  ActionRow: 1,
  Button: 2,
  Section: 9,
  TextDisplay: 10,
  Thumbnail: 11,
  MediaGallery: 12,
  File: 13,
  Separator: 14,
  Container: 17,
} as const;

/** Human names for every component type number, for messages */
const TYPE_NAMES: Record<number, string> = {
  1: "Action Row",
  2: "Button",
  3: "String Select",
  4: "Text Input",
  5: "User Select",
  6: "Role Select",
  7: "Mentionable Select",
  8: "Channel Select",
  9: "Section",
  10: "Text Display",
  11: "Thumbnail",
  12: "Media Gallery",
  13: "File",
  14: "Separator",
  17: "Container",
  18: "Label",
  19: "File Upload",
  21: "Radio Group",
  22: "Checkbox Group",
  23: "Checkbox",
};

/** Image and video extensions Discord lists for media */
export const IMAGE_EXTENSIONS = ["png", "gif", "jpg", "jpeg", "webp", "avif"] as const;
export const VIDEO_EXTENSIONS = ["mp4", "webm", "mov"] as const;

// ── Component shapes (what a valid payload looks like) ─────────────────────

export interface UnfurledMedia {
  url: string;
}
export interface ButtonComponent {
  type: 2;
  id?: number;
  style: 5;
  url: string;
  label?: string;
  emoji?: { id?: string | null; name?: string | null; animated?: boolean };
  disabled?: boolean;
}
export interface TextDisplayComponent {
  type: 10;
  id?: number;
  content: string;
}
export interface ThumbnailComponent {
  type: 11;
  id?: number;
  media: UnfurledMedia;
  description?: string | null;
  spoiler?: boolean;
}
export interface MediaGalleryItem {
  media: UnfurledMedia;
  description?: string | null;
  spoiler?: boolean;
}
export interface MediaGalleryComponent {
  type: 12;
  id?: number;
  items: MediaGalleryItem[];
}
export interface SeparatorComponent {
  type: 14;
  id?: number;
  divider?: boolean;
  spacing?: 1 | 2;
}
export interface SectionComponent {
  type: 9;
  id?: number;
  components: TextDisplayComponent[];
  accessory: ButtonComponent | ThumbnailComponent;
}
export interface ActionRowComponent {
  type: 1;
  id?: number;
  components: ButtonComponent[];
}
export type ContainerChild =
  | ActionRowComponent
  | TextDisplayComponent
  | SectionComponent
  | MediaGalleryComponent
  | SeparatorComponent;
export interface ContainerComponent {
  type: 17;
  id?: number;
  components: ContainerChild[];
  accent_color?: number | null;
  spoiler?: boolean;
}

// ── Results ─────────────────────────────────────────────────────────────────

export type IssueSeverity = "error" | "warning";

export interface EmbedIssue {
  severity: IssueSeverity;
  /** JSON path of the offending value, e.g. `component.components[2].accessory` */
  path: string;
  message: string;
}

export interface ComponentEmbedValidation {
  /** True when there are no errors (warnings allowed) */
  ok: boolean;
  /** UTF-8 size of the payload exactly as written */
  bytes: number;
  /** Components counted toward the 40 limit */
  componentCount: number;
  /** Media Gallery items counted toward the 10 limit */
  galleryItemCount: number;
  /** The root Container when the payload parsed far enough to preview */
  root?: ContainerComponent;
  issues: EmbedIssue[];
}

// ── Helpers ─────────────────────────────────────────────────────────────────

const encoder = new TextEncoder();

/** UTF-8 byte length of a string */
export function utf8ByteLength(text: string): number {
  return encoder.encode(text).byteLength;
}

type Json = Record<string, unknown>;

function isObject(value: unknown): value is Json {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Lower-cased extension of a URL's path, or "" */
export function urlExtension(url: string): string {
  try {
    const path = new URL(url).pathname;
    const dot = path.lastIndexOf(".");
    return dot > path.lastIndexOf("/") ? path.slice(dot + 1).toLowerCase() : "";
  } catch {
    return "";
  }
}

/** Classify a media URL by extension: image, video, or unknown */
export function mediaKind(url: string): "image" | "video" | "unknown" {
  const ext = urlExtension(url);
  if ((IMAGE_EXTENSIONS as readonly string[]).includes(ext)) return "image";
  if ((VIDEO_EXTENSIONS as readonly string[]).includes(ext)) return "video";
  return "unknown";
}

/** Readable name for a component type value */
function typeName(type: unknown): string {
  return typeof type === "number" && TYPE_NAMES[type] ? `${TYPE_NAMES[type]} (type ${type})` : `type ${String(type)}`;
}

// ── Validator ───────────────────────────────────────────────────────────────

/** Mutable walk state */
class Walk {
  issues: EmbedIssue[] = [];
  components = 0;
  galleryItems = 0;

  error(path: string, message: string) {
    this.issues.push({ severity: "error", path, message });
  }
  warn(path: string, message: string) {
    this.issues.push({ severity: "warning", path, message });
  }

  /** Optional `id` must be an integer when present */
  checkId(node: Json, path: string) {
    if (node.id !== undefined && !Number.isInteger(node.id)) this.warn(`${path}.id`, "id must be an integer");
  }

  /** Optional boolean field */
  checkBool(node: Json, key: string, path: string) {
    if (node[key] !== undefined && typeof node[key] !== "boolean") this.warn(`${path}.${key}`, `${key} must be true or false`);
  }

  /** Optional alt-text field (max 1,024 chars per the Components reference) */
  checkDescription(node: Json, path: string) {
    const d = node.description;
    if (d === undefined || d === null) return;
    if (typeof d !== "string") this.warn(`${path}.description`, "description must be a string");
    else if (d.length > 1024) this.warn(`${path}.description`, `Alt text is ${d.length} characters; the limit is 1,024`);
  }

  /** An unfurled media item: http(s), ≤ 2,048 chars, a supported format */
  media(value: unknown, path: string, allowVideo: boolean) {
    if (!isObject(value) || typeof value.url !== "string" || !value.url) {
      this.error(path, "media must be an object with a url, e.g. { \"url\": \"https://…/image.webp\" }");
      return;
    }
    const url = value.url;
    if (url.startsWith("attachment://")) {
      this.error(`${path}.url`, "attachment:// only works for bot messages; a web page must use an http(s) URL");
      return;
    }
    if (!/^https?:\/\//i.test(url)) {
      this.error(`${path}.url`, "Media URLs must use http or https");
      return;
    }
    if (url.length > MEDIA_URL_MAX_CHARS) {
      this.error(`${path}.url`, `URL is ${url.length} characters; the limit is ${MEDIA_URL_MAX_CHARS}`);
    }
    const kind = mediaKind(url);
    if (kind === "video" && !allowVideo) {
      this.warn(`${path}.url`, "Thumbnails are images; put video in a Media Gallery");
    } else if (kind === "unknown") {
      this.warn(
        `${path}.url`,
        `No recognised extension — Discord accepts ${IMAGE_EXTENSIONS.join("/")} images and ${VIDEO_EXTENSIONS.join("/")} video, so make sure the server returns one of those`,
      );
    }
    for (const key of Object.keys(value)) {
      if (key !== "url") this.warn(`${path}.${key}`, `${key} is filled in by Discord after it fetches the asset; leave it out`);
    }
  }

  button(node: Json, path: string) {
    this.components++;
    const allowed = new Set(["id", "type", "url", "style", "label", "emoji", "disabled"]);
    for (const key of Object.keys(node)) {
      if (!allowed.has(key)) this.error(`${path}.${key}`, `Buttons may only use id, type, url, style, label, emoji and disabled — "${key}" invalidates the whole payload`);
    }
    if (node.style !== 5) this.error(`${path}.style`, "Buttons must be link buttons (style 5)");
    if (typeof node.url !== "string" || !node.url) {
      this.error(`${path}.url`, "A link button needs a url");
    } else {
      if (!/^https?:\/\//i.test(node.url)) this.warn(`${path}.url`, "Use an http(s) URL");
      if (node.url.length > 512) this.warn(`${path}.url`, `Button URL is ${node.url.length} characters; the limit is 512`);
    }
    const hasLabel = typeof node.label === "string" && node.label.length > 0;
    const hasEmoji = isObject(node.emoji);
    if (!hasLabel && !hasEmoji) this.error(path, "A button needs a label, an emoji, or both");
    if (typeof node.label === "string" && node.label.length > 80) {
      this.warn(`${path}.label`, `Label is ${node.label.length} characters; the limit is 80`);
    }
    if (node.emoji !== undefined && !hasEmoji) this.warn(`${path}.emoji`, "emoji must be an object like { \"name\": \"🔥\" }");
    this.checkId(node, path);
    this.checkBool(node, "disabled", path);
  }

  textDisplay(node: Json, path: string) {
    this.components++;
    if (typeof node.content !== "string" || node.content.length === 0) {
      this.error(`${path}.content`, "Text Display needs non-empty content");
    }
    this.checkId(node, path);
  }

  thumbnail(node: Json, path: string) {
    this.components++;
    this.media(node.media, `${path}.media`, false);
    this.checkDescription(node, path);
    this.checkBool(node, "spoiler", path);
    this.checkId(node, path);
  }

  mediaGallery(node: Json, path: string) {
    this.components++;
    const items = node.items;
    if (!Array.isArray(items) || items.length === 0) {
      this.error(`${path}.items`, "A Media Gallery needs 1 to 10 items");
      return;
    }
    if (items.length > 10) this.error(`${path}.items`, `${items.length} items; a gallery holds at most 10`);
    items.forEach((item, i) => {
      const itemPath = `${path}.items[${i}]`;
      this.galleryItems++;
      if (!isObject(item)) {
        this.error(itemPath, "Gallery items must be objects with a media field");
        return;
      }
      this.media(item.media, `${itemPath}.media`, true);
      this.checkDescription(item, itemPath);
      this.checkBool(item, "spoiler", itemPath);
    });
    this.checkId(node, path);
  }

  separator(node: Json, path: string) {
    this.components++;
    this.checkBool(node, "divider", path);
    if (node.spacing !== undefined && node.spacing !== 1 && node.spacing !== 2) {
      this.warn(`${path}.spacing`, "spacing is 1 (small) or 2 (large)");
    }
    this.checkId(node, path);
  }

  section(node: Json, path: string) {
    this.components++;
    const children = node.components;
    if (!Array.isArray(children) || children.length < 1 || children.length > 3) {
      this.error(`${path}.components`, "A Section holds one to three Text Display components");
    }
    if (Array.isArray(children)) {
      children.forEach((child, i) => {
        const childPath = `${path}.components[${i}]`;
        if (isObject(child) && child.type === ComponentType.TextDisplay) this.textDisplay(child, childPath);
        else {
          this.error(childPath, `A Section can only contain Text Display components, not ${typeName(isObject(child) ? child.type : child)}`);
          if (isObject(child)) this.components++;
        }
      });
    }
    const accessory = node.accessory;
    if (!isObject(accessory)) {
      this.error(`${path}.accessory`, "A Section needs an accessory: a Thumbnail or a link Button");
    } else if (accessory.type === ComponentType.Button) {
      this.button(accessory, `${path}.accessory`);
    } else if (accessory.type === ComponentType.Thumbnail) {
      this.thumbnail(accessory, `${path}.accessory`);
    } else {
      this.components++;
      this.error(`${path}.accessory`, `A Section accessory must be a Thumbnail or Button, not ${typeName(accessory.type)}`);
    }
    this.checkId(node, path);
  }

  actionRow(node: Json, path: string) {
    this.components++;
    const children = node.components;
    if (!Array.isArray(children) || children.length < 1 || children.length > 5) {
      this.error(`${path}.components`, "An Action Row holds one to five link Buttons");
    }
    if (Array.isArray(children)) {
      children.forEach((child, i) => {
        const childPath = `${path}.components[${i}]`;
        if (isObject(child) && child.type === ComponentType.Button) this.button(child, childPath);
        else {
          if (isObject(child)) this.components++;
          this.error(childPath, `Only link Buttons work in a component embed's Action Row, not ${typeName(isObject(child) ? child.type : child)}`);
        }
      });
    }
    this.checkId(node, path);
  }

  /** A direct child of the root Container */
  containerChild(node: unknown, path: string) {
    if (!isObject(node)) {
      this.error(path, "Each component must be an object with a numeric type");
      return;
    }
    switch (node.type) {
      case ComponentType.ActionRow:
        return this.actionRow(node, path);
      case ComponentType.TextDisplay:
        return this.textDisplay(node, path);
      case ComponentType.Section:
        return this.section(node, path);
      case ComponentType.MediaGallery:
        return this.mediaGallery(node, path);
      case ComponentType.Separator:
        return this.separator(node, path);
      case ComponentType.Container:
        this.components++;
        return this.error(path, "A Container cannot contain another Container");
      case ComponentType.File:
        this.components++;
        return this.error(path, "File components need an uploaded attachment and aren't supported in component embeds");
      case ComponentType.Button:
      case ComponentType.Thumbnail:
        this.components++;
        return this.error(path, `${typeName(node.type)} can't sit directly in the Container — put it in ${node.type === ComponentType.Button ? "an Action Row or a Section accessory" : "a Section accessory"}`);
      default:
        this.components++;
        return this.error(path, `${typeName(node.type)} isn't supported in component embeds (allowed: 1, 2, 9, 10, 11, 12, 14, 17)`);
    }
  }
}

/**
 * Validate a component-embed payload exactly as written on the page.
 * @param raw The JSON text from the inline script or linked document
 */
export function validateComponentEmbed(raw: string): ComponentEmbedValidation {
  const walk = new Walk();
  const bytes = utf8ByteLength(raw);
  const result = (root?: ContainerComponent): ComponentEmbedValidation => ({
    ok: !walk.issues.some((i) => i.severity === "error"),
    bytes,
    componentCount: walk.components,
    galleryItemCount: walk.galleryItems,
    root,
    issues: walk.issues,
  });

  if (bytes > COMPONENT_EMBED_MAX_BYTES) {
    walk.error("", `Payload is ${bytes.toLocaleString()} bytes; the limit is ${COMPONENT_EMBED_MAX_BYTES.toLocaleString()} (escape sequences count at their written length)`);
  }

  let doc: unknown;
  try {
    doc = JSON.parse(raw);
  } catch (e) {
    walk.error("", `Not valid JSON: ${e instanceof Error ? e.message : String(e)}`);
    return result();
  }
  if (!isObject(doc)) {
    walk.error("", "The payload must be a JSON object: { \"component\": { \"type\": 17, … } }");
    return result();
  }
  for (const key of Object.keys(doc)) {
    if (key !== "component") walk.warn(key, `Unknown top-level key "${key}" — the document holds only "component"`);
  }
  const root = doc.component;
  if (!isObject(root)) {
    walk.error("component", "Missing \"component\": the payload needs a single parent Container (type 17)");
    return result();
  }
  if (root.type !== ComponentType.Container) {
    walk.error("component.type", `The top-level component must be a Container (type 17), not ${typeName(root.type)}`);
    return result();
  }

  walk.components++;
  walk.checkId(root, "component");
  walk.checkBool(root, "spoiler", "component");
  const accent = root.accent_color;
  if (accent !== undefined && accent !== null && (!Number.isInteger(accent) || (accent as number) < 0 || (accent as number) > 0xffffff)) {
    walk.error("component.accent_color", "accent_color is an integer RGB value from 0 to 16777215 (0xFFFFFF), e.g. 5793266 for #5865F2");
  }
  if (!Array.isArray(root.components)) {
    walk.error("component.components", "The Container needs a components array");
    return result();
  }
  if (root.components.length === 0) walk.warn("component.components", "The Container is empty");
  root.components.forEach((child, i) => walk.containerChild(child, `component.components[${i}]`));

  if (walk.components > COMPONENT_EMBED_MAX_COMPONENTS) {
    walk.error("", `${walk.components} components; the limit is ${COMPONENT_EMBED_MAX_COMPONENTS}, the Container included`);
  }
  if (walk.galleryItems > COMPONENT_EMBED_MAX_GALLERY_ITEMS) {
    walk.error("", `${walk.galleryItems} Media Gallery items in total; the limit is ${COMPONENT_EMBED_MAX_GALLERY_ITEMS} across the whole embed`);
  }

  return result(root as unknown as ContainerComponent);
}

/** Every media URL in a payload (thumbnails and gallery items), for reachability checks */
export function collectMediaUrls(root: ContainerComponent): string[] {
  const urls: string[] = [];
  const visit = (node: unknown) => {
    if (!isObject(node)) return;
    if (isObject(node.media) && typeof node.media.url === "string") urls.push(node.media.url);
    if (Array.isArray(node.items)) node.items.forEach(visit);
    if (Array.isArray(node.components)) node.components.forEach(visit);
    if (node.accessory) visit(node.accessory);
  };
  visit(root);
  return urls;
}

/** `#RRGGBB` → Discord's integer accent color */
export function hexToAccent(hex: string): number | null {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  return m ? parseInt(m[1]!, 16) : null;
}

/** Discord's integer accent color → `#rrggbb` */
export function accentToHex(value: number): string {
  return `#${(value & 0xffffff).toString(16).padStart(6, "0")}`;
}

/**
 * Minified JSON for a payload, safe to place inside a <script> element:
 * `</` becomes `<\/` (a valid JSON escape) so no string in the payload can
 * close the element early.
 */
export function serializeComponentEmbed(payload: unknown): string {
  return JSON.stringify(payload).replace(/<\//g, "<\\/");
}

/**
 * Minify a payload and wrap it in the inline <script> Discord reads.
 * `</` is written as `<\/` (a valid JSON escape) so text inside the payload
 * can't close the script element early; the returned byte count is of the
 * JSON exactly as it will appear in the page.
 */
export function buildInlineEmbedSnippet(raw: string): { html: string; bytes: number } | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  const json = serializeComponentEmbed(parsed);
  return {
    html: `<script id="${COMPONENT_EMBED_ID}" type="application/json">${json}</script>`,
    bytes: utf8ByteLength(json),
  };
}
