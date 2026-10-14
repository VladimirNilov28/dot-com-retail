import { ImageOff } from "lucide-react";

export function ImageUnavailable() {
  return (
    <div className="catalog-image" role="img" aria-label="Product image unavailable">
      <ImageOff size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
      <span className="text-xs">Image unavailable</span>
    </div>
  );
}
