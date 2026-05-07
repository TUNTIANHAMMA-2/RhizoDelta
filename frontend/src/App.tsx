import { useEffect, lazy, Suspense } from "react";
import { BrowserRouter, Navigate, Outlet, Route, Routes } from "react-router-dom";
import { useAuthStore } from "./stores/authStore";
import { GlobalErrorBoundary } from "./components/shared/GlobalErrorBoundary";

const GraphWorkspace = lazy(() => import("./components/GraphWorkspace").then((m) => ({ default: m.GraphWorkspace })));
const HomePage = lazy(() => import("./components/home/HomePage").then((m) => ({ default: m.HomePage })));
const LoginPage = lazy(() => import("./components/auth/LoginPage").then((m) => ({ default: m.LoginPage })));
const SettingsPage = lazy(() => import("./components/settings/SettingsPage").then((m) => ({ default: m.SettingsPage })));

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
      <GlobalErrorBoundary>
        <Suspense
          fallback={
            <div className="flex justify-center items-center h-screen bg-bg-canvas">
              <div className="spinner" aria-label="Loading page…" />
            </div>
          }
        >
          <Routes>
            <Route path="/login" element={<PublicOnlyRoute />} />
            <Route element={<RequireAuth />}>
              <Route path="/" element={<HomePage />} />
              <Route path="/workspace" element={<GraphWorkspace />} />
              <Route path="/workspace/:rhizomeId" element={<GraphWorkspace />} />
              <Route path="/settings" element={<SettingsPage />} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </GlobalErrorBoundary>
    </BrowserRouter>
  );
}
