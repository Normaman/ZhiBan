import { useEffect, useState } from "react";
import { ImageOff } from "lucide-react";

export function platformImageUrl(value?: string | null): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    const allowed = ["pddpic.com", "pinduoduo.com", "yangkeduo.com"].some(
      (domain) =>
        url.hostname === domain || url.hostname.endsWith(`.${domain}`),
    );
    return url.protocol === "https:" &&
      allowed &&
      !url.username &&
      !url.password &&
      !url.port
      ? url.href
      : null;
  } catch {
    return null;
  }
}

export default function PlatformImage({
  src,
  alt,
}: {
  src?: string | null;
  alt: string;
}) {
  const url = platformImageUrl(src);
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [url]);
  if (!url || failed)
    return (
      <div className="image-placeholder" role="img" aria-label="图片暂不可用">
        <ImageOff size={24} />
        <span>图片暂不可用</span>
      </div>
    );
  return (
    <a
      className="platform-image"
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      aria-label="查看原图"
    >
      <img
        src={url}
        alt={alt}
        loading="lazy"
        decoding="async"
        referrerPolicy="no-referrer"
        onError={() => setFailed(true)}
      />
    </a>
  );
}
