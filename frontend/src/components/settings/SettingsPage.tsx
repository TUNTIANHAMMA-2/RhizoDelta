import { useEffect, useState } from "react";
import clsx from "clsx";
import { getMyProfile, updateProfile } from "../../api/profile";
import { useAuthStore } from "../../stores/authStore";
import { metaLabel } from "../../lib/typography";
import { AvatarUpload } from "./AvatarUpload";
import { useNavigate } from "react-router-dom";
import type { UserProfile } from "../../api/types";
import { RadiusModeToggle } from "../chrome/RadiusModeToggle";

export function SettingsPage() {
  const navigate = useNavigate();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [displayName, setDisplayName] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const username = useAuthStore((s) => s.username);

  useEffect(() => {
    getMyProfile()
      .then((p) => {
        setProfile(p);
        setDisplayName(p.display_name ?? "");
      })
      .finally(() => setLoading(false));
  }, []);

  const handleSaveDisplayName = async () => {
    setSaving(true);
    setMessage(null);
    try {
      const updated = await updateProfile({ display_name: displayName || null });
      setProfile(updated);
      setMessage("Saved");
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Save failed");
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center items-center h-64">
        <div className="spinner" />
      </div>
    );
  }

  return (
    <div className="max-w-lg mx-auto py-12 px-6 space-y-10">
      <div>
        <button
          onClick={() => navigate("/")}
          className="mb-6 flex items-center text-text-tertiary hover:text-text-primary transition-colors text-sm font-ui"
        >
          ← Back to Home
        </button>
        <h1 className="font-content text-3xl tracking-[-0.02em] text-text-primary mb-2">
          Settings
        </h1>
        <p className="font-content italic text-text-secondary text-sm">
          设置
        </p>
      </div>

      <section className="space-y-4">
        <h2
          className={clsx(
            metaLabel,
            "text-text-tertiary uppercase tracking-[0.18em]",
          )}
        >
          Appearance
        </h2>
        <div className="space-y-2">
          <label className={clsx(metaLabel, "block text-text-secondary")}>
            Corner Radius
          </label>
          <div>
            <RadiusModeToggle />
          </div>
        </div>
      </section>

      <section className="space-y-4">
        <h2
          className={clsx(
            metaLabel,
            "text-text-tertiary uppercase tracking-[0.18em]",
          )}
        >
          Avatar
        </h2>
        <AvatarUpload />
      </section>

      <section className="space-y-4">
        <h2
          className={clsx(
            metaLabel,
            "text-text-tertiary uppercase tracking-[0.18em]",
          )}
        >
          Profile
        </h2>

        <div className="space-y-2">
          <label className={clsx(metaLabel, "block text-text-secondary")}>
            Username
          </label>
          <input
            type="text"
            value={username ?? ""}
            disabled
            className="w-full px-3 py-2 border border-border-default bg-bg-canvas text-text-tertiary text-sm"
          />
        </div>

        <div className="space-y-2">
          <label className={clsx(metaLabel, "block text-text-secondary")}>
            Display Name
          </label>
          <input
            type="text"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="Your display name"
            className="w-full px-3 py-2 border border-border-default bg-bg-primary text-text-primary text-sm focus:outline-none focus:border-accent transition-colors"
          />
        </div>

        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={handleSaveDisplayName}
            disabled={saving}
            className={clsx(
              "px-4 py-2 bg-accent-deep text-bg-primary text-sm hover:bg-accent transition-colors",
              saving && "opacity-50 cursor-not-allowed",
            )}
          >
            {saving ? "Saving..." : "Save"}
          </button>
          {message && (
            <span
              className={clsx(
                metaLabel,
                message === "Saved" ? "text-accent" : "text-red-500",
              )}
            >
              {message}
            </span>
          )}
        </div>
      </section>
    </div>
  );
}
