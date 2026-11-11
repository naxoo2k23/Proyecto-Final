/**
 * PlayMatch API Client - Conexión REST asíncrona con Backend Java 21 + SQLite JDBC
 */
const API = {
  baseUrl: '',

  getAuthToken() {
    try {
      const saved = localStorage.getItem('playmatch_user') || sessionStorage.getItem('playmatch_user');
      if (saved) {
        const parsed = JSON.parse(saved);
        return parsed && parsed.token ? parsed.token : '';
      }
    } catch (e) {}
    return '';
  },

  async requestJson(url, options = {}) {
    const token = this.getAuthToken();
    const headers = Object.assign({}, options.headers || {});
    if (token && !headers.Authorization) {
      headers.Authorization = `Bearer ${token}`;
    }
    const res = await fetch(`${this.baseUrl}${url}`, Object.assign({}, options, { headers }));
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

  async getIntegrationsStatus() {
    const { data } = await this.requestJson('/api/integrations/status');
    return data;
  },

  async getPresence() {
    const { data } = await this.requestJson('/api/users/presence');
    return data && typeof data === 'object' ? data : {};
  },

  async updatePresence(username, status, activity = '') {
    return this.requestJson('/api/users/presence', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, status, activity })
    });
  },

  async getDiscordOAuthUrl(username) {
    return this.requestJson(`/api/users/discord/oauth-url?username=${encodeURIComponent(username)}`);
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

  async getMyAccount(username) {
    return this.requestJson(`/api/users/me?username=${encodeURIComponent(username)}`);
  },

  async updateAvatar(username, avatarUrl) {
    return this.requestJson('/api/users/avatar', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, avatarUrl })
    });
  },

  async requestPhoneVerification(username, telefono) {
    return this.requestJson('/api/users/phone/request-code', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, telefono })
    });
  },

  async verifyPhoneCode(username, code) {
    return this.requestJson('/api/users/phone/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, code })
    });
  },

  async connectDiscord(username, discordTag, discordId = '') {
    return this.requestJson('/api/users/discord/connect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, discordTag, discordId })
    });
  },

  async disconnectDiscord(username) {
    return this.requestJson('/api/users/discord/disconnect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username })
    });
  },

  // JUGADORES (LFG)
  async getPlayers(game = 'all', toxic = '', q = '', viewer = '') {
    const params = new URLSearchParams();
    if (game && game !== 'all') params.append('game', game);
    if (toxic) params.append('toxic', toxic);
    if (q) params.append('q', q);
    if (viewer) params.append('viewer', viewer);
    const qs = params.toString();
    const { data } = await this.requestJson(`/api/players${qs ? '?' + qs : ''}`);
    return Array.isArray(data) ? data : [];
  },

  async getPlayerDossier(username, viewer = '') {
    const params = new URLSearchParams();
    params.append('username', username);
    if (viewer) params.append('viewer', viewer);
    return this.requestJson(`/api/players/dossier?${params.toString()}`);
  },

  async getLeaderboard(viewer = '') {
    const q = viewer ? `?viewer=${encodeURIComponent(viewer)}` : '';
    const { data } = await this.requestJson(`/api/players/leaderboard${q}`);
    return Array.isArray(data) ? data : [];
  },

  async updateProfile(profileData) {
    return this.requestJson('/api/players/profile', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(profileData)
    });
  },

  async getRecommendations(username) {
    const { data } = await this.requestJson(`/api/recommendations?username=${encodeURIComponent(username)}`);
    return data;
  },

  async ratePlayer(raterUsername, ratedUsername, stars, comment = '') {
    return this.requestJson('/api/players/rate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ raterUsername, ratedUsername, stars, comment })
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

  async checkInTournament(id, teamName, actor) {
    return this.requestJson(`/api/tournaments/${id}/checkin`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ teamName, actor })
    });
  },

  async generateTournamentBracket(id, actor) {
    return this.requestJson(`/api/tournaments/${id}/generate-bracket`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ actor })
    });
  },

  async submitTournamentMatchResult(id, matchId, actor, scoreTeam1, scoreTeam2) {
    return this.requestJson(`/api/tournaments/${id}/match-result`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ matchId, actor, scoreTeam1, scoreTeam2 })
    });
  },

  // LOBBIES CON CUPOS Y CHAT GRUPAL
  async getLobbies(game = 'all', viewer = '') {
    const params = new URLSearchParams();
    if (game && game !== 'all') params.append('game', game);
    if (viewer) params.append('viewer', viewer);
    const qs = params.toString();
    const { data } = await this.requestJson(`/api/lobbies${qs ? '?' + qs : ''}`);
    return Array.isArray(data) ? data : [];
  },

  async createLobby(lobbyData) {
    return this.requestJson('/api/lobbies', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(lobbyData)
    });
  },

  async joinLobby(lobbyId, username, roleNote = '') {
    return this.requestJson('/api/lobbies/join', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ lobbyId, username, roleNote })
    });
  },

  async leaveLobby(lobbyId, username) {
    return this.requestJson('/api/lobbies/leave', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ lobbyId, username })
    });
  },

  // MODERACIÓN (BLOQUEOS Y REPORTES)
  async getBlockedUsers(username) {
    const { data } = await this.requestJson(`/api/moderation/blocks?username=${encodeURIComponent(username)}`);
    return Array.isArray(data) ? data : [];
  },

  async blockUser(blocker, blocked) {
    return this.requestJson('/api/moderation/block', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ blocker, blocked })
    });
  },

  async unblockUser(blocker, blocked) {
    return this.requestJson('/api/moderation/unblock', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ blocker, blocked })
    });
  },

  async reportPlayer(reporter, reported, reason, details = '') {
    return this.requestJson('/api/moderation/report', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reporter, reported, reason, details })
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

  // AMISTADES Y MENSAJES DIRECTOS ESTILO INSTAGRAM (DM)
  async getFriendsInbox(username) {
    const { ok, data } = await this.requestJson(`/api/friends?username=${encodeURIComponent(username)}`);
    return ok ? data : null;
  },

  async sendFriendRequest(sender, receiver) {
    return this.requestJson('/api/friends/request', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sender, receiver })
    });
  },

  async respondFriendRequest(requestId, username, status) {
    return this.requestJson('/api/friends/respond', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ requestId, username, status })
    });
  },

  async removeFriend(username, friendUsername) {
    return this.requestJson('/api/friends/remove', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, friendUsername })
    });
  },

  async getDirectMessages(user, friend) {
    return this.requestJson(`/api/chat/dm/messages?user=${encodeURIComponent(user)}&friend=${encodeURIComponent(friend)}`);
  },

  async sendDirectMessage(sender, receiver, content, opts = {}) {
    const payload = Object.assign({ sender, receiver, content }, opts || {});
    return this.requestJson('/api/chat/dm/messages', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
  },

  async sendDmTyping(sender, receiver, typing) {
    return this.requestJson('/api/chat/dm/typing', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sender, receiver, typing })
    });
  },

  async markDirectMessagesRead(username, friend) {
    return this.requestJson('/api/chat/dm/read', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, friend })
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
