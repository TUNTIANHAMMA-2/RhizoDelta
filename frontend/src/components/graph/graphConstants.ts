import { HumanPostNode } from "./HumanPostNode";
import { ConsensusNode } from "./ConsensusNode";
import { ResultNode } from "./ResultNode";
import { VersionEdge } from "./VersionEdge";

export const nodeTypes = {
  humanPost: HumanPostNode,
  consensus: ConsensusNode,
  result: ResultNode,
};

export const edgeTypes = {
  versionEdge: VersionEdge,
};

export const MINIMAP_NODE_COLOR = (node: { type?: string }) => {
  switch (node.type) {
    case "humanPost":
      return "#2E7CF6";
    case "consensus":
      return "#9B59B6";
    case "result":
      return "#0D9488";
    default:
      return "#B4B4B0";
  }
};
