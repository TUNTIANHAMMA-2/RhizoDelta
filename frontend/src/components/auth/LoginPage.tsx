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
        background:
          "radial-gradient(circle at top left, rgba(205, 227, 255, 0.9), transparent 38%), linear-gradient(135deg, #f3efe4 0%, #ece6d8 46%, #d6e0d5 100%)",
        fontFamily: "var(--font-ui)",
      }}
    >
      <section
        style={{
          width: "min(440px, 100%)",
          padding: "32px",
          borderRadius: "28px",
          background: "rgba(255, 255, 255, 0.86)",
          border: "1px solid rgba(79, 95, 87, 0.15)",
          boxShadow: "0 24px 80px rgba(83, 74, 54, 0.16)",
          backdropFilter: "blur(16px)",
        }}
      >
        <div style={{ display: "grid", gap: "12px", marginBottom: "24px" }}>
          <span
            style={{
              width: "fit-content",
              padding: "6px 12px",
              borderRadius: "999px",
              background: "rgba(56, 89, 72, 0.08)",
              color: "#365846",
              fontSize: "12px",
              fontWeight: 600,
              letterSpacing: "0.08em",
            }}
          >
            RHIZODELTA ACCESS
          </span>
          <h1
            style={{
              margin: 0,
              fontSize: "36px",
              lineHeight: 1.05,
              color: "#233129",
              fontFamily: "var(--font-content)",
            }}
          >
            登录你的谱系工作台
          </h1>
          <p style={{ margin: 0, color: "#5d665f", lineHeight: 1.6 }}>
            使用已有账号进入图谱；如果你是第一次使用，可以直接注册一个 `USER` 身份。
          </p>
        </div>

        <div
          style={{
            display: "grid",
            gridTemplateColumns: "1fr 1fr",
            gap: "8px",
            padding: "6px",
            borderRadius: "999px",
            background: "rgba(35, 49, 41, 0.06)",
            marginBottom: "20px",
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
                  borderRadius: "999px",
                  padding: "10px 0",
                  background: active ? "#233129" : "transparent",
                  color: active ? "#f8f5ee" : "#5d665f",
                  cursor: "pointer",
                  fontSize: "14px",
                  fontWeight: 600,
                }}
              >
                {label}
              </button>
            );
          })}
        </div>

        <form
          onSubmit={handleSubmit}
          style={{ display: "grid", gap: "14px" }}
        >
          <label style={{ display: "grid", gap: "6px", color: "#233129" }}>
            <span>用户名</span>
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
            <label style={{ display: "grid", gap: "6px", color: "#233129" }}>
              <span>显示名称</span>
              <input
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                placeholder="Alice"
                style={inputStyle}
              />
            </label>
          )}

          <label style={{ display: "grid", gap: "6px", color: "#233129" }}>
            <span>密码</span>
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
                borderRadius: "16px",
                background: "rgba(176, 54, 54, 0.08)",
                color: "#8f2f2f",
                fontSize: "14px",
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
              borderRadius: "18px",
              padding: "14px 18px",
              background: "#233129",
              color: "#f8f5ee",
              cursor: submitting ? "wait" : "pointer",
              fontSize: "15px",
              fontWeight: 700,
              marginTop: "6px",
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
  border: "1px solid rgba(67, 88, 78, 0.18)",
  borderRadius: "16px",
  padding: "12px 14px",
  fontSize: "15px",
  background: "rgba(255, 255, 255, 0.92)",
  color: "#233129",
  outline: "none",
};
