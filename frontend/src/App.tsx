import { useEffect } from "react";
import { BrowserRouter, Navigate, Outlet, Route, Routes } from "react-router-dom";
import { GraphWorkspace } from "./components/GraphWorkspace";
import { HomePage } from "./components/home/HomePage";
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
      <div className="flex justify-center items-center h-screen">
        <div className="spinner" aria-label="Verifying session…" />
      </div>
    );
  }

  if (!token) return <Navigate to="/login" replace />;
  return <Outlet />;
}

function PublicOnlyRoute() {
  const token = useAuthStore((s) => s.token);
  return token ? <Navigate to="/" replace /> : <LoginPage />;
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<PublicOnlyRoute />} />
        <Route element={<RequireAuth />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/workspace" element={<GraphWorkspace />} />
          <Route path="/workspace/:rhizomeId" element={<GraphWorkspace />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
