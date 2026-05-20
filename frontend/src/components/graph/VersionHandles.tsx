/* eslint-disable react-refresh/only-export-components */
import { memo } from "react";
import { Handle, Position } from "@xyflow/react";

export const HIDDEN_HANDLE_STYLE = {
  opacity: 0,
  pointerEvents: "none",
} as const;

export const VersionHandles = memo(function VersionHandles() {
  return (
    <>
      <Handle id="source-center" type="source" position={Position.Top} style={{ top: '50%', left: '50%', opacity: 0, pointerEvents: 'none' }} />
      <Handle id="target-center" type="target" position={Position.Top} style={{ top: '50%', left: '50%', opacity: 0, pointerEvents: 'none' }} />
      <Handle id="source-top" type="source" position={Position.Top} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="source-right" type="source" position={Position.Right} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="source-bottom" type="source" position={Position.Bottom} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="source-left" type="source" position={Position.Left} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="target-top" type="target" position={Position.Top} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="target-right" type="target" position={Position.Right} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="target-bottom" type="target" position={Position.Bottom} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="target-left" type="target" position={Position.Left} style={HIDDEN_HANDLE_STYLE} />
    </>
  );
});
