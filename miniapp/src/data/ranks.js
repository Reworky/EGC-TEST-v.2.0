// Полная шкала рангов — дублирует UserService.LEVEL_TIERS / SinkShopService.getMonthlyLimit на бэкенде,
// т.к. отдельного API для списка всех тиров нет. Используется в ProfilePage и WalletPage.
export const RANKS_DATA = [
  { number: 1, name: 'Новичок',               minXp: 0,       bonus: 0,  limit: 10000 },
  { number: 2, name: 'Игрок',                minXp: 1000,    bonus: 5,  limit: 25000 },
  { number: 3, name: 'Ветеран',               minXp: 5000,    bonus: 10, limit: 50000 },
  { number: 4, name: 'Элита',                 minXp: 15000,   bonus: 15, limit: 80000 },
  { number: 5, name: 'Легенда',               minXp: 35000,   bonus: 20, limit: 100000 },
  { number: 6, name: 'Герой EXPERIENCE',      minXp: 75000,   bonus: 25, limit: 150000 },
  { number: 7, name: 'Чемпион EXPERIENCE',    minXp: 150000,  bonus: 30, limit: 150000 },
  { number: 8, name: 'Амбассадор EXPERIENCE', minXp: 300000,  bonus: 50, limit: 150000 },
];

export function getLevelFromXp(xp) {
  let current = RANKS_DATA[0];
  for (const tier of RANKS_DATA) {
    if (xp >= tier.minXp) current = tier;
  }
  return current.number;
}
