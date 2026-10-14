import type { ComponentPropsWithoutRef } from "react";

export function PageContainer({
  className = "",
  ...props
}: ComponentPropsWithoutRef<"div">) {
  return <div className={`page-container ${className}`} {...props} />;
}
