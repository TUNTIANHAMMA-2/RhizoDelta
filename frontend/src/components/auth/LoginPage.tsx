import { useState } from "react";
import { useNavigate } from "react-router-dom";
import clsx from "clsx";
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

const INPUT_CLASS =
  "border border-border-default rounded-md px-[14px] py-3 text-base bg-white/70 text-text-primary outline-none font-ui transition-[border-color,box-shadow] duration-150";

const FIELD_LABEL_CLASS =
  "text-xs font-medium text-text-secondary";

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
      className="min-h-screen grid place-items-center p-6 font-ui relative overflow-hidden"
      style={{ background: "#F0EDE4" }}
    >
      {/* Layered background gradients */}
      <div
        className="absolute inset-0 pointer-events-none"
        style={{
          background:
            "radial-gradient(ellipse 80% 60% at 20% 10%, rgba(74, 124, 89, 0.08), transparent 50%), " +
            "radial-gradient(ellipse 60% 80% at 80% 90%, rgba(59, 125, 216, 0.06), transparent 50%), " +
            "radial-gradient(ellipse 50% 50% at 50% 50%, rgba(196, 113, 59, 0.04), transparent 60%)",
        }}
      />

      {/* Decorative botanical illustration — top-left */}
      <svg
        className="absolute -top-[2%] -left-[2%] opacity-[0.35] pointer-events-none"
        width="560"
        height="560"
        viewBox="0 0 480 480"
        fill="none"
      >
        <path d="M60 420 Q120 360 180 340 Q240 320 300 280 Q340 260 380 200" stroke="#4A7C59" strokeWidth="2" fill="none" />
        <path d="M60 420 Q100 380 140 370 Q200 350 240 300 Q260 270 300 220 Q320 190 360 140" stroke="#4A7C59" strokeWidth="1.5" fill="none" opacity="0.7" />
        <path d="M180 340 Q200 310 220 290 Q250 260 260 220" stroke="#4A7C59" strokeWidth="1.2" fill="none" opacity="0.6" />
        <path d="M300 280 Q320 300 350 320 Q380 340 420 350" stroke="#4A7C59" strokeWidth="1.2" fill="none" opacity="0.6" />
        <path d="M240 300 Q220 280 200 240 Q180 200 170 160" stroke="#4A7C59" strokeWidth="1" fill="none" opacity="0.5" />
        <path d="M140 370 Q120 340 100 300 Q85 260 90 220" stroke="#4A7C59" strokeWidth="0.8" fill="none" opacity="0.4" />
        <path d="M380 200 Q400 180 430 170 Q450 165 470 170" stroke="#2D8F6F" strokeWidth="1" fill="none" opacity="0.5" />
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
        <circle cx="200" cy="350" r="2.5" fill="#3B7DD8" opacity="0.4" />
        <circle cx="320" cy="260" r="2.5" fill="#7C5CBF" opacity="0.4" />
        <circle cx="280" cy="240" r="2" fill="#4A7C59" opacity="0.3" />
      </svg>

      {/* Decorative botanical illustration — bottom-right */}
      <svg
        className="absolute -bottom-[5%] -right-[3%] opacity-30 pointer-events-none"
        width="600"
        height="600"
        viewBox="0 0 520 520"
        fill="none"
      >
        <circle cx="260" cy="260" r="220" stroke="#4A7C59" strokeWidth="1" strokeDasharray="6 10" />
        <circle cx="260" cy="260" r="170" stroke="#4A7C59" strokeWidth="1.2" strokeDasharray="4 8" />
        <circle cx="260" cy="260" r="120" stroke="#4A7C59" strokeWidth="1.5" />
        <circle cx="260" cy="260" r="70" stroke="#4A7C59" strokeWidth="1.8" />
        <circle cx="260" cy="260" r="25" stroke="#4A7C59" strokeWidth="2" />
        <line x1="260" y1="30" x2="260" y2="490" stroke="#4A7C59" strokeWidth="0.8" opacity="0.5" />
        <line x1="30" y1="260" x2="490" y2="260" stroke="#4A7C59" strokeWidth="0.8" opacity="0.5" />
        <line x1="100" y1="100" x2="420" y2="420" stroke="#4A7C59" strokeWidth="0.6" opacity="0.35" />
        <line x1="420" y1="100" x2="100" y2="420" stroke="#4A7C59" strokeWidth="0.6" opacity="0.35" />
        <path d="M260 260 Q310 190 350 165 Q375 150 400 155" stroke="#2D8F6F" strokeWidth="1.8" fill="none" opacity="0.6" />
        <path d="M260 260 Q210 190 185 145 Q170 120 175 95" stroke="#3B7DD8" strokeWidth="1.8" fill="none" opacity="0.5" />
        <path d="M260 260 Q330 310 375 355 Q395 380 415 385" stroke="#7C5CBF" strokeWidth="1.5" fill="none" opacity="0.5" />
        <path d="M260 260 Q200 320 160 370 Q140 395 120 405" stroke="#C4713B" strokeWidth="1.2" fill="none" opacity="0.4" />
        <circle cx="400" cy="155" r="6" fill="#2D8F6F" opacity="0.7" />
        <circle cx="175" cy="95" r="5" fill="#3B7DD8" opacity="0.6" />
        <circle cx="415" cy="385" r="5" fill="#7C5CBF" opacity="0.6" />
        <circle cx="120" cy="405" r="4" fill="#C4713B" opacity="0.5" />
        <circle cx="260" cy="260" r="6" fill="#4A7C59" opacity="0.8" />
        <circle cx="260" cy="40" r="3" fill="#4A7C59" opacity="0.4" />
        <circle cx="480" cy="260" r="3" fill="#4A7C59" opacity="0.4" />
        <circle cx="260" cy="480" r="3" fill="#4A7C59" opacity="0.4" />
        <circle cx="40" cy="260" r="3" fill="#4A7C59" opacity="0.4" />
      </svg>

      <section className="relative w-[min(440px,100%)] pt-9 px-8 pb-8 rounded-xl bg-[rgba(253,252,249,0.88)] border border-[rgba(74,124,89,0.12)] shadow-xl backdrop-blur-[20px] backdrop-saturate-[1.3] animate-scale-in">
        <div className="grid gap-[10px] mb-7">
          <span className="w-fit px-3 py-[5px] rounded-full bg-[rgba(74,124,89,0.08)] text-accent text-[11px] font-semibold tracking-[0.1em] font-mono">
            RHIZODELTA
          </span>
          <h1 className="m-0 text-2xl leading-[1.1] text-text-primary font-content font-normal tracking-[-0.02em]">
            登录你的谱系工作台
          </h1>
          <p className="m-0 text-text-secondary leading-[1.6] text-sm">
            使用已有账号进入图谱；如果你是第一次使用，可以直接注册一个 USER 身份。
          </p>
        </div>

        {/* Mode toggle */}
        <div className="grid grid-cols-2 gap-1 p-1 rounded-full bg-[rgba(26,29,27,0.05)] mb-6">
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
                className={clsx(
                  "border-none rounded-full py-[10px] cursor-pointer text-sm font-semibold font-ui transition-[all] duration-[var(--transition-fast)] tracking-[0.01em]",
                  active
                    ? "bg-text-primary text-bg-primary"
                    : "bg-transparent text-text-secondary",
                )}
              >
                {label}
              </button>
            );
          })}
        </div>

        <form onSubmit={handleSubmit} className="grid gap-4">
          <label className="grid gap-[6px] text-text-primary">
            <span className={FIELD_LABEL_CLASS}>用户名</span>
            <input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              placeholder="alice"
              autoComplete="username"
              required
              className={INPUT_CLASS}
            />
          </label>

          {mode === "register" && (
            <label className="grid gap-[6px] text-text-primary">
              <span className={FIELD_LABEL_CLASS}>显示名称</span>
              <input
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                placeholder="Alice"
                className={INPUT_CLASS}
              />
            </label>
          )}

          <label className="grid gap-[6px] text-text-primary">
            <span className={FIELD_LABEL_CLASS}>密码</span>
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="至少 8 位"
              autoComplete={mode === "login" ? "current-password" : "new-password"}
              required
              className={INPUT_CLASS}
            />
          </label>

          {error && (
            <div className="px-[14px] py-3 rounded-md bg-[rgba(196,69,58,0.06)] border border-[rgba(196,69,58,0.12)] text-danger text-sm leading-[1.5]">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={submitting}
            className={clsx(
              "border-none rounded-md px-[18px] py-[13px] bg-text-primary text-bg-primary text-base font-semibold font-ui mt-1 transition-[all] duration-[var(--transition-fast)] shadow-sm tracking-[0.01em]",
              submitting ? "cursor-wait" : "cursor-pointer",
            )}
          >
            {submitting ? "提交中..." : mode === "login" ? "进入工作台" : "创建账号并登录"}
          </button>
        </form>
      </section>
    </main>
  );
}
