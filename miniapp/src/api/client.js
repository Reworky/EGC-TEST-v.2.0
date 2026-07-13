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
