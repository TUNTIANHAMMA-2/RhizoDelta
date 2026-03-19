import { BrowserRouter, Routes, Route } from "react-router-dom";
import { GraphWorkspace } from "./components/GraphWorkspace";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<GraphWorkspace />} />
      </Routes>
    </BrowserRouter>
  );
}
