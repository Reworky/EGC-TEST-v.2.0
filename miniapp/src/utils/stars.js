/** Открывает Telegram Stars-инвойс по ссылке (см. getStarsInvoiceLink в api/client.js) прямо
 * поверх мини-аппа через нативный Telegram.WebApp.openInvoice, без ухода в чат бота. Резолвится
 * финальным статусом Telegram: 'paid' | 'cancelled' | 'failed' | 'pending'. */
export function openStarsInvoice(url) {
  return new Promise((resolve, reject) => {
    const tg = window.Telegram?.WebApp;
    if (!tg?.openInvoice) {
      reject(new Error('Обновите Telegram, чтобы покупать за Stars.'));
      return;
    }
    tg.openInvoice(url, status => resolve(status));
  });
}
