import { useEffect, useRef, useState } from "react";
import clsx from "clsx";
import { uploadAvatar, deleteAvatar, getMyProfile } from "../../api/profile";
import { useAuthStore } from "../../stores/authStore";
import { metaLabel } from "../../lib/typography";

export function AvatarUpload() {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const displayName = useAuthStore((s) => s.displayName);
  const username = useAuthStore((s) => s.username);

  // 进入设置页时拉取一次 profile —— 否则用户已上传的头像不会回显，
  // 直到用户重新触发上传操作为止。
  useEffect(() => {
    let cancelled = false;
    getMyProfile()
      .then((p) => {
        if (!cancelled) setAvatarUrl(p.avatar_url);
      })
      .catch(() => {
        // 静默：失败时退化为占位首字母，不打断设置页加载
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);
    setUploading(true);
    try {
      const profile = await uploadAvatar(file);
      setAvatarUrl(profile.avatar_url);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploading(false);
    }
  };

  const handleDelete = async () => {
    setError(null);
    setUploading(true);
    try {
      await deleteAvatar();
      setAvatarUrl(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Delete failed");
    } finally {
      setUploading(false);
    }
  };

  const initials = (displayName ?? username ?? "?")[0]?.toUpperCase() ?? "?";
  const altText = `${displayName ?? username ?? "User"} avatar`;

  return (
    <div className="flex flex-col items-center gap-4">
      <div className="relative">
        {avatarUrl ? (
          <img
            src={avatarUrl}
            alt={altText}
            className="w-24 h-24 rounded-full object-cover border-2 border-border-default"
          />
        ) : (
          <div className="w-24 h-24 rounded-full bg-bg-hover border-2 border-border-default flex items-center justify-center">
            <span className="font-content text-3xl text-text-secondary">
              {initials}
            </span>
          </div>
        )}
        {uploading && (
          <div className="absolute inset-0 rounded-full bg-bg-canvas/60 flex items-center justify-center">
            <div className="spinner" />
          </div>
        )}
      </div>

      <input
        ref={fileInputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        onChange={handleFileChange}
        className="hidden"
      />

      <div className="flex gap-3">
        <button
          type="button"
          onClick={() => fileInputRef.current?.click()}
          disabled={uploading}
          className={clsx(
            "px-4 py-2 border border-border-default text-sm hover:border-accent transition-colors",
            uploading && "opacity-50 cursor-not-allowed",
          )}
        >
          {avatarUrl ? "Change" : "Upload"}
        </button>
        {avatarUrl && (
          <button
            type="button"
            onClick={handleDelete}
            disabled={uploading}
            className={clsx(
              "px-4 py-2 border border-border-default text-sm text-text-secondary hover:text-text-primary hover:border-border-strong transition-colors",
              uploading && "opacity-50 cursor-not-allowed",
            )}
          >
            Remove
          </button>
        )}
      </div>

      {error && (
        <p className={clsx(metaLabel, "text-red-500")}>{error}</p>
      )}

      <p className={clsx(metaLabel, "text-text-tertiary text-center")}>
        JPEG, PNG, or WebP. Max 2 MB.
      </p>
    </div>
  );
}
