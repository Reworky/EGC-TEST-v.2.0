export default function BorderBeamCard({ children, className = '', style = {} }) {
  return (
    <div className={`border-beam-card ${className}`} style={style}>
      {children}
    </div>
  );
}
