import { describe, it, expect } from "vitest";
import { stripMarkdown } from "./markdown";

describe("stripMarkdown", () => {
  it("removes headers", () => {
    expect(stripMarkdown("# Header 1\n## Header 2")).toBe("Header 1\nHeader 2");
  });

  it("removes bold and italic", () => {
    expect(stripMarkdown("**bold** and *italic*")).toBe("bold and italic");
  });

  it("removes strikethrough", () => {
    expect(stripMarkdown("~~strike~~")).toBe("strike");
  });

  it("removes inline code", () => {
    expect(stripMarkdown("`code`")).toBe("code");
  });

  it("replaces code blocks with a placeholder", () => {
    expect(stripMarkdown("```js\nconsole.log(1)\n```")).toBe("[代码块]");
  });

  it("extracts text from links", () => {
    expect(stripMarkdown("[OpenSpec](https://example.com)")).toBe("OpenSpec");
  });

  it("extracts alt text from images", () => {
    expect(stripMarkdown("![An image](https://example.com/img.png)")).toBe("An image");
  });

  it("removes blockquotes", () => {
    expect(stripMarkdown("> quote")).toBe("quote");
  });

  it("removes lists", () => {
    expect(stripMarkdown("- item 1\n* item 2\n1. item 3")).toBe("item 1\nitem 2\nitem 3");
  });

  it("removes horizontal rules", () => {
    expect(stripMarkdown("---\ntext\n***")).toBe("text");
  });

  it("handles null or undefined", () => {
    expect(stripMarkdown(null)).toBe("");
    expect(stripMarkdown(undefined)).toBe("");
  });
});
