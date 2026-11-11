/**
 * PlayMatch Frontend Application Logic
 * Conectado a Backend Java 21 REST + PostgreSQL (HikariCP) / SQLite JDBC.
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
  games: { kicker: 'Catálogo Relacional', title: 'Juegos Disponibles' },
  tournaments: { kicker: 'Competencias Oficiales', title: 'Torneos y Brackets' },
  chat: { kicker: 'Comunicación', title: 'Sala de Coordinación & Invitaciones' },
  profile: { kicker: 'Cuenta Verificada', title: 'Mi Perfil y Credenciales' },
  database: { kicker: 'Persistencia Relacional 3NF', title: 'Monitor Base de Datos' }
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
  favGamesSelected: new Set(),
  chatInterval: null
};

document.addEventListener('DOMContentLoaded', async () => {
  updateAuthUI();
  setupNavigation();
  setupChat();
  setupModals();
  setupProfileForm();
  setupDatabaseMonitor();

  document.getElementById('btn-refresh-ai-rec')?.addEventListener('click', () => loadRecommendations(true));

  await Promise.all([
    loadStats(),
    loadGames(),
    loadLfgPlayers(),
    loadRecommendations(),
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
      metaEl.innerText = `${stats.dbEngine || 'PostgreSQL'} · ${stats.dbSizeKB || 1} KB persistidos`;
    }
    const liveLabel = document.getElementById('db-live-label');
    if (liveLabel) {
      liveLabel.innerText = stats.isPostgres ? 'PostgreSQL · Activa' : 'SQLite Local · Activa';
    }
    const engineTag = document.getElementById('telemetry-engine-tag');
    if (engineTag) {
      engineTag.innerText = stats.isPostgres ? 'POSTGRES · POOL' : 'SQLITE · 3NF';
    }
    const monEl = document.getElementById('db-monitor-engine-name');
    if (monEl) {
      monEl.innerText = stats.dbEngine || 'SQL';
    }
  } catch (err) {
    console.error('Error cargando telemetría DB:', err);
  }
}

// =========================================================================
// 2. CATÁLOGO DE JUEGOS DISPONIBLES (BASE DE DATOS)
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

  ['prof-game', 'new-t-game', 'match-target-game'].forEach(selId => {
    const sel = document.getElementById(selId);
    if (!sel) return;
    const prev = sel.value;
    sel.innerHTML = games.map(g => `<option value="${escapeHtml(g.code)}">${escapeHtml(g.name)}</option>`).join('');
    if (prev && [...sel.options].some(o => o.value === prev)) {
      sel.value = prev;
    }
  });

  renderFavGamesChips(games);
}

function renderFavGamesChips(games = state.gamesCache) {
  const wrap = document.getElementById('prof-fav-games-chips');
  if (!wrap || !games || games.length === 0) return;
  wrap.innerHTML = games.map(g => {
    const code = String(g.code || '').toLowerCase();
    const isAct = state.favGamesSelected.has(code);
    return `<button type="button" class="taste-chip ${isAct ? 'active' : ''}" data-value="${escapeHtml(code)}">${escapeHtml(g.name)}</button>`;
  }).join('');

  wrap.querySelectorAll('.taste-chip').forEach(btn => {
    btn.addEventListener('click', () => {
      const code = String(btn.dataset.value || '').toLowerCase();
      if (!code) return;
      if (state.favGamesSelected.has(code)) {
        state.favGamesSelected.delete(code);
        btn.classList.remove('active');
      } else {
        state.favGamesSelected.add(code);
        btn.classList.add('active');
      }
    });
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
    const accent = /^#[0-9a-fA-F]{3,8}$/.test(g.accentColor || '') ? g.accentColor : '#8b5cf6';
    const card = document.createElement('div');
    card.className = 'game-card';
    card.innerHTML = `
      <div class="game-card-banner" style="background:linear-gradient(135deg, ${accent}44 0%, #0d0a18 100%);">
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
    card.querySelector('.game-card-banner img')?.addEventListener('error', (e) => {
      e.currentTarget.style.display = 'none';
    });
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
// 3. BUSCADOR DE JUGADORES (LFG) Y RECOMENDACIÓN IA (GEMINI)
// =========================================================================
async function loadRecommendations(showFeedback = false) {
  if (!state.currentUser) return;
  const grid = document.getElementById('ai-recommendations-grid');
  if (!grid) return;

  try {
    const data = await API.getRecommendations(state.currentUser.username);
    if (!data || data.error) return;
    renderRecommendations(data);
    if (showFeedback) {
      showToast(`Recomendaciones recalculadas (${data.engineLabel || 'IA'}).`);
    }
  } catch (err) {
    console.error('Error cargando recomendaciones IA:', err);
  }
}

function renderRecommendations(data) {
  const pill = document.getElementById('ai-engine-pill');
  const summaryEl = document.getElementById('ai-rec-summary');
  const warnEl = document.getElementById('ai-low-rating-banner');
  const gamesWrap = document.getElementById('ai-suggested-games');
  const grid = document.getElementById('ai-recommendations-grid');
  if (!grid) return;

  if (pill) {
    pill.innerText = data.geminiConfigured ? `GEMINI · ${(data.model || '3.8-flash').toUpperCase()}` : 'MOTOR IA · AFINIDAD';
  }
  if (summaryEl) {
    summaryEl.innerText = data.summary || 'Recomendaciones calculadas según tus juegos, gustos y valoración.';
  }
  if (warnEl) {
    if (data.currentUserLowVisibility) {
      warnEl.style.display = 'block';
      warnEl.innerText = `Aviso de visibilidad: Tu cuenta tiene ${Number(data.currentUserRating || 0).toFixed(1)}★ (menos de 2.0★), por lo que otros jugadores te verán recomendado con mucha menor frecuencia.`;
    } else {
      warnEl.style.display = 'none';
    }
  }
  if (gamesWrap) {
    const recGames = Array.isArray(data.recommendedGames) ? data.recommendedGames : [];
    gamesWrap.innerHTML = recGames.map(gName => `<span class="game-code-pill">${escapeHtml(gName)}</span>`).join('');
  }

  const recs = Array.isArray(data.recommendations) ? data.recommendations : [];
  if (recs.length === 0) {
    grid.innerHTML = '<div style="color:var(--text-secondary);font-size:0.84rem;">No hay otros jugadores para recomendar todavía.</div>';
    return;
  }

  grid.innerHTML = '';
  recs.slice(0, 6).forEach(rec => {
    const isLow = Boolean(rec.lowRatedPenalty || Number(rec.starRating) < 2.0 || rec.visibilityTier === 'REDUCIDA');
    const card = document.createElement('div');
    card.className = `ai-rec-card ${isLow ? 'low-rated' : ''}`;
    const tags = Array.isArray(rec.sharedTags) ? rec.sharedTags.slice(0, 4) : [];

    card.innerHTML = `
      <div class="ai-rec-card-top">
        <div class="ai-rec-user-info">
          <img src="${escapeHtml(rec.avatarUrl)}" alt="${escapeHtml(rec.username)}">
          <div style="min-width:0;">
            <strong style="color:#fff;font-size:0.9rem;display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">@${escapeHtml(rec.username)}</strong>
            <span class="star-rating-pill ${isLow ? 'low' : ''}">
              ${Number(rec.starRating || 0).toFixed(1)} ★ (${Number(rec.ratingCount) || 0}) ${isLow ? '· Baja visibilidad' : ''}
            </span>
          </div>
        </div>
        <span class="ai-score-badge ${isLow ? 'low' : ''}">
          ${isLow ? `${Number(rec.matchScore)}% · <2★ Penalizado` : `${Number(rec.matchScore)}% Match IA`}
        </span>
      </div>

      <p class="ai-rec-reason">${escapeHtml(rec.reason)}</p>

      <div class="player-chips-row" style="margin-bottom:0.2rem;">
        <span class="badge-game ${escapeHtml(rec.game)}">${escapeHtml(rec.gameName || rec.game)}</span>
        ${tags.map(t => `<span class="chip-plat">${escapeHtml(t)}</span>`).join('')}
      </div>

      <div class="ai-rec-actions">
        <button type="button" class="btn btn-primary btn-sm btn-ai-invite" data-user="${escapeHtml(rec.username)}" data-game="${escapeHtml(rec.gameName || rec.game)}" style="flex:1;">Invitar</button>
        <button type="button" class="btn btn-secondary btn-sm btn-ai-rate" data-user="${escapeHtml(rec.username)}">Valorar ★</button>
      </div>
    `;

    card.querySelector('.btn-ai-invite')?.addEventListener('click', (e) => {
      const b = e.currentTarget;
      openMatchModal(b.dataset.user, b.dataset.game);
    });
    card.querySelector('.btn-ai-rate')?.addEventListener('click', (e) => {
      openRateModal(e.currentTarget.dataset.user);
    });
    grid.appendChild(card);
  });
}

async function loadLfgPlayers() {
  const container = document.getElementById('players-grid');
  if (!container) return;

  container.innerHTML = '<div style="color:var(--text-secondary);grid-column:1/-1;text-align:center;padding:2rem;">Consultando jugadores en la base de datos...</div>';

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
  const stars = Number(player.starRating !== undefined ? player.starRating : 4.8);
  const isLowRating = stars < 2.0;

  div.innerHTML = `
    <div class="player-card-header">
      <img src="${escapeHtml(player.avatarUrl)}" class="player-avatar" alt="${escapeHtml(player.username)}">
      <div>
        <h3>${escapeHtml(player.username)}</h3>
        <p class="player-meta-text">${escapeHtml(player.region || 'Chile')}</p>
        <div style="display:flex;flex-wrap:wrap;gap:0.35rem;align-items:center;">
          <span class="badge-game ${escapeHtml(player.game)}">${escapeHtml(player.gameName || player.game)}</span>
          <span class="star-rating-pill ${isLowRating ? 'low' : ''}">
            ${stars.toFixed(1)} ★ (${Number(player.ratingCount) || 0})
          </span>
        </div>
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
        <span>Visibilidad</span>
        <strong style="color:${isLowRating ? 'var(--color-danger)' : 'var(--color-success)'};">
          ${isLowRating ? 'Reducida (<2★)' : (player.toxicLevel === 'ZERO_TOXIC' ? 'Zero-Toxic · Alta' : 'Amigable · Normal')}
        </strong>
      </div>
    </div>

    <p class="player-bio-snippet">"${escapeHtml(player.bio || 'Jugador activo buscando formar equipo.')}"</p>

    <div class="player-chips-row">
      ${player.favoriteGenres ? `<span class="chip-plat">${escapeHtml(player.favoriteGenres.split(',')[0])}</span>` : ''}
      ${player.riotId ? `<span class="chip-plat">ID: ${escapeHtml(player.riotId)}</span>` : ''}
      ${(player.discordConnected || player.discordTag) ? `<button type="button" class="chip-discord" data-copy-discord="${escapeHtml(player.discordTag)}">Discord: @${escapeHtml(player.discordTag)}</button>` : ''}
      ${player.steamId ? `<span class="chip-plat">Steam Vinculado</span>` : ''}
    </div>

    ${!isCurrentUser ? `
      <div class="player-actions-row">
        <button class="btn btn-primary btn-sm btn-invite-player" data-user="${escapeHtml(player.username)}" data-game="${escapeHtml(player.gameName || player.game)}">
          Invitar a Partida
        </button>
        <button type="button" class="btn btn-secondary btn-sm btn-rate-player" data-user="${escapeHtml(player.username)}">
          Valorar ★
        </button>
      </div>
    ` : `
      <button class="btn btn-secondary btn-sm btn-edit-own-profile">
        Editar mi Perfil y Gustos
      </button>
    `}
  `;

  div.querySelector('.chip-discord')?.addEventListener('click', (e) => {
    const tag = e.currentTarget.dataset.copyDiscord;
    if (tag && navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(tag).then(() => showToast(`Usuario de Discord @${tag} copiado.`));
    } else if (tag) {
      showToast(`Discord conectado: @${tag}`);
    }
  });
  div.querySelector('.btn-invite-player')?.addEventListener('click', (e) => {
    const btn = e.currentTarget;
    openMatchModal(btn.dataset.user, btn.dataset.game);
  });
  div.querySelector('.btn-rate-player')?.addEventListener('click', (e) => {
    openRateModal(e.currentTarget.dataset.user);
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
          Llaves pendientes de sorteo (${Number(t.registeredTeamsCount)} de ${Number(t.maxTeams)} equipos inscritos).
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
// 5. SALA DE COORDINACIÓN (CHAT MULTICANAL + INVITACIONES)
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
      const label = chBtn.dataset.label || chBtn.innerText.trim();
      const desc = chBtn.dataset.desc || 'Conversación persistida entre jugadores.';
      const header = document.getElementById('active-channel-title');
      if (header) header.innerText = `Canal Activo: ${label}`;
      const descEl = document.getElementById('active-channel-desc');
      if (descEl) descEl.innerText = desc;
      if (input) input.placeholder = `Escribe en ${label} para charlar o buscar gente...`;
      const mc = document.getElementById('chat-messages-container');
      if (mc) mc.innerHTML = '<div class="chat-empty-state">Cargando sala...</div>';
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
          Envía un mensaje para iniciar la conversación o buscar dúo.
        </div>
      `;
      return;
    }

    const defaultGame = (state.selectedGameFilter && state.selectedGameFilter !== 'all')
      ? state.selectedGameFilter
      : (document.getElementById('prof-game')?.value || 'valorant');

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
            ${!isMe ? `<button type="button" class="msg-invite-btn">Invitar a jugar</button>` : ''}
          </div>
          <div class="msg-text-content">${escapeHtml(m.content)}</div>
        </div>
      `;
      item.querySelector('.msg-invite-btn')?.addEventListener('click', () => {
        openMatchModal(m.sender, defaultGame);
      });
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
function syncProfileSession(userObj) {
  if (!state.currentUser || !userObj) return;
  if (userObj.email !== undefined) state.currentUser.email = userObj.email;
  if (userObj.telefono !== undefined) state.currentUser.telefono = userObj.telefono;
  if (userObj.phoneVerified !== undefined) state.currentUser.phoneVerified = Boolean(userObj.phoneVerified);
  if (userObj.avatarUrl) state.currentUser.avatar = userObj.avatarUrl;
  const storage = localStorage.getItem('playmatch_user') ? localStorage : sessionStorage;
  storage.setItem('playmatch_user', JSON.stringify(state.currentUser));
  updateAuthUI();
}

function renderProfileIdentityBadges(profile = null) {
  const user = state.currentUser;
  if (!user) return;
  const defaultAvatar = `https://api.dicebear.com/7.x/bottts/svg?seed=${encodeURIComponent(user.username)}`;
  const avatarSrc = user.avatar || defaultAvatar;

  const prevImg = document.getElementById('prof-avatar-preview');
  if (prevImg) prevImg.src = avatarSrc;
  const nameEl = document.getElementById('prof-avatar-username');
  if (nameEl) nameEl.innerText = `@${user.username}`;
  const urlInp = document.getElementById('prof-avatar-url');
  if (urlInp && !avatarSrc.startsWith('data:image/')) {
    urlInp.value = avatarSrc;
  }

  const verified = Boolean(user.phoneVerified);
  ['prof-phone-badge', 'prof-phone-status-pill'].forEach(id => {
    const el = document.getElementById(id);
    if (!el) return;
    el.className = `status-pill ${verified ? 'verified' : 'pending'}`;
    el.innerText = verified ? 'Cuenta Verificada (Privado)' : 'Teléfono sin verificar';
  });

  if (profile !== null) {
    const connected = Boolean(profile.discordConnected || (profile.discordTag && profile.discordTag.trim()));
    const dTag = (profile.discordTag || '').trim();
    const dId = (profile.discordId || '').trim();

    const profBadge = document.getElementById('prof-discord-badge');
    if (profBadge) {
      profBadge.className = `status-pill ${connected ? 'discord' : 'offline'}`;
      profBadge.innerText = connected ? `Discord: @${dTag}` : 'Discord sin conectar';
    }
    const cardBadge = document.getElementById('discord-status-badge');
    if (cardBadge) {
      cardBadge.className = `status-pill ${connected ? 'discord' : 'offline'}`;
      cardBadge.innerText = connected ? `Conectado: @${dTag}` : 'Sin conectar';
    }

    const dUserInp = document.getElementById('discord-username-input');
    if (dUserInp) dUserInp.value = dTag;
    const dIdInp = document.getElementById('discord-id-input');
    if (dIdInp) dIdInp.value = dId;

    const btnDisconnect = document.getElementById('btn-disconnect-discord');
    if (btnDisconnect) btnDisconnect.style.display = connected ? 'inline-flex' : 'none';
    const profileLink = document.getElementById('discord-profile-link');
    if (profileLink) {
      if (connected && /^[0-9]{17,22}$/.test(dId)) {
        profileLink.href = `https://discord.com/users/${encodeURIComponent(dId)}`;
        profileLink.style.display = 'inline-flex';
      } else {
        profileLink.style.display = 'none';
      }
    }

    const stars = Number(profile.starRating !== undefined ? profile.starRating : 4.8);
    const votes = Number(profile.ratingCount || 0);
    const lowVis = stars < 2.0;
    const rateBadge = document.getElementById('prof-rating-badge');
    if (rateBadge) {
      rateBadge.className = `status-pill ${lowVis ? 'pending' : 'verified'}`;
      rateBadge.innerText = `${stars.toFixed(1)} ★ (${votes} votos) · ${lowVis ? 'Baja visibilidad IA' : 'Prioridad Alta'}`;
    }
    const visNote = document.getElementById('prof-rating-visibility-note');
    if (visNote) {
      visNote.innerText = lowVis
        ? 'Tu valoración está bajo 2.0★: te recomendarán menos en las sugerencias IA hasta subir tu puntuación.'
        : 'Con 2.0★ o más tu perfil participa normalmente en las recomendaciones por gustos de Gemini IA.';
    }
  }
}

function applyTasteChipsSelection(containerId, csvValues) {
  const wrap = document.getElementById(containerId);
  if (!wrap) return;
  const selected = new Set(
    String(csvValues || '')
      .split(',')
      .map(s => s.trim().toLowerCase())
      .filter(Boolean)
  );
  wrap.querySelectorAll('.taste-chip').forEach(btn => {
    const v = String(btn.dataset.value || '').trim().toLowerCase();
    btn.classList.toggle('active', selected.has(v));
  });
}

function collectTasteChipsSelection(containerId) {
  const wrap = document.getElementById(containerId);
  if (!wrap) return '';
  return [...wrap.querySelectorAll('.taste-chip.active')]
    .map(b => String(b.dataset.value || '').trim())
    .filter(Boolean)
    .join(',');
}

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
  renderProfileIdentityBadges(null);

  try {
    const [players, meRes] = await Promise.all([
      API.getPlayers('all'),
      API.getMyAccount(state.currentUser.username).catch(() => ({ ok: false, data: null }))
    ]);

    const myAccount = meRes && meRes.ok && meRes.data && meRes.data.user ? meRes.data.user : null;
    if (myAccount) {
      setVal('prof-email', myAccount.email || '');
      setVal('prof-phone', myAccount.telefono || '');
      setVal('prof-account-role', myAccount.role || 'GAMER');
      syncProfileSession(myAccount);
    }

    const myProfile = players.find(p => p.username.toLowerCase() === state.currentUser.username.toLowerCase());
    if (myProfile) {
      setVal('prof-bio', myProfile.bio || '');
      setVal('prof-region', myProfile.region || '');
      setVal('prof-game', myProfile.game || 'valorant');
      setVal('prof-rank', myProfile.rank || '');
      setVal('prof-role', myProfile.role || '');
      setVal('prof-riot', myProfile.riotId || '');
      setVal('prof-playstyle', myProfile.playstyle || 'COMPETITIVE');
      setVal('prof-schedule', myProfile.preferredSchedule || '');

      state.favGamesSelected = new Set(
        String(myProfile.favoriteGames || '')
          .split(',')
          .map(s => s.trim().toLowerCase())
          .filter(Boolean)
      );
      renderFavGamesChips();
      applyTasteChipsSelection('prof-fav-genres-chips', myProfile.favoriteGenres || '');
      applyTasteChipsSelection('prof-play-prefs-chips', myProfile.playPreferences || '');
      renderProfileIdentityBadges(myProfile);
    }
  } catch (err) {
    console.error('Error cargando perfil:', err);
  }
}

function resizeImageFileToDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('No se pudo leer el archivo seleccionado.'));
    reader.onload = () => {
      const rawDataUrl = reader.result;
      if (file.type === 'image/gif' && rawDataUrl.length <= 450000) {
        resolve(rawDataUrl);
        return;
      }
      const img = new Image();
      img.onerror = () => reject(new Error('Archivo de imagen inválido o dañado.'));
      img.onload = () => {
        const maxSide = 256;
        const ratio = Math.min(1, maxSide / Math.max(img.width, img.height));
        const w = Math.max(1, Math.round(img.width * ratio));
        const h = Math.max(1, Math.round(img.height * ratio));
        const canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0, w, h);
        const outWebp = canvas.toDataURL('image/webp', 0.88);
        const out = outWebp.startsWith('data:image/webp') ? outWebp : canvas.toDataURL('image/jpeg', 0.88);
        resolve(out);
      };
      img.src = rawDataUrl;
    };
    reader.readAsDataURL(file);
  });
}

async function persistAvatarChange(avatarUrl) {
  if (!state.currentUser) return;
  try {
    const { ok, data } = await API.updateAvatar(state.currentUser.username, avatarUrl);
    if (!ok) {
      showToast((data && data.error) || 'No se pudo actualizar la foto de perfil.', true);
      return;
    }
    if (data && data.user) syncProfileSession(data.user);
    renderProfileIdentityBadges(null);
    showToast('Foto de perfil actualizada en tu cuenta, LFG y chat.');
    loadLfgPlayers();
    fetchAndRenderChat();
  } catch (err) {
    showToast('Error al subir la imagen al servidor.', true);
  }
}

function setupProfileForm() {
  // 1. Subir imagen desde archivo local o URL
  document.getElementById('prof-avatar-file')?.addEventListener('change', async (e) => {
    const file = e.target.files && e.target.files[0];
    if (!file) return;
    try {
      const dataUrl = await resizeImageFileToDataUrl(file);
      const prev = document.getElementById('prof-avatar-preview');
      if (prev) prev.src = dataUrl;
      await persistAvatarChange(dataUrl);
    } catch (err) {
      showToast(err.message || 'No se pudo procesar la imagen.', true);
    } finally {
      e.target.value = '';
    }
  });

  document.getElementById('btn-apply-avatar-url')?.addEventListener('click', async () => {
    const url = document.getElementById('prof-avatar-url')?.value?.trim();
    if (!url) {
      showToast('Pega la URL de una imagen antes de aplicar.', true);
      return;
    }
    await persistAvatarChange(url);
  });

  document.getElementById('btn-reset-avatar')?.addEventListener('click', async () => {
    if (!state.currentUser) return;
    const defaultUrl = `https://api.dicebear.com/7.x/bottts/svg?seed=${encodeURIComponent(state.currentUser.username)}`;
    await persistAvatarChange(defaultUrl);
  });

  // 2. Verificación privada de teléfono
  document.getElementById('btn-request-phone-code')?.addEventListener('click', async () => {
    if (!state.currentUser) return;
    const telefono = document.getElementById('prof-phone')?.value?.trim() || '';
    try {
      const { ok, data } = await API.requestPhoneVerification(state.currentUser.username, telefono);
      if (!ok) {
        showToast((data && data.error) || 'No se pudo generar el código.', true);
        return;
      }
      const hint = document.getElementById('phone-demo-code-hint');
      const row = document.getElementById('phone-code-input-row');
      if (hint && data && data.demoCode) {
        hint.innerText = `Código demo: ${data.demoCode}`;
        hint.style.display = 'inline';
      }
      if (row) row.style.display = 'flex';
      showToast('Código generado. Ingrésalo para verificar tu teléfono privado.');
    } catch (err) {
      showToast('Error al solicitar verificación de teléfono.', true);
    }
  });

  document.getElementById('btn-verify-phone-code')?.addEventListener('click', async () => {
    if (!state.currentUser) return;
    const code = document.getElementById('prof-phone-code')?.value?.trim() || '';
    if (!code) {
      showToast('Ingresa el código de 6 dígitos.', true);
      return;
    }
    try {
      const { ok, data } = await API.verifyPhoneCode(state.currentUser.username, code);
      if (!ok) {
        showToast((data && data.error) || 'Código incorrecto.', true);
        return;
      }
      if (data && data.user) syncProfileSession(data.user);
      const hint = document.getElementById('phone-demo-code-hint');
      const row = document.getElementById('phone-code-input-row');
      const inp = document.getElementById('prof-phone-code');
      if (hint) hint.style.display = 'none';
      if (row) row.style.display = 'none';
      if (inp) inp.value = '';
      renderProfileIdentityBadges(null);
      showToast('Cuenta verificada mediante tu teléfono privado.');
      if (state.currentTab === 'database') loadDatabaseExplorer();
    } catch (err) {
      showToast('Error al confirmar el código.', true);
    }
  });

  // 3. Conexión dedicada con Discord
  document.getElementById('btn-connect-discord')?.addEventListener('click', async () => {
    if (!state.currentUser) return;
    const discordTag = document.getElementById('discord-username-input')?.value?.trim() || '';
    const discordId = document.getElementById('discord-id-input')?.value?.trim() || '';
    try {
      const { ok, data } = await API.connectDiscord(state.currentUser.username, discordTag, discordId);
      if (!ok) {
        showToast((data && data.error) || 'No se pudo conectar la cuenta de Discord.', true);
        return;
      }
      if (data && data.profile) renderProfileIdentityBadges(data.profile);
      showToast('Cuenta de Discord conectada a tu perfil.');
      loadLfgPlayers();
    } catch (err) {
      showToast('Error conectando con Discord.', true);
    }
  });

  document.getElementById('btn-disconnect-discord')?.addEventListener('click', async () => {
    if (!state.currentUser) return;
    try {
      const { ok, data } = await API.disconnectDiscord(state.currentUser.username);
      if (!ok) {
        showToast((data && data.error) || 'No se pudo desconectar Discord.', true);
        return;
      }
      if (data && data.profile) renderProfileIdentityBadges(data.profile);
      showToast('Cuenta de Discord desconectada.');
      loadLfgPlayers();
    } catch (err) {
      showToast('Error desconectando Discord.', true);
    }
  });

  // 4. Chips interactivos de gustos (géneros y preferencias sociales)
  ['prof-fav-genres-chips', 'prof-play-prefs-chips'].forEach(containerId => {
    document.getElementById(containerId)?.querySelectorAll('.taste-chip').forEach(btn => {
      btn.addEventListener('click', () => btn.classList.toggle('active'));
    });
  });

  // 5. Guardar cambios generales de cuenta privada, gustos y perfil competitivo
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
      playstyle: document.getElementById('prof-playstyle')?.value || 'COMPETITIVE',
      preferredSchedule: document.getElementById('prof-schedule').value.trim(),
      favoriteGames: [...state.favGamesSelected].join(','),
      favoriteGenres: collectTasteChipsSelection('prof-fav-genres-chips'),
      playPreferences: collectTasteChipsSelection('prof-play-prefs-chips')
    };

    try {
      const { ok, data } = await API.updateProfile(payload);
      if (!ok) {
        showToast((data && data.error) || 'Error al guardar cambios.', true);
        return;
      }

      if (data && data.user) syncProfileSession(data.user);
      if (data && data.profile) renderProfileIdentityBadges(data.profile);

      showToast('Perfil, gustos y datos privados guardados; recalculando IA...');
      loadLfgPlayers();
      loadRecommendations();
      loadGames();
    } catch (err) {
      showToast('Error al conectar con el servidor Java.', true);
    }
  });
}

// =========================================================================
// 7. MONITOR DE BASE DE DATOS
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
      : users.map(u => {
        const badgeClass = u.phoneVerified ? 'verified' : 'pending';
        const badgeText = u.phoneVerified ? 'Privado · Verificado' : (u.hasPhone ? 'Privado · Pendiente' : 'Privado · Sin número');
        return `
        <tr>
          <td><strong style="color:#fff;">@${escapeHtml(u.username)}</strong></td>
          <td>${escapeHtml(u.email)}</td>
          <td><span class="status-pill ${badgeClass}">${badgeText}</span></td>
          <td><span class="role-pill ${escapeHtml(u.role)}">${escapeHtml(u.role)}</span></td>
          <td style="font-size:0.76rem;color:var(--text-muted);">${escapeHtml(u.createdAt || 'Reciente')}</td>
        </tr>
      `;
      }).join('');

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
    console.error('Error cargando monitor de base de datos:', err);
  }
}

// =========================================================================
// 8. MODALES Y EVENTOS
// =========================================================================
function setupModals() {
  document.getElementById('modal-close-btn')?.addEventListener('click', () => closeModalById('match-modal'));
  document.getElementById('rate-modal-close-btn')?.addEventListener('click', () => closeModalById('rate-modal'));
  document.getElementById('tournament-modal-close-btn')?.addEventListener('click', () => closeModalById('join-tournament-modal'));
  document.getElementById('add-game-close-btn')?.addEventListener('click', () => closeModalById('add-game-modal'));
  document.getElementById('add-tournament-close-btn')?.addEventListener('click', () => closeModalById('add-tournament-modal'));

  document.querySelectorAll('#rate-stars-picker .rate-star-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const stars = btn.dataset.stars || '5';
      const inp = document.getElementById('rate-stars-value');
      if (inp) inp.value = stars;
      document.querySelectorAll('#rate-stars-picker .rate-star-btn').forEach(b => {
        b.classList.toggle('active', Number(b.dataset.stars) <= Number(stars));
      });
    });
  });

  document.getElementById('rate-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!state.currentUser) return;

    const ratedUsername = document.getElementById('rate-target-user').value;
    const stars = Number(document.getElementById('rate-stars-value').value || 5);
    const comment = document.getElementById('rate-comment-input').value.trim();

    try {
      const { ok, data } = await API.ratePlayer(state.currentUser.username, ratedUsername, stars, comment);
      if (!ok) {
        showToast((data && data.error) || 'No se pudo guardar la valoración.', true);
        return;
      }
      closeModalById('rate-modal');
      document.getElementById('rate-comment-input').value = '';
      showToast(`Valoración de ${stars}★ guardada para @${ratedUsername}.`);
      await Promise.all([loadLfgPlayers(), loadRecommendations()]);
    } catch (err) {
      showToast('Error al enviar la valoración.', true);
    }
  });

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
      showToast(`Invitación enviada a @${receiver} y guardada en la base de datos.`);
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
      showToast(data.message || 'Equipo inscrito en la base de datos.');
      loadTournaments();
      loadStats();
    } catch (err) {
      showToast('Error al procesar la inscripción.', true);
    }
  });

  // Registrar nuevo juego disponible en la base de datos
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
      showToast(`Juego "${gameData.name}" guardado en la base de datos.`);
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
      showToast('Torneo publicado y guardado en la base de datos.');
      await loadTournaments();
      await loadStats();
    } catch (err) {
      showToast('Error al crear el torneo.', true);
    }
  });
}

function openMatchModal(username, game) {
  document.getElementById('match-target-user').value = username;
  const gameSel = document.getElementById('match-target-game');
  let selectedLabel = game || 'PlayMatch';
  if (gameSel && gameSel.options.length > 0) {
    const targetLower = String(game || '').trim().toLowerCase();
    const matched = [...gameSel.options].find(
      o => o.value.toLowerCase() === targetLower || o.text.toLowerCase() === targetLower
    );
    if (matched) {
      gameSel.value = matched.value;
      selectedLabel = matched.text;
    } else {
      gameSel.selectedIndex = 0;
      selectedLabel = gameSel.options[0].text;
    }
  }
  document.getElementById('modal-title').innerText = `Invitar a @${username} (${selectedLabel})`;
  document.getElementById('match-modal').style.display = 'flex';
}

function openRateModal(username) {
  document.getElementById('rate-target-user').value = username;
  document.getElementById('rate-stars-value').value = '5';
  document.querySelectorAll('#rate-stars-picker .rate-star-btn').forEach(b => {
    b.classList.toggle('active', Number(b.dataset.stars) <= 5);
  });
  document.getElementById('rate-modal-title').innerText = `Valorar a @${username}`;
  document.getElementById('rate-modal').style.display = 'flex';
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
