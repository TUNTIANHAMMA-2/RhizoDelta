import { HumanPostNode } from "./HumanPostNode";
import { ConsensusNode } from "./ConsensusNode";
import { ResultNode } from "./ResultNode";
import { ExpandPlaceholder } from "./ExpandPlaceholder";
import { VersionEdge } from "./VersionEdge";
import { AssociationEdge } from "./AssociationEdge";

export const nodeTypes = {
  humanPost: HumanPostNode,
  consensus: ConsensusNode,
  result: ResultNode,
  expandPlaceholder: ExpandPlaceholder,
};

export const edgeTypes = {
  versionEdge: VersionEdge,
  association: AssociationEdge,
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
