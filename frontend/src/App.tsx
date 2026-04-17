import { useEffect } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { GraphWorkspace } from "./components/GraphWorkspace";
import { LoginPage } from "./components/auth/LoginPage";
import { useAuthStore } from "./stores/authStore";

function RequireAuth() {
  const token = useAuthStore((s) => s.token);
  const isVerifying = useAuthStore((s) => s.isVerifying);
  const verifyToken = useAuthStore((s) => s.verifyToken);

  useEffect(() => {
    verifyToken();
  }, [verifyToken]);

  if (isVerifying) {
    return (
      <div style={{ display: "flex", justifyContent: "center", alignItems: "center", height: "100vh" }}>
        <div className="spinner" aria-label="Verifying session…" />
      </div>
    );
  }

  return token ? <GraphWorkspace /> : <Navigate to="/login" replace />;
}

function PublicOnlyRoute() {
  const token = useAuthStore((s) => s.token);
  return token ? <Navigate to="/" replace /> : <LoginPage />;
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<RequireAuth />} />
        <Route path="/login" element={<PublicOnlyRoute />} />
      </Routes>
    </BrowserRouter>
  );
}
