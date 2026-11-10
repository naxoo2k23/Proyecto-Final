/**
 * PlayMatch Frontend Application Logic
 * Conectado a Backend Java 21 REST + SQLite JDBC persistente.
 */

// Auth Guard en cliente: exige sesión activa
let storedUser = null;
try {
  const saved = localStorage.getItem('playmatch_user') || sessionStorage.getItem('playmatch_user');
  if (saved) storedUser = JSON.parse(saved);
} catch (e) {
  storedUser = null;
}

if (!storedUser) {
  window.location.replace('login.html');
}

const TAB_META = {
  lfg: { kicker: 'Arena Competitiva', title: 'Buscador de Jugadores (LFG)' },
  games: { kicker: 'Catálogo SQLite', title: 'Juegos Disponibles' },
  tournaments: { kicker: 'Competencias Oficiales', title: 'Torneos y Brackets' },
  chat: { kicker: 'Comunicación', title: 'Sala de Coordinación & Invitaciones' },
  profile: { kicker: 'Cuenta Verificada', title: 'Mi Perfil y Credenciales' },
  database: { kicker: 'Persistencia Relacional 3NF', title: 'Monitor Base de Datos (SQLite)' }
};

const state = {
  currentUser: storedUser,
  currentTab: 'lfg',
  selectedGameFilter: 'all',
  selectedToxicFilter: '',
  globalSearch: '',
  selectedTournamentId: 't-3',
  activeChannel: 'global',
  gamesCache: [],
  chatInterval: null
};

document.addEventListener('DOMContentLoaded', async () => {
  updateAuthUI();
  setupNavigation();
  setupChat();
  setupModals();
  setupProfileForm();
  setupDatabaseMonitor();

  await Promise.all([
    loadStats(),
    loadGames(),
    loadLfgPlayers(),
    loadTournaments(),
    loadProfileData(),
    loadInvites()
  ]);

  // Refresco periódico del chat e invitaciones cada 3.5s
  state.chatInterval = setInterval(() => {
    if (state.currentTab === 'chat') {
      fetchAndRenderChat();
      loadInvites();
    }
  }, 3500);
});

function updateAuthUI() {
  const navName = document.getElementById('nav-user-name');
  const navAvatar = document.getElementById('nav-user-avatar');
  const navRole = document.getElementById('nav-user-role');

  if (state.currentUser) {
    if (navName) navName.innerText = state.currentUser.username;
    if (navAvatar) navAvatar.src = state.currentUser.avatar || `https://api.dicebear.com/7.x/bottts/svg?seed=${encodeURIComponent(state.currentUser.username)}`;
    if (navRole) navRole.innerText = `${state.currentUser.role || 'GAMER'} · En línea`;
  }
}

// =========================================================================
// 1. NAVEGACIÓN, BÚSQUEDA GLOBAL Y TELEMETRÍA
// =========================================================================
function setupNavigation() {
  document.querySelectorAll('.nav-tab-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      switchTab(btn.dataset.tab);
    });
  });

  document.getElementById('btn-mobile-menu')?.addEventListener('click', () => {
    document.getElementById('app-sidebar')?.classList.toggle('open');
  });

  document.getElementById('game-filter')?.addEventListener('change', (e) => {
    state.selectedGameFilter = e.target.value;
    loadLfgPlayers();
  });

  document.getElementById('toxic-filter')?.addEventListener('change', (e) => {
    state.selectedToxicFilter = e.target.value;
    loadLfgPlayers();
  });

  let searchDebounce = null;
  document.getElementById('global-search-input')?.addEventListener('input', (e) => {
    state.globalSearch = e.target.value.trim();
    clearTimeout(searchDebounce);
    searchDebounce = setTimeout(() => {
      if (state.currentTab === 'lfg') {
        loadLfgPlayers();
      } else if (state.currentTab === 'database') {
        const dbSearch = document.getElementById('db-search-input');
        if (dbSearch) dbSearch.value = state.globalSearch;
        loadDatabaseExplorer();
      } else if (state.currentTab === 'games') {
        renderGamesGrid(state.gamesCache);
      }
    }, 220);
  });

  document.getElementById('btn-quick-invites')?.addEventListener('click', () => switchTab('chat'));
  document.getElementById('btn-quick-add-game')?.addEventListener('click', openAddGameModal);
  document.getElementById('btn-open-add-game')?.addEventListener('click', openAddGameModal);
  document.getElementById('btn-open-add-tournament')?.addEventListener('click', openAddTournamentModal);
}

function switchTab(tabId) {
  state.currentTab = tabId;

  document.querySelectorAll('.nav-tab-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.tab === tabId);
  });

  document.querySelectorAll('.app-section').forEach(sec => {
    sec.style.display = (sec.id === `section-${tabId}`) ? 'block' : 'none';
  });

  const bento = document.getElementById('bento-overview');
  if (bento) {
    bento.style.display = (tabId === 'lfg') ? 'grid' : 'none';
  }

  const meta = TAB_META[tabId] || TAB_META.lfg;
  const kickerEl = document.getElementById('topbar-kicker');
  const titleEl = document.getElementById('topbar-title');
  if (kickerEl) kickerEl.innerText = meta.kicker;
  if (titleEl) titleEl.innerText = meta.title;

  document.getElementById('app-sidebar')?.classList.remove('open');

  if (tabId === 'chat') {
    fetchAndRenderChat();
    loadInvites();
  } else if (tabId === 'games') {
    loadGames();
  } else if (tabId === 'database') {
    loadDatabaseExplorer();
  } else if (tabId === 'profile') {
    loadProfileData();
  }
}

async function loadStats() {
  try {
    const stats = await API.getStats();
    if (!stats) return;
    const setText = (id, val) => {
      const el = document.getElementById(id);
      if (el) el.innerText = val ?? 0;
    };
    setText('stat-users', stats.users);
    setText('stat-games', stats.games);
    setText('stat-tournaments', stats.tournaments);
    setText('stat-messages', stats.messages);
    setText('badge-players-count', stats.profiles ?? stats.users);
    setText('badge-games-count', stats.games);
    setText('badge-tournaments-count', stats.tournaments);

    const metaEl = document.getElementById('stat-db-meta');
    if (metaEl) {
      metaEl.innerText = `${stats.dbEngine || 'SQLite 3'} · ${stats.dbSizeKB || 1} KB persistidos`;
    }
  } catch (err) {
    console.error('Error cargando telemetría DB:', err);
  }
}

// =========================================================================
// 2. CATÁLOGO DE JUEGOS DISPONIBLES (SQLITE)
// =========================================================================
async function loadGames() {
  try {
    const games = await API.getGames();
    state.gamesCache = games;
    syncGameDropdowns(games);
    renderGamesGrid(games);
  } catch (err) {
    console.error('Error cargando juegos:', err);
  }
}

function syncGameDropdowns(games) {
  const filterSel = document.getElementById('game-filter');
  if (filterSel) {
    const prev = filterSel.value || 'all';
    filterSel.innerHTML = '<option value="all">Todos los Juegos</option>' +
      games.map(g => `<option value="${escapeHtml(g.code)}">${escapeHtml(g.name)}</option>`).join('');
    if ([...filterSel.options].some(o => o.value === prev)) {
      filterSel.value = prev;
    }
  }

  ['prof-game', 'new-t-game'].forEach(selId => {
    const sel = document.getElementById(selId);
    if (!sel) return;
    const prev = sel.value;
    sel.innerHTML = games.map(g => `<option value="${escapeHtml(g.code)}">${escapeHtml(g.name)}</option>`).join('');
    if (prev && [...sel.options].some(o => o.value === prev)) {
      sel.value = prev;
    }
  });
}

function renderGamesGrid(games) {
  const grid = document.getElementById('games-grid');
  if (!grid) return;

  const q = (state.currentTab === 'games' && state.globalSearch) ? state.globalSearch.toLowerCase() : '';
  const filtered = q
    ? games.filter(g => g.name.toLowerCase().includes(q) || g.category.toLowerCase().includes(q) || g.code.toLowerCase().includes(q))
    : games;

  if (!filtered || filtered.length === 0) {
    grid.innerHTML = '<div style="color:var(--text-secondary);grid-column:1/-1;text-align:center;padding:2.5rem;">No hay juegos que coincidan con la búsqueda.</div>';
    return;
  }

  grid.innerHTML = '';
  filtered.forEach(g => {
    const card = document.createElement('div');
    card.className = 'game-card';
    card.innerHTML = `
      <div class="game-card-banner">
        <img src="${escapeHtml(g.bannerUrl)}" alt="${escapeHtml(g.name)}">
        <div class="game-card-banner-overlay"></div>
        <span class="game-card-category">${escapeHtml(g.category)}</span>
      </div>
      <div class="game-card-body">
        <div class="game-card-title-row">
          <h3>${escapeHtml(g.name)}</h3>
          <span class="game-code-pill">${escapeHtml(g.code)}</span>
        </div>
        <p style="font-size:0.75rem;color:var(--text-muted);margin-bottom:0.55rem;">${escapeHtml(g.platform)}</p>
        <p class="game-card-desc">${escapeHtml(g.description || 'Disponible para emparejamiento LFG y torneos competitivos.')}</p>
        <div class="game-stats-strip">
          <div>
            <span class="game-stat-num">${Number(g.maxTeamSize) || 5}v${Number(g.maxTeamSize) || 5}</span>
            <span class="game-stat-lbl">Formato</span>
          </div>
          <div>
            <span class="game-stat-num">${Number(g.playerCount) || 0}</span>
            <span class="game-stat-lbl">Jugadores</span>
          </div>
          <div>
            <span class="game-stat-num">${Number(g.tournamentCount) || 0}</span>
            <span class="game-stat-lbl">Torneos</span>
          </div>
        </div>
        <button class="btn btn-secondary btn-sm btn-filter-by-game" data-game="${escapeHtml(g.code)}" style="width:100%;">
          Ver Jugadores en ${escapeHtml(g.name)}
        </button>
      </div>
    `;
    grid.appendChild(card);
  });

  grid.querySelectorAll('.btn-filter-by-game').forEach(btn => {
    btn.addEventListener('click', () => {
      state.selectedGameFilter = btn.dataset.game;
      const filterEl = document.getElementById('game-filter');
      if (filterEl) filterEl.value = state.selectedGameFilter;
      switchTab('lfg');
      loadLfgPlayers();
    });
  });
}

// =========================================================================
// 3. BUSCADOR DE JUGADORES (LFG)
// =========================================================================
async function loadLfgPlayers() {
  const container = document.getElementById('players-grid');
  if (!container) return;

  container.innerHTML = '<div style="color:var(--text-secondary);grid-column:1/-1;text-align:center;padding:2rem;">Consultando jugadores en SQLite...</div>';

  try {
    const q = (state.currentTab === 'lfg') ? state.globalSearch : '';
    const players = await API.getPlayers(state.selectedGameFilter, state.selectedToxicFilter, q);
    container.innerHTML = '';

    if (!players || players.length === 0) {
      container.innerHTML = '<div style="color:var(--text-secondary);grid-column:1/-1;text-align:center;padding:2.5rem;">No se encontraron jugadores con los filtros aplicados.</div>';
      return;
    }

    players.forEach(player => {
      const card = createPlayerCard(player);
      container.appendChild(card);
    });
  } catch (err) {
    console.error('Error cargando jugadores:', err);
    container.innerHTML = '<div style="color:var(--color-danger);grid-column:1/-1;text-align:center;padding:2rem;">No se pudo conectar con el servidor Java.</div>';
  }
}

function createPlayerCard(player) {
  const div = document.createElement('div');
  div.className = 'player-card';
  const isCurrentUser = Boolean(state.currentUser && player.username.toLowerCase() === state.currentUser.username.toLowerCase());

  div.innerHTML = `
    <div class="player-card-header">
      <img src="${escapeHtml(player.avatarUrl)}" class="player-avatar" alt="${escapeHtml(player.username)}">
      <div>
        <h3>${escapeHtml(player.username)}</h3>
        <p class="player-meta-text">${escapeHtml(player.region || 'Chile')}</p>
        <span class="badge-game ${escapeHtml(player.game)}">${escapeHtml(player.gameName || player.game)}</span>
      </div>
    </div>

    <div class="player-data-table">
      <div class="player-data-cell">
        <span>Rango</span>
        <strong>${escapeHtml(player.rank)}</strong>
      </div>
      <div class="player-data-cell">
        <span>Rol</span>
        <strong>${escapeHtml(player.role)}</strong>
      </div>
      <div class="player-data-cell">
        <span>Horario</span>
        <strong>${escapeHtml(player.preferredSchedule || 'Noches')}</strong>
      </div>
      <div class="player-data-cell">
        <span>Conducta</span>
        <strong style="color:var(--color-success);">${player.toxicLevel === 'ZERO_TOXIC' ? `Zero-Toxic (${player.reputationScore || 100})` : `Amigable (${player.reputationScore || 95})`}</strong>
      </div>
    </div>

    <p class="player-bio-snippet">"${escapeHtml(player.bio || 'Jugador activo buscando formar equipo.')}"</p>

    <div class="player-chips-row">
      ${player.riotId ? `<span class="chip-plat">ID: ${escapeHtml(player.riotId)}</span>` : ''}
      ${player.discordTag ? `<span class="chip-plat">Discord: ${escapeHtml(player.discordTag)}</span>` : ''}
      ${player.steamId ? `<span class="chip-plat">Steam Vinculado</span>` : ''}
    </div>

    ${!isCurrentUser ? `
      <button class="btn btn-primary btn-sm btn-invite-player" data-user="${escapeHtml(player.username)}" data-game="${escapeHtml(player.gameName || player.game)}">
        Invitar a Partida
      </button>
    ` : `
      <button class="btn btn-secondary btn-sm btn-edit-own-profile">
        Editar mi Perfil
      </button>
    `}
  `;

  div.querySelector('.btn-invite-player')?.addEventListener('click', (e) => {
    const btn = e.currentTarget;
    openMatchModal(btn.dataset.user, btn.dataset.game);
  });
  div.querySelector('.btn-edit-own-profile')?.addEventListener('click', () => switchTab('profile'));

  return div;
}

// =========================================================================
// 4. TORNEOS Y BRACKETS
// =========================================================================
async function loadTournaments() {
  const container = document.getElementById('tournaments-grid');
  if (!container) return;

  try {
    const tournaments = await API.getTournaments();
    container.innerHTML = '';

    tournaments.forEach(t => {
      const card = document.createElement('div');
      card.className = 'tournament-card';
      card.innerHTML = `
        <div class="tournament-card-header">
          <span class="badge-game ${escapeHtml(t.game)}">${escapeHtml(t.gameName)}</span>
          <span style="font-size:0.74rem;color:${t.status === 'IN_PROGRESS' ? 'var(--color-cyan)' : 'var(--color-success)'};font-weight:600;">
            ${t.status === 'IN_PROGRESS' ? 'En Curso' : 'Inscripción Abierta'}
          </span>
        </div>
        <h3>${escapeHtml(t.title)}</h3>
        <div class="tournament-table-info">
          <div>Premio: <strong>${escapeHtml(t.prizePool)}</strong></div>
          <div>Fecha: <strong>${escapeHtml(t.startDate)}</strong></div>
          <div>Equipos inscritos: <strong>${Number(t.registeredTeamsCount)} / ${Number(t.maxTeams)}</strong></div>
          <div>Organizador: <strong>${escapeHtml(t.organizer)}</strong></div>
        </div>
        <div style="display:flex;gap:0.55rem;margin-top:auto;">
          <button class="btn btn-primary btn-sm btn-join-tournament" style="flex:1;" data-tid="${escapeHtml(t.id)}" data-title="${escapeHtml(t.title)}">
            Inscribir Equipo
          </button>
          <button class="btn btn-secondary btn-sm btn-view-bracket" data-tid="${escapeHtml(t.id)}">
            Ver Llaves
          </button>
        </div>
      `;
      container.appendChild(card);
    });

    container.querySelectorAll('.btn-join-tournament').forEach(btn => {
      btn.addEventListener('click', () => openJoinTournamentModal(btn.dataset.tid, btn.dataset.title));
    });
    container.querySelectorAll('.btn-view-bracket').forEach(btn => {
      btn.addEventListener('click', () => renderTournamentBracket(btn.dataset.tid));
    });

    if (tournaments.length > 0) {
      const targetExists = tournaments.some(t => t.id === state.selectedTournamentId);
      renderTournamentBracket(targetExists ? state.selectedTournamentId : tournaments[0].id);
    }
  } catch (err) {
    console.error('Error cargando torneos:', err);
  }
}

async function renderTournamentBracket(tournamentId) {
  state.selectedTournamentId = tournamentId;
  const bracketContainer = document.getElementById('bracket-view');
  if (!bracketContainer) return;

  try {
    const t = await API.getTournament(tournamentId);
    if (!t || t.error) return;

    document.getElementById('bracket-tournament-title').innerText = `Llaves de Eliminación: ${t.title}`;

    if (!t.matches || t.matches.length === 0) {
      const teamsList = (t.registeredTeams && t.registeredTeams.length > 0)
        ? `<div style="margin-top:0.85rem;display:flex;flex-wrap:wrap;justify-content:center;gap:0.45rem;">${t.registeredTeams.map(tm => `<span class="chip-plat">${escapeHtml(tm)}</span>`).join('')}</div>`
        : '';
      bracketContainer.innerHTML = `
        <div style="padding:1.5rem;text-align:center;color:var(--text-muted);font-size:0.85rem;">
          Llaves pendientes de sorteo (${Number(t.registeredTeamsCount)} de ${Number(t.maxTeams)} equipos inscritos en SQLite).
          ${teamsList}
        </div>
      `;
      return;
    }

    const semis = t.matches.filter(m => m.roundName.includes('Semifinal'));
    const finals = t.matches.filter(m => m.roundName.includes('Final') && !m.roundName.includes('Semi'));

    bracketContainer.innerHTML = `
      <div class="bracket-container">
        <div class="bracket-round-column">
          <div class="round-name-tag">Semifinales (Bo1)</div>
          ${semis.map(m => createMatchBoxHtml(m)).join('')}
        </div>
        <div style="color:var(--text-muted);font-weight:bold;">➔</div>
        <div class="bracket-round-column">
          <div class="round-name-tag">Gran Final (Bo3)</div>
          ${finals.map(m => createMatchBoxHtml(m)).join('')}
        </div>
        <div style="color:var(--text-muted);font-weight:bold;">➔</div>
        <div class="bracket-round-column" style="max-width:190px;text-align:center;">
          <div class="round-name-tag">Campeón</div>
          <div class="match-box-item" style="padding:1rem;background:rgba(16,185,129,0.12);border-color:var(--color-success);">
            <strong style="color:var(--text-primary);display:block;">
              ${escapeHtml(finals[0]?.winner || 'Por Definir')}
            </strong>
          </div>
        </div>
      </div>
    `;
  } catch (err) {
    console.error('Error renderizando bracket:', err);
  }
}

function createMatchBoxHtml(m) {
  const isFinished = m.status === 'FINISHED';
  return `
    <div class="match-box-item">
      <div class="match-row-team ${isFinished && m.winner === m.team1 ? 'winner' : ''}">
        <span>${escapeHtml(m.team1 || 'Por definir')}</span>
        <span class="score">${Number(m.scoreTeam1)}</span>
      </div>
      <div class="match-row-team ${isFinished && m.winner === m.team2 ? 'winner' : ''}">
        <span>${escapeHtml(m.team2 || 'Por definir')}</span>
        <span class="score">${Number(m.scoreTeam2)}</span>
      </div>
    </div>
  `;
}

// =========================================================================
// 5. SALA DE COORDINACIÓN (CHAT MULTICANAL + INVITACIONES SQLITE)
// =========================================================================
function setupChat() {
  const input = document.getElementById('chat-message-input');
  const btn = document.getElementById('chat-send-btn');

  btn?.addEventListener('click', handleSendMessage);
  input?.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') handleSendMessage();
  });

  document.querySelectorAll('.channel-list-item').forEach(chBtn => {
    chBtn.addEventListener('click', () => {
      document.querySelectorAll('.channel-list-item').forEach(b => b.classList.remove('active'));
      chBtn.classList.add('active');
      state.activeChannel = chBtn.dataset.channel || 'global';
      const header = document.getElementById('active-channel-title');
      if (header) header.innerText = `Canal Activo: ${chBtn.innerText}`;
      fetchAndRenderChat();
    });
  });

  document.getElementById('btn-refresh-invites')?.addEventListener('click', loadInvites);
}

async function handleSendMessage() {
  const input = document.getElementById('chat-message-input');
  const content = input?.value?.trim();
  if (!content || !state.currentUser) return;

  try {
    const { ok, data } = await API.sendChatMessage(state.currentUser.username, content, state.activeChannel);
    if (!ok) {
      showToast((data && data.error) || 'No se pudo enviar el mensaje.', true);
      return;
    }
    input.value = '';
    await fetchAndRenderChat();
    loadStats();
  } catch (err) {
    showToast('Error al enviar el mensaje al servidor.', true);
  }
}

async function fetchAndRenderChat() {
  const msgContainer = document.getElementById('chat-messages-container');
  if (!msgContainer) return;

  try {
    const messages = await API.getChatMessages(state.activeChannel);
    msgContainer.innerHTML = '';

    if (!messages || messages.length === 0) {
      msgContainer.innerHTML = `
        <div class="chat-empty-state">
          No hay mensajes guardados en <strong>#${escapeHtml(state.activeChannel)}</strong> todavía.<br>
          Envía un mensaje para iniciar la coordinación en SQLite.
        </div>
      `;
      return;
    }

    messages.forEach(m => {
      const isMe = Boolean(state.currentUser && m.sender.toLowerCase() === state.currentUser.username.toLowerCase());
      const item = document.createElement('div');
      item.className = `message-card ${isMe ? 'own' : ''}`;
      item.innerHTML = `
        <img src="${escapeHtml(m.avatar)}" class="msg-avatar-img" alt="${escapeHtml(m.sender)}">
        <div class="msg-body-wrapper">
          <div class="msg-meta-line">
            <strong>${escapeHtml(m.sender)}</strong>
            <span>${escapeHtml(m.timestamp)}</span>
          </div>
          <div class="msg-text-content">${escapeHtml(m.content)}</div>
        </div>
      `;
      msgContainer.appendChild(item);
    });

    msgContainer.scrollTop = msgContainer.scrollHeight;
  } catch (err) {
    console.error('Error actualizando chat:', err);
  }
}

async function loadInvites() {
  const container = document.getElementById('invites-list');
  if (!state.currentUser) return;

  try {
    const invites = await API.getMatchRequests(state.currentUser.username);
    const pendingForMe = invites.filter(i => i.receiver.toLowerCase() === state.currentUser.username.toLowerCase() && i.status === 'PENDING');

    const badgeEl = document.getElementById('badge-invites-count');
    if (badgeEl) badgeEl.innerText = pendingForMe.length;
    const topBtn = document.getElementById('topbar-invites-text');
    if (topBtn) topBtn.innerText = pendingForMe.length > 0 ? `Invitaciones (${pendingForMe.length})` : 'Invitaciones';

    if (!container) return;
    if (!invites || invites.length === 0) {
      container.innerHTML = '<div style="text-align:center;color:var(--text-muted);padding:2rem 0.5rem;font-size:0.82rem;">No tienes invitaciones de partida registradas aún.</div>';
      return;
    }

    container.innerHTML = '';
    invites.forEach(inv => {
      const isReceiver = inv.receiver.toLowerCase() === state.currentUser.username.toLowerCase();
      const card = document.createElement('div');
      card.className = 'invite-item-card';
      card.innerHTML = `
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:0.35rem;">
          <strong style="font-size:0.82rem;color:#fff;">
            ${isReceiver ? `De @${escapeHtml(inv.sender)}` : `Para @${escapeHtml(inv.receiver)}`}
          </strong>
          <span class="invite-status-pill ${escapeHtml(inv.status)}">${escapeHtml(inv.status)}</span>
        </div>
        <div style="font-size:0.74rem;color:var(--color-cyan);margin-bottom:0.25rem;">Juego: ${escapeHtml(inv.game)}</div>
        <p style="font-size:0.78rem;color:var(--text-secondary);margin-bottom:0.55rem;">"${escapeHtml(inv.message || 'Sin mensaje adjunto')}"</p>
        ${(isReceiver && inv.status === 'PENDING') ? `
          <div style="display:flex;gap:0.45rem;">
            <button class="btn btn-success btn-sm btn-respond-inv" data-id="${escapeHtml(inv.id)}" data-status="ACCEPTED" style="flex:1;">Aceptar</button>
            <button class="btn btn-danger btn-sm btn-respond-inv" data-id="${escapeHtml(inv.id)}" data-status="REJECTED" style="flex:1;">Rechazar</button>
          </div>
        ` : ''}
      `;
      container.appendChild(card);
    });

    container.querySelectorAll('.btn-respond-inv').forEach(btn => {
      btn.addEventListener('click', async () => {
        const res = await API.respondMatchRequest(btn.dataset.id, btn.dataset.status);
        if (res.ok) {
          showToast(btn.dataset.status === 'ACCEPTED' ? 'Invitación aceptada.' : 'Invitación rechazada.');
          loadInvites();
        }
      });
    });
  } catch (err) {
    console.error('Error cargando invitaciones:', err);
  }
}

// =========================================================================
// 6. PERFIL DE USUARIO Y CUENTA REGISTRADA
// =========================================================================
async function loadProfileData() {
  if (!state.currentUser) return;

  const setVal = (id, val) => {
    const el = document.getElementById(id);
    if (el && val !== undefined && val !== null) el.value = val;
  };

  setVal('prof-username-readonly', state.currentUser.username);
  setVal('prof-email', state.currentUser.email || '');
  setVal('prof-phone', state.currentUser.telefono || '');
  setVal('prof-account-role', state.currentUser.role || 'GAMER');

  try {
    const [players, users] = await Promise.all([
      API.getPlayers('all'),
      API.getUsers(state.currentUser.username)
    ]);

    const myAccount = users.find(u => u.username.toLowerCase() === state.currentUser.username.toLowerCase());
    if (myAccount) {
      setVal('prof-email', myAccount.email || '');
      setVal('prof-phone', myAccount.telefono || '');
      setVal('prof-account-role', myAccount.role || 'GAMER');
      state.currentUser.email = myAccount.email;
      state.currentUser.telefono = myAccount.telefono;
    }

    const myProfile = players.find(p => p.username.toLowerCase() === state.currentUser.username.toLowerCase());
    if (myProfile) {
      setVal('prof-bio', myProfile.bio || '');
      setVal('prof-region', myProfile.region || '');
      setVal('prof-game', myProfile.game || 'valorant');
      setVal('prof-rank', myProfile.rank || '');
      setVal('prof-role', myProfile.role || '');
      setVal('prof-riot', myProfile.riotId || '');
      setVal('prof-discord', myProfile.discordTag || '');
      setVal('prof-schedule', myProfile.preferredSchedule || '');
    }
  } catch (err) {
    console.error('Error cargando perfil:', err);
  }
}

function setupProfileForm() {
  document.getElementById('profile-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!state.currentUser) return;

    const payload = {
      username: state.currentUser.username,
      email: document.getElementById('prof-email').value.trim(),
      telefono: document.getElementById('prof-phone').value.trim(),
      bio: document.getElementById('prof-bio').value.trim(),
      region: document.getElementById('prof-region').value.trim(),
      game: document.getElementById('prof-game').value,
      rank: document.getElementById('prof-rank').value.trim(),
      role: document.getElementById('prof-role').value.trim(),
      riotId: document.getElementById('prof-riot').value.trim(),
      discordTag: document.getElementById('prof-discord').value.trim(),
      preferredSchedule: document.getElementById('prof-schedule').value.trim()
    };

    try {
      const { ok, data } = await API.updateProfile(payload);
      if (!ok) {
        showToast((data && data.error) || 'Error al guardar cambios.', true);
        return;
      }

      if (data && data.user) {
        state.currentUser.email = data.user.email;
        state.currentUser.telefono = data.user.telefono;
        const storage = localStorage.getItem('playmatch_user') ? localStorage : sessionStorage;
        storage.setItem('playmatch_user', JSON.stringify(state.currentUser));
      }

      showToast('Perfil, correo y teléfono actualizados en SQLite.');
      loadLfgPlayers();
      loadGames();
    } catch (err) {
      showToast('Error al conectar con el servidor Java.', true);
    }
  });
}

// =========================================================================
// 7. MONITOR DE BASE DE DATOS SQLITE
// =========================================================================
function setupDatabaseMonitor() {
  let dbDebounce = null;
  document.getElementById('db-search-input')?.addEventListener('input', () => {
    clearTimeout(dbDebounce);
    dbDebounce = setTimeout(loadDatabaseExplorer, 200);
  });
  document.getElementById('btn-refresh-db')?.addEventListener('click', loadDatabaseExplorer);
}

async function loadDatabaseExplorer() {
  const usersTbody = document.getElementById('db-users-tbody');
  const gamesTbody = document.getElementById('db-games-tbody');
  if (!usersTbody || !gamesTbody) return;

  const q = document.getElementById('db-search-input')?.value?.trim() || '';

  try {
    const [users, games] = await Promise.all([
      API.getUsers(q),
      API.getGames(true)
    ]);

    const uCountLabel = document.getElementById('db-users-count-label');
    if (uCountLabel) uCountLabel.innerText = `${users.length} registros`;

    usersTbody.innerHTML = users.length === 0
      ? '<tr><td colspan="5" style="text-align:center;">Sin usuarios coincidentes.</td></tr>'
      : users.map(u => `
        <tr>
          <td><strong style="color:#fff;">@${escapeHtml(u.username)}</strong></td>
          <td>${escapeHtml(u.email)}</td>
          <td>${escapeHtml(u.telefono || 'Sin registro')}</td>
          <td><span class="role-pill ${escapeHtml(u.role)}">${escapeHtml(u.role)}</span></td>
          <td style="font-size:0.76rem;color:var(--text-muted);">${escapeHtml(u.createdAt || 'Reciente')}</td>
        </tr>
      `).join('');

    const gCountLabel = document.getElementById('db-games-count-label');
    if (gCountLabel) gCountLabel.innerText = `${games.length} juegos`;

    gamesTbody.innerHTML = games.map(g => `
      <tr>
        <td><span class="game-code-pill">${escapeHtml(g.code)}</span></td>
        <td><strong style="color:#fff;">${escapeHtml(g.name)}</strong></td>
        <td>${escapeHtml(g.category)}</td>
        <td>${escapeHtml(g.platform)}</td>
        <td>${Number(g.maxTeamSize) || 5} jugadores</td>
        <td>${Number(g.playerCount) || 0}</td>
        <td>${Number(g.tournamentCount) || 0}</td>
      </tr>
    `).join('');
  } catch (err) {
    console.error('Error cargando monitor SQLite:', err);
  }
}

// =========================================================================
// 8. MODALES Y EVENTOS
// =========================================================================
function setupModals() {
  document.getElementById('modal-close-btn')?.addEventListener('click', () => closeModalById('match-modal'));
  document.getElementById('tournament-modal-close-btn')?.addEventListener('click', () => closeModalById('join-tournament-modal'));
  document.getElementById('add-game-close-btn')?.addEventListener('click', () => closeModalById('add-game-modal'));
  document.getElementById('add-tournament-close-btn')?.addEventListener('click', () => closeModalById('add-tournament-modal'));

  document.getElementById('btn-logout')?.addEventListener('click', () => {
    localStorage.removeItem('playmatch_user');
    sessionStorage.removeItem('playmatch_user');
    window.location.href = 'login.html';
  });

  window.addEventListener('click', (e) => {
    if (e.target.classList.contains('modal-overlay')) {
      e.target.style.display = 'none';
    }
  });

  // Invitar jugador
  document.getElementById('match-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!state.currentUser) return;

    const receiver = document.getElementById('match-target-user').value;
    const game = document.getElementById('match-target-game').value;
    const msg = document.getElementById('match-message-input').value.trim();

    try {
      const { ok, data } = await API.sendMatchRequest(state.currentUser.username, receiver, game, msg);
      if (!ok) {
        showToast((data && data.error) || 'Error enviando invitación.', true);
        return;
      }
      closeModalById('match-modal');
      document.getElementById('match-message-input').value = '';
      showToast(`Invitación enviada a @${receiver} y guardada en SQLite.`);
      loadInvites();
      loadStats();
    } catch (err) {
      showToast('Error al enviar la invitación.', true);
    }
  });

  // Inscribir equipo en torneo
  document.getElementById('join-tournament-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!state.currentUser) return;

    const tId = document.getElementById('join-t-id').value;
    const teamName = document.getElementById('join-team-name').value.trim();

    try {
      const { ok, data } = await API.joinTournament(tId, teamName, state.currentUser.username);
      if (!ok) {
        showToast((data && data.error) || 'Error al inscribir equipo.', true);
        return;
      }
      closeModalById('join-tournament-modal');
      document.getElementById('join-team-name').value = '';
      showToast(data.message || 'Equipo inscrito en SQLite.');
      loadTournaments();
      loadStats();
    } catch (err) {
      showToast('Error al procesar la inscripción.', true);
    }
  });

  // Registrar nuevo juego disponible en SQLite
  document.getElementById('add-game-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const gameData = {
      name: document.getElementById('new-game-name').value.trim(),
      category: document.getElementById('new-game-category').value.trim(),
      platform: document.getElementById('new-game-platform').value.trim(),
      maxTeamSize: document.getElementById('new-game-teamsize').value,
      description: document.getElementById('new-game-desc').value.trim()
    };

    try {
      const { ok, data } = await API.createGame(gameData);
      if (!ok) {
        showToast((data && data.error) || 'No se pudo guardar el juego.', true);
        return;
      }
      closeModalById('add-game-modal');
      e.target.reset();
      showToast(`Juego "${gameData.name}" guardado en SQLite.`);
      await loadGames();
      await loadStats();
      switchTab('games');
    } catch (err) {
      showToast('Error al registrar el juego.', true);
    }
  });

  // Crear nuevo torneo
  document.getElementById('add-tournament-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const payload = {
      title: document.getElementById('new-t-title').value.trim(),
      game: document.getElementById('new-t-game').value,
      maxTeams: document.getElementById('new-t-max').value,
      prizePool: document.getElementById('new-t-prize').value.trim(),
      startDate: document.getElementById('new-t-date').value.trim(),
      rules: document.getElementById('new-t-rules').value.trim(),
      organizer: state.currentUser ? state.currentUser.username : 'AndesEsports'
    };

    try {
      const { ok, data } = await API.createTournament(payload);
      if (!ok) {
        showToast((data && data.error) || 'No se pudo crear el torneo.', true);
        return;
      }
      closeModalById('add-tournament-modal');
      e.target.reset();
      showToast('Torneo publicado y guardado en SQLite.');
      await loadTournaments();
      await loadStats();
    } catch (err) {
      showToast('Error al crear el torneo.', true);
    }
  });
}

function openMatchModal(username, game) {
  document.getElementById('match-target-user').value = username;
  document.getElementById('match-target-game').value = game;
  document.getElementById('modal-title').innerText = `Invitar a @${username} (${game})`;
  document.getElementById('match-modal').style.display = 'flex';
}

function openJoinTournamentModal(tournamentId, tournamentTitle) {
  document.getElementById('join-t-id').value = tournamentId;
  document.getElementById('join-modal-title').innerText = `Inscripción: ${tournamentTitle}`;
  document.getElementById('join-tournament-modal').style.display = 'flex';
}

function openAddGameModal() {
  document.getElementById('add-game-modal').style.display = 'flex';
}

function openAddTournamentModal() {
  document.getElementById('add-tournament-modal').style.display = 'flex';
}

function closeModalById(id) {
  const el = document.getElementById(id);
  if (el) el.style.display = 'none';
}

let toastTimeout = null;
function showToast(message, isError = false) {
  const toast = document.getElementById('app-toast');
  if (!toast) return;
  toast.innerText = message;
  toast.style.display = 'block';

  if (isError) {
    toast.style.borderColor = 'rgba(239, 68, 68, 0.7)';
    toast.style.color = '#fca5a5';
    toast.style.backgroundColor = '#1c1020';
  } else {
    toast.style.borderColor = 'var(--color-primary-bright)';
    toast.style.color = 'var(--text-primary)';
    toast.style.backgroundColor = 'var(--bg-card-solid)';
  }

  if (toastTimeout) clearTimeout(toastTimeout);
  toastTimeout = setTimeout(() => { toast.style.display = 'none'; }, 4200);
}

function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
