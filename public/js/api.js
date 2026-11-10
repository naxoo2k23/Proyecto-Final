/**
 * PlayMatch API Client - Conexión REST asíncrona con Backend Java 21 + SQLite JDBC
 */
const API = {
  baseUrl: '',

  async requestJson(url, options = {}) {
    const res = await fetch(`${this.baseUrl}${url}`, options);
    let data = null;
    try {
      data = await res.json();
    } catch (e) {
      data = null;
    }
    return { ok: res.ok, status: res.status, data };
  },

  async getHealth() {
    try {
      const { data } = await this.requestJson('/api/health');
      return data;
    } catch (e) {
      console.error('Health check error:', e);
      return null;
    }
  },

  async getStats() {
    const { data } = await this.requestJson('/api/stats');
    return data;
  },

  // JUEGOS DISPONIBLES
  async getGames(includeAll = false) {
    const q = includeAll ? '?all=true' : '';
    const { data } = await this.requestJson(`/api/games${q}`);
    return Array.isArray(data) ? data : [];
  },

  async createGame(gameData) {
    return this.requestJson('/api/games', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(gameData)
    });
  },

  // USUARIOS REGISTRADOS
  async getUsers(query = '') {
    const params = query ? `?q=${encodeURIComponent(query)}` : '';
    const { data } = await this.requestJson(`/api/users${params}`);
    return Array.isArray(data) ? data : [];
  },

  async updateAccount(accountData) {
    return this.requestJson('/api/users/account', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(accountData)
    });
  },

  // JUGADORES (LFG)
  async getPlayers(game = 'all', toxic = '', q = '') {
    const params = new URLSearchParams();
    if (game && game !== 'all') params.append('game', game);
    if (toxic) params.append('toxic', toxic);
    if (q) params.append('q', q);
    const qs = params.toString();
    const { data } = await this.requestJson(`/api/players${qs ? '?' + qs : ''}`);
    return Array.isArray(data) ? data : [];
  },

  async updateProfile(profileData) {
    return this.requestJson('/api/players/profile', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(profileData)
    });
  },

  // TORNEOS
  async getTournaments() {
    const { data } = await this.requestJson('/api/tournaments');
    return Array.isArray(data) ? data : [];
  },

  async getTournament(id) {
    const { data } = await this.requestJson(`/api/tournaments/${id}`);
    return data;
  },

  async createTournament(tournamentData) {
    return this.requestJson('/api/tournaments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(tournamentData)
    });
  },

  async joinTournament(id, teamName, captain = '') {
    return this.requestJson(`/api/tournaments/${id}/join`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ teamName, captain })
    });
  },

  // CHAT MULTICANAL
  async getChatMessages(channel = 'global') {
    const { data } = await this.requestJson(`/api/chat/messages?channel=${encodeURIComponent(channel)}`);
    return Array.isArray(data) ? data : [];
  },

  async sendChatMessage(sender, content, channel = 'global') {
    return this.requestJson('/api/chat/messages', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sender, content, channel })
    });
  },

  // INVITACIONES DE PARTIDA
  async getMatchRequests(user = '') {
    const q = user ? `?user=${encodeURIComponent(user)}` : '';
    const { data } = await this.requestJson(`/api/match/requests${q}`);
    return Array.isArray(data) ? data : [];
  },

  async sendMatchRequest(sender, receiver, game, message) {
    return this.requestJson('/api/match/request', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sender, receiver, game, message })
    });
  },

  async respondMatchRequest(requestId, status) {
    return this.requestJson('/api/match/respond', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ requestId, status })
    });
  },

  // AUTENTICACIÓN
  async registerUser(username, email, password, telefono = '', game = 'valorant') {
    const { data } = await this.requestJson('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, email, password, telefono, game })
    });
    return data;
  },

  async loginUser(username, password) {
    const { data } = await this.requestJson('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    return data;
  }
};
