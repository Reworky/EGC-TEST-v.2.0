export default function ShimmerButton({ children, onClick, disabled, style = {}, className = '' }) {
  return (
    <button
      className={`btn-shimmer ${className}`}
      onClick={onClick}
      disabled={disabled}
      style={style}
    >
      {children}
    </button>
  );
}
