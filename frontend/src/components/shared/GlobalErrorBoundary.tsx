import { Component } from "react";
import type { ReactNode } from "react";
import { metaLabel } from "../../lib/typography";
import clsx from "clsx";

interface Props {
  children?: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class GlobalErrorBoundary extends Component<Props, State> {
  public state: State = {
    hasError: false,
    error: null,
  };

  public static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  public componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error("Uncaught error:", error, errorInfo);
  }

  public render() {
    if (this.state.hasError) {
      return (
        <div className="flex flex-col items-center justify-center min-h-screen bg-bg-canvas px-6">
          <div className="text-center max-w-md space-y-6 p-8 bg-bg-elevated border border-border-default shadow-sm">
            <h2 className="text-accent-deep text-lg font-semibold">Something went wrong</h2>
            <p className="text-text-secondary font-content italic">
              {this.state.error?.message || "An unexpected error occurred."}
            </p>
            <button
              type="button"
              onClick={() => window.location.reload()}
              className={clsx(
                metaLabel,
                "px-6 py-2 bg-text-primary text-bg-primary hover:bg-text-secondary transition-colors"
              )}
            >
              Reload Page
            </button>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
