import { useEffect, useState } from "react";
import { Store } from "lucide-react";
import { platformImageUrl } from "./PlatformImage";
import type { Shop } from "../types";

export default function ShopAvatar({ shop }: { shop: Shop }) {
  const url = platformImageUrl(shop.avatar_url);
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [url]);
  return (
    <span className="shop-avatar" aria-hidden="true">
      {url && !failed ? (
        <img
          src={url}
          alt=""
          referrerPolicy="no-referrer"
          onError={() => setFailed(true)}
        />
      ) : (
        <Store size={18} />
      )}
    </span>
  );
}
