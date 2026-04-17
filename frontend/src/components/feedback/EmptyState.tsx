export interface EmptyStateProps {
  message: string;
}

export function EmptyState({ message }: EmptyStateProps) {
  return (
    <div className="flex items-center justify-center px-4 py-8 font-ui text-sm text-text-tertiary text-center">
      {message}
    </div>
  );
}
