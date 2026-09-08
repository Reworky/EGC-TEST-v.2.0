/** Переиспользуемый баннер партнёра (не квест — обычная реклама без награды EXC; для нашей
 * площадки этот тип трафика разрешён без согласования, в отличие от мотивированного/квестового).
 * Картинка и ссылка отдаются напрямую с сервера рекламной сети (Admitad и т.п.) — своих ассетов
 * не нужно. Маркировка «Реклама · erid» — обязательна по 38-ФЗ. */
export default function AdBanner({ img, link, alt, erid, style }) {
  function handleClick() {
    const tg = window.Telegram?.WebApp;
    if (tg) tg.openLink(link); else window.open(link, '_blank', 'noopener');
  }

  return (
    <div style={{ cursor: 'pointer', ...style }} onClick={handleClick}>
      <img src={img} alt={alt} style={{ width: '100%', height: 'auto', display: 'block', borderRadius: 12 }} />
      <div style={{ fontSize: 10, opacity: 0.5, marginTop: 4, textAlign: 'right' }}>
        Реклама{erid ? ` · erid: ${erid}` : ''}
      </div>
    </div>
  );
}
