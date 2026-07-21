import axios from 'axios';

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8090';

const api = axios.create({ baseURL: BASE_URL });

api.interceptors.request.use(cfg => {
  const token = localStorage.getItem('egc_token');
  if (token) cfg.headers.Authorization = `Bearer ${token}`;
  return cfg;
});

export async function authMiniApp(initData) {
  const { data } = await api.post('/api/auth/miniapp', { initData });
  localStorage.setItem('egc_token', data.token);
  return data;
}

export async function getProfile() {
  const { data } = await api.get('/api/profile');
  return data;
}

export async function getAvatarUrl() {
  const { data } = await api.get('/api/profile/avatar', { responseType: 'blob' });
  return URL.createObjectURL(data);
}

export async function getSponsoredQuests() {
  const { data } = await api.get('/api/quests/sponsored');
  return data;
}

export async function getQuests(game, category) {
  const params = {};
  if (game) params.game = game;
  if (category) params.category = category;
  const { data } = await api.get('/api/quests', { params });
  return data;
}

export async function getGames() {
  const { data } = await api.get('/api/quests/games');
  return data;
}

export async function getQuestDetail(id) {
  const { data } = await api.get(`/api/quests/${id}`);
  return data;
}

export async function takeQuest(id) {
  const { data } = await api.post(`/api/quests/${id}/take`);
  return data;
}

export async function submitQuestReport(id, { photo, externalLink, comment }) {
  const form = new FormData();
  if (photo) form.append('photo', photo);
  if (externalLink) form.append('externalLink', externalLink);
  if (comment) form.append('comment', comment);
  const { data } = await api.post(`/api/quests/${id}/report`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return data;
}

export async function getMyQuests() {
  const { data } = await api.get('/api/quests/mine');
  return data;
}

export async function cancelMyQuest(submissionId) {
  const { data } = await api.post(`/api/quests/mine/${submissionId}/cancel`);
  return data;
}

export async function getShopItems() {
  const { data } = await api.get('/api/shop/items');
  return data;
}

export async function getShopStats() {
  const { data } = await api.get('/api/shop/stats');
  return data;
}

export async function purchaseItem(id, userData) {
  const { data } = await api.post(`/api/shop/items/${id}/purchase`, { userData });
  return data;
}

export async function getMyRewards() {
  const { data } = await api.get('/api/profile/rewards');
  return data;
}

export async function getLeaderboard(type) {
  const { data } = await api.get('/api/leaderboard', { params: { type } });
  return data;
}

export async function getPerksState() {
  const { data } = await api.get('/api/perks');
  return data;
}

export async function purchasePerk(key) {
  const { data } = await api.post('/api/perks/purchase', { key });
  return data;
}

export async function sendGiftBoost(nickname) {
  const { data } = await api.post('/api/perks/gift', { nickname });
  return data;
}

export async function sendExcTransfer(nickname, amount) {
  const { data } = await api.post('/api/perks/transfer', { nickname, amount });
  return data;
}

export async function getReferrals() {
  const { data } = await api.get('/api/profile/referrals');
  return data;
}

export async function getTournament() {
  const { data } = await api.get('/api/tournament');
  return data;
}

export async function joinTournament(id) {
  const { data } = await api.post(`/api/tournament/${id}/join`);
  return data;
}

export async function getTournamentLeaderboard(id) {
  const { data } = await api.get(`/api/tournament/${id}/leaderboard`);
  return data;
}

export async function getPolls() {
  const { data } = await api.get('/api/polls');
  return data;
}

export async function votePoll(id, optionIndex) {
  const { data } = await api.post(`/api/polls/${id}/vote`, { optionIndex });
  return data;
}

export async function getSupportTickets() {
  const { data } = await api.get('/api/support/tickets');
  return data;
}

export async function createSupportTicket({ text, photo }) {
  const form = new FormData();
  if (text) form.append('text', text);
  if (photo) form.append('photo', photo);
  const { data } = await api.post('/api/support/tickets', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return data;
}

export async function getWallet() {
  const { data } = await api.get('/api/wallet');
  return data;
}

export async function claimDailyBonus() {
  const { data } = await api.post('/api/wallet/daily-bonus');
  return data;
}

export async function getTonQuote(amount) {
  const { data } = await api.get('/api/wallet/ton-quote', { params: { amount } });
  return data;
}

export async function withdrawRub(amount, requisites) {
  const { data } = await api.post('/api/wallet/withdraw/rub', { amount, requisites });
  return data;
}

export async function withdrawTon(amount, walletAddress) {
  const { data } = await api.post('/api/wallet/withdraw/ton', { amount, walletAddress });
  return data;
}

export async function getWithdrawals() {
  const { data } = await api.get('/api/wallet/withdrawals');
  return data;
}

export async function cancelReward(id) {
  const { data } = await api.post(`/api/profile/rewards/${id}/cancel`);
  return data;
}

export async function getBattlePass() {
  const { data } = await api.get('/api/battlepass');
  return data;
}

export async function purchaseBattlePass(seasonId) {
  const { data } = await api.post(`/api/battlepass/${seasonId}/purchase`);
  return data;
}

export async function getMySquad() {
  const { data } = await api.get('/api/squads/me');
  return data;
}

export async function createSquad(name) {
  const { data } = await api.post('/api/squads/create', { name });
  return data;
}

export async function joinSquad(code) {
  const { data } = await api.post('/api/squads/join', { code });
  return data;
}

export async function leaveSquad() {
  await api.post('/api/squads/leave');
}

export async function disbandSquad() {
  await api.post('/api/squads/disband');
}

export async function kickSquadMember(memberTelegramId) {
  await api.post(`/api/squads/kick/${memberTelegramId}`);
}

export async function getSquadLeaderboard() {
  const { data } = await api.get('/api/squads/leaderboard');
  return data;
}

export async function getWheelStatus() {
  const { data } = await api.get('/api/wheel');
  return data;
}

export async function spinWheel() {
  const { data } = await api.post('/api/wheel/spin');
  return data;
}
