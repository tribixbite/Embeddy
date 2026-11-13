import { describe, expect, test } from "bun:test";
import { EMBED_TEMPLATES } from "./component-embed-templates";
import { buildInlineEmbedSnippet, validateComponentEmbed } from "./component-embed";

describe("EMBED_TEMPLATES", () => {
  test.each(EMBED_TEMPLATES.map((t) => [t.id, t.json]))("%s is valid with no warnings", (_id, json) => {
    const v = validateComponentEmbed(json);
    expect(v.issues).toEqual([]);
    expect(v.ok).toBe(true);
  });

  test.each(EMBED_TEMPLATES.map((t) => [t.id, t.json]))("%s fits the 3,000-byte limit once minified", (_id, json) => {
    expect(buildInlineEmbedSnippet(json)!.bytes).toBeLessThanOrEqual(3_000);
  });
});
