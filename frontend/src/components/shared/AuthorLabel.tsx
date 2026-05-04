interface AuthorLabelProps {
  displayName?: string | null;
  username?: string | null;
  authorId?: string | null;
  /**
   * AI / 共识 / 结果节点的模型标识，例如 "deepseek-v4-flash"。
   * 当该节点没有人类作者（无 UserProfile / 无 username）时，把 agentVersion
   * 作为显示名兜底，避免落到 "Anonymous" —— 这是 AI 节点真正的"作者"。
   */
  agentVersion?: string | null;
  className?: string;
}

/**
 * 解析作者展示名。
 *
 * <p>优先级：
 * <ol>
 *   <li>{@code displayName} —— 用户主动设置的显示名</li>
 *   <li>{@code username} —— 注册用户名</li>
 *   <li>{@code agentVersion} —— AI / 共识 / 结果节点的模型标识；
 *       Human Post 这一步通常是 null，自然跳过</li>
 *   <li>{@code "Anonymous"} —— 兜底</li>
 * </ol>
 *
 * <p>不再回退到 author_id —— 那是个 UUID，外露 UI 体验差，且未授权用户不应
 * 直接看到内部主键。
 */
export function resolveAuthorName(
  displayName?: string | null,
  username?: string | null,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _authorId?: string | null,
  agentVersion?: string | null,
): string {
  return (
    displayName ??
    username ??
    formatAgentVersion(agentVersion) ??
    "Anonymous"
  );
}

/**
 * 把模型 slug 格式化成更可读的展示名。
 * 例：deepseek-v4-flash → DeepSeek V4 Flash
 *      gpt-4o-mini      → GPT 4o Mini
 *      qwen2.5-7b       → Qwen2.5 7B
 *
 * <p>已含空格的（如 "DeepSeek V4 Flash"）会原样返回，避免双重格式化。
 */
function formatAgentVersion(slug?: string | null): string | null {
  if (!slug) return null;
  if (slug.includes(" ")) return slug;
  return slug
    .split(/[-_/]/)
    .filter(Boolean)
    .map(prettifyModelToken)
    .join(" ");
}

/**
 * 单段美化：先匹配常见品牌（大小写/驼峰固定），落空则做朴素首字母大写。
 */
function prettifyModelToken(token: string): string {
  // 数字版本号直接大写：v4 → V4, v1.5 → V1.5
  if (/^v\d/i.test(token)) {
    return token.toUpperCase();
  }
  // 已经是合理大小写（首字母大写或全大写）就保留
  if (/^[A-Z]/.test(token)) {
    return token;
  }
  // 已知厂商 / 缩写的固定大小写
  const lower = token.toLowerCase();
  const brands: Record<string, string> = {
    deepseek: "DeepSeek",
    openai: "OpenAI",
    gpt: "GPT",
    llm: "LLM",
    sft: "SFT",
    rlhf: "RLHF",
    moe: "MoE",
    qwen: "Qwen",
    llama: "Llama",
    gemini: "Gemini",
    claude: "Claude",
    mistral: "Mistral",
    mixtral: "Mixtral",
    yi: "Yi",
    glm: "GLM",
    chatglm: "ChatGLM",
    baichuan: "Baichuan",
    minimax: "MiniMax",
  };
  if (brands[lower]) return brands[lower];
  // 形如 "7b" / "13b" / "70b" 的参数规模：数字部分保留，b 大写
  if (/^\d+(\.\d+)?b$/i.test(token)) {
    return token.replace(/b$/i, "B");
  }
  // 朴素首字母大写
  return token.charAt(0).toUpperCase() + token.slice(1);
}

export function AuthorLabel({
  displayName,
  username,
  authorId,
  agentVersion,
  className,
}: AuthorLabelProps) {
  return (
    <span className={className}>
      {resolveAuthorName(displayName, username, authorId, agentVersion)}
    </span>
  );
}
