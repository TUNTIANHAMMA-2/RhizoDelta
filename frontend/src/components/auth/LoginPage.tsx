import { useState } from "react";
import { useNavigate } from "react-router-dom";
import clsx from "clsx";
import { request } from "../../api/client";
import { useAuthStore } from "../../stores/authStore";
import { metaLabel } from "../../lib/typography";
import { WordMark } from "../brand/WordMark";

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

type FieldProps = {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  placeholder?: string;
  autoComplete?: string;
  required?: boolean;
};

function Field({ label, value, onChange, ...rest }: FieldProps) {
  return (
    <label className="block group">
      <span
        className={clsx(
          "block",
          metaLabel,
          "text-text-tertiary mb-2 transition-colors group-focus-within:text-accent",
        )}
      >
        {label}
      </span>
      <input
        {...rest}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="block w-full border-0 border-b border-border-default bg-transparent px-0 py-[10px] text-base text-text-primary outline-none placeholder:text-text-tertiary/50 focus-visible:outline-none focus:border-b-[1.5px] focus:border-accent transition-all"
      />
    </label>
  );
}

function RhizomeBackdrop() {
  return (
    <svg
      className="absolute inset-0 w-full h-full pointer-events-none"
      viewBox="0 0 900 1200"
      preserveAspectRatio="xMidYMid slice"
      fill="none"
      aria-hidden
    >
      {/* Primary trunk — bottom-left to upper-right, like a root climbing */}
      <path
        d="M 0 1150 Q 180 1010 360 920 Q 500 840 600 720 Q 700 600 770 440 Q 820 300 880 110"
        stroke="#4A7C59"
        strokeWidth="2.4"
        opacity="0.55"
      />
      {/* Secondary branches */}
      <path
        d="M 360 920 Q 310 840 250 730 Q 210 610 240 480"
        stroke="#4A7C59"
        strokeWidth="1.5"
        opacity="0.42"
      />
      <path
        d="M 600 720 Q 700 680 790 640 Q 860 590 900 540"
        stroke="#2D8F6F"
        strokeWidth="1.5"
        opacity="0.42"
      />
      <path
        d="M 600 720 Q 540 640 460 570 Q 380 490 340 380"
        stroke="#3B7DD8"
        strokeWidth="1.2"
        opacity="0.4"
      />
      <path
        d="M 770 440 Q 820 370 880 320"
        stroke="#7C5CBF"
        strokeWidth="1.2"
        opacity="0.4"
      />
      <path
        d="M 240 480 Q 170 440 90 420 Q 30 410 0 420"
        stroke="#4A7C59"
        strokeWidth="1"
        opacity="0.35"
      />
      {/* Tertiary hair-lines */}
      <path
        d="M 250 730 Q 310 770 400 770"
        stroke="#4A7C59"
        strokeWidth="0.8"
        opacity="0.25"
      />
      <path
        d="M 460 570 Q 540 550 600 510"
        stroke="#4A7C59"
        strokeWidth="0.8"
        opacity="0.25"
      />
      <path
        d="M 770 440 Q 700 380 620 360"
        stroke="#C4713B"
        strokeWidth="0.8"
        opacity="0.3"
      />

      {/* Major nodes */}
      <circle cx="360" cy="920" r="8" fill="#4A7C59" opacity="0.72" />
      <circle cx="600" cy="720" r="10" fill="#3B7DD8" opacity="0.72" />
      <circle cx="770" cy="440" r="9" fill="#7C5CBF" opacity="0.72" />
      <circle cx="240" cy="480" r="7" fill="#2D8F6F" opacity="0.65" />
      <circle cx="880" cy="110" r="6" fill="#4A7C59" opacity="0.7" />

      {/* Mid-size nodes */}
      <circle cx="900" cy="540" r="5" fill="#2D8F6F" opacity="0.6" />
      <circle cx="340" cy="380" r="5" fill="#3B7DD8" opacity="0.55" />
      <circle cx="880" cy="320" r="5" fill="#7C5CBF" opacity="0.55" />

      {/* Minor nodes */}
      <circle cx="500" cy="840" r="3" fill="#4A7C59" opacity="0.5" />
      <circle cx="700" cy="600" r="3" fill="#2D8F6F" opacity="0.5" />
      <circle cx="400" cy="770" r="2.5" fill="#4A7C59" opacity="0.4" />
      <circle cx="600" cy="510" r="2.5" fill="#4A7C59" opacity="0.4" />
      <circle cx="620" cy="360" r="3" fill="#C4713B" opacity="0.55" />
      <circle cx="90" cy="420" r="3" fill="#4A7C59" opacity="0.5" />
      <circle cx="250" cy="730" r="4" fill="#4A7C59" opacity="0.5" />

      {/* Satellite drift */}
      <circle cx="150" cy="300" r="2" fill="#4A7C59" opacity="0.3" />
      <circle cx="700" cy="280" r="2" fill="#7C5CBF" opacity="0.3" />
      <circle cx="560" cy="950" r="2" fill="#3B7DD8" opacity="0.3" />
      <circle cx="830" cy="780" r="2" fill="#2D8F6F" opacity="0.3" />
      <circle cx="50" cy="600" r="2" fill="#C4713B" opacity="0.3" />
      <circle cx="440" cy="1050" r="2.5" fill="#4A7C59" opacity="0.3" />
    </svg>
  );
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
    <main className="min-h-screen flex flex-col md:flex-row font-ui">
      {/* ═══ LEFT — editorial panel ═══ */}
      <aside
        className="hidden md:flex md:flex-1 lg:flex-[7] bg-bg-parchment relative overflow-hidden flex-col justify-between p-12 lg:p-16 xl:p-20 animate-fade-in"
      >
        <RhizomeBackdrop />

        {/* Masthead */}
        <header className="relative z-10 inline-block">
          <WordMark className="block text-[26px] leading-none" />
          <div className="mt-2.5 h-px w-32 bg-accent/40" />
          <div className="mt-2 font-mono text-[10px] tracking-[0.22em] text-text-tertiary">
            A thinking thicket · in threads
          </div>
        </header>

        {/* Manifesto — big editorial title */}
        <div className="relative z-10 max-w-xl space-y-7 animate-slide-up" style={{ animationDelay: "120ms", animationFillMode: "both" }}>
          <h1 className="font-content text-[64px] lg:text-[84px] xl:text-[96px] leading-[0.9] tracking-[-0.035em] text-text-primary">
            谱系
            <br />
            <em
              className="italic font-light text-accent"
              style={{ fontFeatureSettings: "'ss01'" }}
            >
              丛林
            </em>
          </h1>

          <p className="font-content text-lg lg:text-xl leading-[1.55] text-text-secondary max-w-lg font-light">
            <em className="italic text-text-primary">A living thicket of thought.</em>
            <span className="block mt-2">
              一处可并、可分、可反思的知识网络 —— 每一条观点都带出它的来路与去向。
            </span>
          </p>
        </div>

        {/* Footer index — three movements */}
        <dl className="relative z-10 grid grid-cols-3 gap-10 max-w-2xl animate-slide-up" style={{ animationDelay: "240ms", animationFillMode: "both" }}>
          {([
            ["01", "Merge", "并入共识"],
            ["02", "Branch", "分出支线"],
            ["03", "Reflect", "反思修正"],
          ] as const).map(([n, en, zh]) => (
            <div key={n} className="space-y-2 border-l border-accent/25 pl-4">
              <dt className={clsx(metaLabel, "text-accent flex items-baseline gap-2")}>
                <span className="font-bold tabular-nums">{n}</span>
                <span>{en}</span>
              </dt>
              <dd className="font-content text-[17px] text-text-secondary">
                {zh}
              </dd>
            </div>
          ))}
        </dl>
      </aside>

      {/* ═══ RIGHT — form panel ═══ */}
      <section className="flex-1 lg:flex-[3] bg-bg-elevated relative flex items-center justify-center p-8 sm:p-12 lg:p-16 xl:p-20">
        <div
          className="w-full max-w-[420px] space-y-10 animate-slide-up"
          style={{ animationDelay: "60ms", animationFillMode: "both" }}
        >
          {/* Mobile-only brand */}
          <div className="md:hidden space-y-1.5">
            <h1>
              <WordMark className="block text-[22px] leading-none" />
            </h1>
            <div className="font-mono text-[10px] tracking-[0.18em] text-text-tertiary">
              A thinking thicket · in threads
            </div>
          </div>

          {/* Heading */}
          <header className="space-y-3">
            <h2 className="font-content text-[40px] lg:text-[48px] leading-[1.0] tracking-[-0.025em] text-text-primary">
              {mode === "login" ? (
                <>
                  Welcome <em className="italic text-accent">back</em>.
                </>
              ) : (
                <>
                  Begin a <em className="italic text-accent">thread</em>.
                </>
              )}
            </h2>
            <p className="font-content italic text-text-secondary text-base leading-[1.5]">
              {mode === "login"
                ? "使用已有账号进入图谱。"
                : "第一次使用？注册一个账号即可。"}
            </p>
          </header>

          {/* Mode tabs — two-line labels, underline for active */}
          <div role="tablist" className="flex gap-10 border-b border-border-default">
            {(
              [
                ["login", "登录", "Sign in"],
                ["register", "注册", "Register"],
              ] as const
            ).map(([v, zh, en]) => {
              const active = mode === v;
              return (
                <button
                  key={v}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  onClick={() => {
                    setMode(v);
                    setError(null);
                  }}
                  className={clsx(
                    "relative pb-3 text-left transition-colors",
                    active
                      ? "text-text-primary"
                      : "text-text-tertiary hover:text-text-secondary",
                  )}
                >
                  <span className={clsx("block", metaLabel)}>
                    {en}
                  </span>
                  <span
                    className={clsx(
                      "block font-content text-base mt-0.5",
                      active ? "italic" : "",
                    )}
                  >
                    {zh}
                  </span>
                  {active && (
                    <span className="absolute left-0 right-0 -bottom-px h-[2px] bg-accent" />
                  )}
                </button>
              );
            })}
          </div>

          {/* Form */}
          <form onSubmit={handleSubmit} className="space-y-7">
            <Field
              label="Username · 用户名"
              value={username}
              onChange={setUsername}
              placeholder="alice"
              autoComplete="username"
              required
            />

            {mode === "register" && (
              <Field
                label="Display name · 显示名称"
                value={displayName}
                onChange={setDisplayName}
                placeholder="Alice"
              />
            )}

            <Field
              label="Password · 密码"
              type="password"
              value={password}
              onChange={setPassword}
              placeholder="至少 8 位"
              autoComplete={mode === "login" ? "current-password" : "new-password"}
              required
            />

            {error && (
              <div className="relative border-l-2 border-danger pl-4 py-2 bg-danger/5">
                <div className={clsx(metaLabel, "text-danger mb-1")}>
                  Error / 错误
                </div>
                <div className="font-content text-sm text-text-primary leading-[1.5]">
                  {error}
                </div>
              </div>
            )}

            <button
              type="submit"
              disabled={submitting}
              className={clsx(
                "group relative w-full flex items-center justify-between border border-accent-deep bg-accent-deep text-bg-primary py-[14px] px-5 transition-all",
                submitting
                  ? "cursor-wait opacity-70"
                  : "cursor-pointer hover:bg-accent hover:border-accent",
              )}
            >
              <span className="font-mono text-[12px] tracking-[0.06em]">
                {submitting
                  ? "Authenticating…"
                  : mode === "login"
                    ? "Enter the thicket"
                    : "Create account"}
              </span>
              <span className="flex items-center gap-3">
                <span className="font-content italic text-sm">
                  {submitting
                    ? "稍候"
                    : mode === "login"
                      ? "进入丛林"
                      : "创建并登录"}
                </span>
                <span
                  className="inline-block transition-transform duration-300 group-hover:translate-x-1"
                  aria-hidden
                >
                  ↗
                </span>
              </span>
            </button>
          </form>

          {/* Footer meta — like a journal colophon */}
          <footer
            className={clsx(
              "border-t border-border-default pt-6 flex items-center justify-between text-text-tertiary",
              metaLabel,
            )}
          >
            <span className="flex items-center gap-1.5">
              <span className="inline-block w-1 h-1 rounded-full bg-success" />
              Secure · JWT / ES256
            </span>
            <span>{mode === "login" ? "Returning member" : "New thread"}</span>
          </footer>
        </div>
      </section>
    </main>
  );
}
