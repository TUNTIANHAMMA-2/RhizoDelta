import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { GraphWorkspace } from "./components/GraphWorkspace";
import { LoginPage } from "./components/auth/LoginPage";
import { useAuthStore } from "./stores/authStore";

function RequireAuth() {
  const token = useAuthStore((s) => s.token);
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
