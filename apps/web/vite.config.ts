import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
const proxy = {
  "/api": { target: process.env.CORE_URL || "http://localhost:8080" },
  "/login-desktop": {
    target: process.env.LOGIN_DESKTOP_URL || "http://localhost:6080",
    ws: true,
    rewrite: (path: string) => path.replace(/^\/login-desktop/, ""),
  },
  "/websockify": {
    target: process.env.LOGIN_DESKTOP_URL || "http://localhost:6080",
    ws: true,
  },
};
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy,
  },
  preview: { proxy },
});
