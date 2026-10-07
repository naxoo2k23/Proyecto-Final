/**
 * PlayMatch API Client - Conexión con el Backend Java REST
 */
const API = {
  baseUrl: '',

  async getHealth() {
    try {
      const res = await fetch(`${this.baseUrl}/api/health`);
      return await res.json();
    } catch (e) {
      console.error('Health check error:', e);
      return null;
    }
  },

  async getPlayers(game = 'all', toxic = '') {
    const params = new URLSearchParams();
    if (game && game !== 'all') params.append('game', game);
    if (toxic) params.append('toxic', toxic);
    const res = await fetch(`${this.baseUrl}/api/players?${params.toString()}`);
    return await res.json();
  },

  async updateProfile(profileData) {
    const res = await fetch(`${this.baseUrl}/api/players/profile`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(profileData)
    });
    return await res.json();
  },

  async getTournaments() {
    const res = await fetch(`${this.baseUrl}/api/tournaments`);
    return await res.json();
  },

  async getTournament(id) {
    const res = await fetch(`${this.baseUrl}/api/tournaments/${id}`);
    return await res.json();
  },

  async joinTournament(id, teamName) {
    const res = await fetch(`${this.baseUrl}/api/tournaments/${id}/join`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ teamName })
    });
    return await res.json();
  },

  async getChatMessages() {
    const res = await fetch(`${this.baseUrl}/api/chat/messages`);
    return await res.json();
  },

  async sendChatMessage(sender, content, channel = 'global') {
    const res = await fetch(`${this.baseUrl}/api/chat/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sender, content, channel })
    });
    return await res.json();
  },

  async sendMatchRequest(sender, receiver, game, message) {
    const res = await fetch(`${this.baseUrl}/api/match/request`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sender, receiver, game, message })
    });
    return await res.json();
  },

  async registerUser(username, email, password) {
    const res = await fetch(`${this.baseUrl}/api/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, email, password })
    });
    return await res.json();
  },

  async loginUser(username, password) {
    const res = await fetch(`${this.baseUrl}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    return await res.json();
  }
};
