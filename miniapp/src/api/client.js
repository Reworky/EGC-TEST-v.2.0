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
