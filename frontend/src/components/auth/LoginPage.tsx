import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { request } from "../../api/client";
import { useAuthStore } from "../../stores/authStore";

type Mode = "login" | "register";

interface AuthSessionPayload {
  token: string;
  user: {
    user_id: string;
    username: string;
    display_name: string;
    roles: string[];
  };
}

export function LoginPage() {
  const navigate = useNavigate();
  const setToken = useAuthStore((s) => s.setToken);
  const [mode, setMode] = useState<Mode>("login");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);

    try {
      const payload =
        mode === "login"
          ? await request<AuthSessionPayload>("/api/auth/login", {
              method: "POST",
              body: JSON.stringify({ username, password }),
            })
          : await request<AuthSessionPayload>("/api/auth/register", {
              method: "POST",
              body: JSON.stringify({
                username,
                password,
                display_name: displayName,
              }),
            });

      setToken(payload.token);
      navigate("/", { replace: true });
    } catch (submitError) {
      setError((submitError as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main
      style={{
        minHeight: "100vh",
        display: "grid",
        placeItems: "center",
        padding: "24px",
        background: "#F0EDE4",
        fontFamily: "var(--font-ui)",
        position: "relative",
        overflow: "hidden",
      }}
    >
      {/* Layered background gradients */}
      <div
        style={{
          position: "absolute",
          inset: 0,
          background:
            "radial-gradient(ellipse 80% 60% at 20% 10%, rgba(74, 124, 89, 0.08), transparent 50%), " +
            "radial-gradient(ellipse 60% 80% at 80% 90%, rgba(59, 125, 216, 0.06), transparent 50%), " +
            "radial-gradient(ellipse 50% 50% at 50% 50%, rgba(196, 113, 59, 0.04), transparent 60%)",
          pointerEvents: "none",
        }}
      />

      {/* Decorative botanical illustration — top-left */}
      <svg
        style={{
          position: "absolute",
          top: "-2%",
          left: "-2%",
          opacity: 0.35,
          pointerEvents: "none",
        }}
        width="560"
        height="560"
        viewBox="0 0 480 480"
        fill="none"
      >
        {/* Rhizome root network */}
        <path d="M60 420 Q120 360 180 340 Q240 320 300 280 Q340 260 380 200" stroke="#4A7C59" strokeWidth="2" fill="none" />
        <path d="M60 420 Q100 380 140 370 Q200 350 240 300 Q260 270 300 220 Q320 190 360 140" stroke="#4A7C59" strokeWidth="1.5" fill="none" opacity="0.7" />
        <path d="M180 340 Q200 310 220 290 Q250 260 260 220" stroke="#4A7C59" strokeWidth="1.2" fill="none" opacity="0.6" />
        <path d="M300 280 Q320 300 350 320 Q380 340 420 350" stroke="#4A7C59" strokeWidth="1.2" fill="none" opacity="0.6" />
        <path d="M240 300 Q220 280 200 240 Q180 200 170 160" stroke="#4A7C59" strokeWidth="1" fill="none" opacity="0.5" />
        <path d="M140 370 Q120 340 100 300 Q85 260 90 220" stroke="#4A7C59" strokeWidth="0.8" fill="none" opacity="0.4" />
        <path d="M380 200 Q400 180 430 170 Q450 165 470 170" stroke="#2D8F6F" strokeWidth="1" fill="none" opacity="0.5" />
        {/* Node dots at branch points */}
        <circle cx="60" cy="420" r="6" fill="#4A7C59" opacity="0.6" />
        <circle cx="180" cy="340" r="7" fill="#4A7C59" opacity="0.7" />
        <circle cx="300" cy="280" r="8" fill="#3B7DD8" opacity="0.6" />
        <circle cx="240" cy="300" r="6" fill="#7C5CBF" opacity="0.6" />
        <circle cx="360" cy="140" r="5" fill="#2D8F6F" opacity="0.6" />
        <circle cx="260" cy="220" r="5" fill="#4A7C59" opacity="0.5" />
        <circle cx="420" cy="350" r="5" fill="#3B7DD8" opacity="0.5" />
        <circle cx="170" cy="160" r="4" fill="#7C5CBF" opacity="0.5" />
        <circle cx="90" cy="220" r="4" fill="#2D8F6F" opacity="0.4" />
        <circle cx="470" cy="170" r="4" fill="#2D8F6F" opacity="0.5" />
        {/* Tiny satellite dots */}
        <circle cx="200" cy="350" r="2.5" fill="#3B7DD8" opacity="0.4" />
        <circle cx="320" cy="260" r="2.5" fill="#7C5CBF" opacity="0.4" />
        <circle cx="280" cy="240" r="2" fill="#4A7C59" opacity="0.3" />
      </svg>

      {/* Decorative botanical illustration — bottom-right */}
      <svg
        style={{
          position: "absolute",
          bottom: "-5%",
          right: "-3%",
          opacity: 0.3,
          pointerEvents: "none",
        }}
        width="600"
        height="600"
        viewBox="0 0 520 520"
        fill="none"
      >
        {/* Concentric growth rings */}
        <circle cx="260" cy="260" r="220" stroke="#4A7C59" strokeWidth="1" strokeDasharray="6 10" />
        <circle cx="260" cy="260" r="170" stroke="#4A7C59" strokeWidth="1.2" strokeDasharray="4 8" />
        <circle cx="260" cy="260" r="120" stroke="#4A7C59" strokeWidth="1.5" />
        <circle cx="260" cy="260" r="70" stroke="#4A7C59" strokeWidth="1.8" />
        <circle cx="260" cy="260" r="25" stroke="#4A7C59" strokeWidth="2" />
        {/* Cross-hairs */}
        <line x1="260" y1="30" x2="260" y2="490" stroke="#4A7C59" strokeWidth="0.8" opacity="0.5" />
        <line x1="30" y1="260" x2="490" y2="260" stroke="#4A7C59" strokeWidth="0.8" opacity="0.5" />
        {/* Diagonal lines */}
        <line x1="100" y1="100" x2="420" y2="420" stroke="#4A7C59" strokeWidth="0.6" opacity="0.35" />
        <line x1="420" y1="100" x2="100" y2="420" stroke="#4A7C59" strokeWidth="0.6" opacity="0.35" />
        {/* Leaf-like curves branching from center */}
        <path d="M260 260 Q310 190 350 165 Q375 150 400 155" stroke="#2D8F6F" strokeWidth="1.8" fill="none" opacity="0.6" />
        <path d="M260 260 Q210 190 185 145 Q170 120 175 95" stroke="#3B7DD8" strokeWidth="1.8" fill="none" opacity="0.5" />
        <path d="M260 260 Q330 310 375 355 Q395 380 415 385" stroke="#7C5CBF" strokeWidth="1.5" fill="none" opacity="0.5" />
        <path d="M260 260 Q200 320 160 370 Q140 395 120 405" stroke="#C4713B" strokeWidth="1.2" fill="none" opacity="0.4" />
        {/* Endpoint dots */}
        <circle cx="400" cy="155" r="6" fill="#2D8F6F" opacity="0.7" />
        <circle cx="175" cy="95" r="5" fill="#3B7DD8" opacity="0.6" />
        <circle cx="415" cy="385" r="5" fill="#7C5CBF" opacity="0.6" />
        <circle cx="120" cy="405" r="4" fill="#C4713B" opacity="0.5" />
        <circle cx="260" cy="260" r="6" fill="#4A7C59" opacity="0.8" />
        {/* Ring intersection markers */}
        <circle cx="260" cy="40" r="3" fill="#4A7C59" opacity="0.4" />
        <circle cx="480" cy="260" r="3" fill="#4A7C59" opacity="0.4" />
        <circle cx="260" cy="480" r="3" fill="#4A7C59" opacity="0.4" />
        <circle cx="40" cy="260" r="3" fill="#4A7C59" opacity="0.4" />
      </svg>

      <section
        style={{
          position: "relative",
          width: "min(440px, 100%)",
          padding: "36px 32px 32px",
          borderRadius: "var(--radius-xl)",
          background: "rgba(253, 252, 249, 0.88)",
          border: "1px solid rgba(74, 124, 89, 0.12)",
          boxShadow:
            "0 1px 2px rgba(26, 29, 27, 0.04), " +
            "0 8px 24px rgba(26, 29, 27, 0.06), " +
            "0 32px 80px rgba(26, 29, 27, 0.1)",
          backdropFilter: "blur(20px) saturate(1.3)",
          animation: "scale-in 500ms var(--ease-out)",
        }}
      >
        <div style={{ display: "grid", gap: "10px", marginBottom: "28px" }}>
          <span
            style={{
              width: "fit-content",
              padding: "5px 12px",
              borderRadius: "var(--radius-full)",
              background: "rgba(74, 124, 89, 0.08)",
              color: "var(--color-accent)",
              fontSize: "11px",
              fontWeight: 600,
              letterSpacing: "0.1em",
              fontFamily: "var(--font-mono)",
            }}
          >
            RHIZODELTA
          </span>
          <h1
            style={{
              margin: 0,
              fontSize: "var(--font-size-2xl)",
              lineHeight: 1.1,
              color: "var(--color-text-primary)",
              fontFamily: "var(--font-content)",
              fontWeight: 400,
              letterSpacing: "-0.02em",
            }}
          >
            登录你的谱系工作台
          </h1>
          <p style={{ margin: 0, color: "var(--color-text-secondary)", lineHeight: 1.6, fontSize: "var(--font-size-sm)" }}>
            使用已有账号进入图谱；如果你是第一次使用，可以直接注册一个 USER 身份。
          </p>
        </div>

        {/* Mode toggle */}
        <div
          style={{
            display: "grid",
            gridTemplateColumns: "1fr 1fr",
            gap: "4px",
            padding: "4px",
            borderRadius: "var(--radius-full)",
            background: "rgba(26, 29, 27, 0.05)",
            marginBottom: "24px",
          }}
        >
          {([
            ["login", "登录"],
            ["register", "注册"],
          ] as const).map(([value, label]) => {
            const active = mode === value;
            return (
              <button
                key={value}
                type="button"
                onClick={() => {
                  setMode(value);
                  setError(null);
                }}
                style={{
                  border: "none",
                  borderRadius: "var(--radius-full)",
                  padding: "10px 0",
                  background: active ? "var(--color-text-primary)" : "transparent",
                  color: active ? "var(--color-bg-primary)" : "var(--color-text-secondary)",
                  cursor: "pointer",
                  fontSize: "var(--font-size-sm)",
                  fontWeight: 600,
                  fontFamily: "var(--font-ui)",
                  transition: "all var(--transition-fast)",
                  letterSpacing: "0.01em",
                }}
              >
                {label}
              </button>
            );
          })}
        </div>

        <form
          onSubmit={handleSubmit}
          style={{ display: "grid", gap: "16px" }}
        >
          <label style={{ display: "grid", gap: "6px", color: "var(--color-text-primary)" }}>
            <span style={{ fontSize: "var(--font-size-xs)", fontWeight: 500, color: "var(--color-text-secondary)" }}>用户名</span>
            <input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              placeholder="alice"
              autoComplete="username"
              required
              style={inputStyle}
            />
          </label>

          {mode === "register" && (
            <label style={{ display: "grid", gap: "6px", color: "var(--color-text-primary)" }}>
              <span style={{ fontSize: "var(--font-size-xs)", fontWeight: 500, color: "var(--color-text-secondary)" }}>显示名称</span>
              <input
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                placeholder="Alice"
                style={inputStyle}
              />
            </label>
          )}

          <label style={{ display: "grid", gap: "6px", color: "var(--color-text-primary)" }}>
            <span style={{ fontSize: "var(--font-size-xs)", fontWeight: 500, color: "var(--color-text-secondary)" }}>密码</span>
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="至少 8 位"
              autoComplete={mode === "login" ? "current-password" : "new-password"}
              required
              style={inputStyle}
            />
          </label>

          {error && (
            <div
              style={{
                padding: "12px 14px",
                borderRadius: "var(--radius-md)",
                background: "rgba(196, 69, 58, 0.06)",
                border: "1px solid rgba(196, 69, 58, 0.12)",
                color: "var(--color-danger)",
                fontSize: "var(--font-size-sm)",
                lineHeight: 1.5,
              }}
            >
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={submitting}
            style={{
              border: "none",
              borderRadius: "var(--radius-md)",
              padding: "13px 18px",
              background: "var(--color-text-primary)",
              color: "var(--color-bg-primary)",
              cursor: submitting ? "wait" : "pointer",
              fontSize: "var(--font-size-base)",
              fontWeight: 600,
              fontFamily: "var(--font-ui)",
              marginTop: "4px",
              transition: "all var(--transition-fast)",
              boxShadow: "var(--shadow-sm)",
              letterSpacing: "0.01em",
            }}
          >
            {submitting ? "提交中..." : mode === "login" ? "进入工作台" : "创建账号并登录"}
          </button>
        </form>
      </section>
    </main>
  );
}

const inputStyle: React.CSSProperties = {
  border: "1px solid var(--color-border-default)",
  borderRadius: "var(--radius-md)",
  padding: "12px 14px",
  fontSize: "var(--font-size-base)",
  background: "rgba(255, 255, 255, 0.7)",
  color: "var(--color-text-primary)",
  outline: "none",
  fontFamily: "var(--font-ui)",
  transition: "border-color 150ms, box-shadow 150ms",
};
