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
  chat: { kicker: 'Mensajería Directa & Comunidad', title: 'Chats con Amigos & Salas' },
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
  chatMode: 'dm',
  igInboxTab: 'chats',
  friendsInbox: null,
  friendshipStatusesLower: {},
  allPlayersCache: [],
  lastRecommendationsData: null,
  activeDmFriend: null,
  activeDmCount: 0,
  pendingInvitesCount: 0,
  igSearchTerm: '',
  gamesCache: [],
  favGamesSelected: new Set(),
  chatInterval: null,
  presenceMap: {},
  myPresenceStatus: 'ONLINE',
  eventSource: null,
  lobbiesCache: [],
  typingTimers: {},
  dmTypingDebounce: null,
  matchesInvitesCache: [],
  dossierUsername: null,
  dossierIsBlocked: false
};

document.addEventListener('DOMContentLoaded', async () => {
  updateAuthUI();
  setupNavigation();
  setupChat();
  setupInstagramDm();
  setupModals();
  setupProfileForm();
  setupDatabaseMonitor();

  document.getElementById('btn-refresh-ai-rec')?.addEventListener('click', () => loadRecommendations(true));

  const presSel = document.getElementById('presence-status-select');
  presSel?.addEventListener('change', async () => {
    state.myPresenceStatus = presSel.value || 'ONLINE';
    updateAuthUI();
    if (state.currentUser) {
      await API.updatePresence(state.currentUser.username, state.myPresenceStatus).catch(() => {});
    }
  });

  if (new URLSearchParams(window.location.search).get('discord') === 'connected') {
    showToast('¡Cuenta de Discord conectada mediante OAuth2!');
    window.history.replaceState({}, document.title, window.location.pathname);
  }

  await Promise.all([
    loadFriendsInbox(),
    loadStats(),
    loadGames(),
    loadLfgPlayers(),
    loadLobbies(),
    loadRecommendations(),
    loadTournaments(),
    loadLeaderboard(),
    loadProfileData(),
    loadInvites()
  ]);

  initRealtimeEvents();
  setupPwaAndPush();
  setupCardSpotlight();

  // Heartbeat de presencia cada 30s y respaldo de chat si SSE no está conectado
  state.chatInterval = setInterval(() => {
    if (state.currentUser) {
      API.updatePresence(state.currentUser.username, state.myPresenceStatus).catch(() => {});
    }
    if (!state.eventSource || state.eventSource.readyState !== EventSource.OPEN) {
      if (state.currentTab === 'chat') {
        loadFriendsInbox();
        if (state.chatMode === 'dm' && state.activeDmFriend) {
          fetchActiveDmMessages(false);
        } else if (state.chatMode === 'channels') {
          fetchAndRenderChat();
        }
        loadInvites();
      }
    }
  }, 30000);
});

const FRAME_META = {
  NONE: { cls: 'card-frame-none', pillCls: '', label: 'Titanio Base' },
  GOLD: { cls: 'card-frame-gold', pillCls: 'gold', label: 'Oro' },
  EMERALD: { cls: 'card-frame-emerald', pillCls: 'emerald', label: 'Esmeralda' },
  RUBY: { cls: 'card-frame-ruby', pillCls: 'ruby', label: 'Rubí' },
  DIAMOND: { cls: 'card-frame-diamond', pillCls: 'diamond', label: 'Diamante' },
  CHALLENGER: { cls: 'card-frame-challenger', pillCls: 'challenger', label: 'Retador' }
};

function getFrameInfo(raw) {
  const key = String(raw || 'NONE').trim().toUpperCase();
  return FRAME_META[key] || FRAME_META.NONE;
}

function defaultAvatarUrl(username) {
  return `/api/avatar?u=${encodeURIComponent(username || 'PlayMatch')}`;
}

function getGameCoverArtwork(gameCode) {
  const clean = String(gameCode || '').trim().toLowerCase();
  const cached = (state.gamesCache || []).find(g => (g.code || '').toLowerCase() === clean);
  if (cached && cached.bannerUrl) return cached.bannerUrl;
  const map = {
    valorant: '/assets/games/valorant.jpg',
    lol: '/assets/games/lol.jpg',
    cs2: '/assets/games/cs2.jpg',
    'rocket-league': '/assets/games/rocket-league.jpg',
    'marvel-rivals': '/assets/games/marvel-rivals.jpg',
    overwatch: '/assets/games/overwatch.jpg',
    'apex-legends': '/assets/games/apex-legends.jpg',
    apex: '/assets/games/apex-legends.jpg',
    fortnite: '/assets/games/fortnite.jpg',
    roblox: '/assets/games/roblox.jpg',
    minecraft: '/assets/games/minecraft.jpg'
  };
  return map[clean] || '/assets/games/valorant.jpg';
}

function getRankTierClass(rankStr) {
  const r = String(rankStr || '').toLowerCase();
  if (r.includes('radiant') || r.includes('retador') || r.includes('challenger') || r.includes('maestro')) return 'rank-tier-radiant';
  if (r.includes('inmortal') || r.includes('immortal')) return 'rank-tier-immortal';
  if (r.includes('ascendente') || r.includes('esmeralda') || r.includes('emerald')) return 'rank-tier-ascendant';
  if (r.includes('diamante') || r.includes('diamond')) return 'rank-tier-diamond';
  if (r.includes('platino') || r.includes('platinum')) return 'rank-tier-platinum';
  if (r.includes('oro') || r.includes('gold') || r.includes('faceit')) return 'rank-tier-gold';
  return '';
}

function renderSkeletonCards(container, count = 3) {
  if (!container) return;
  container.innerHTML = Array.from({ length: count }).map(() => `
    <div class="skeleton-card">
      <div style="display:flex;align-items:center;gap:0.75rem;">
        <div class="skeleton-avatar"></div>
        <div style="flex:1;display:grid;gap:0.45rem;">
          <div class="skeleton-line" style="height:14px;width:55%;"></div>
          <div class="skeleton-line" style="height:11px;width:38%;"></div>
        </div>
      </div>
      <div class="skeleton-line" style="height:56px;width:100%;"></div>
      <div class="skeleton-line" style="height:28px;width:82%;"></div>
    </div>
  `).join('');
}

function setupCardSpotlight() {
  document.addEventListener('mousemove', (e) => {
    const card = e.target.closest?.('.player-card, .ai-rec-card, .lobby-card');
    if (!card) return;
    const rect = card.getBoundingClientRect();
    card.style.setProperty('--mx', `${Math.round(e.clientX - rect.left)}px`);
    card.style.setProperty('--my', `${Math.round(e.clientY - rect.top)}px`);
  });
}

function updateAuthUI() {
  const navName = document.getElementById('nav-user-name');
  const navAvatar = document.getElementById('nav-user-avatar');
  const navRole = document.getElementById('nav-user-role');
  const igHandle = document.getElementById('ig-self-handle');
  const topDot = document.getElementById('topbar-presence-dot');

  const stLabels = { ONLINE: 'En línea', LOOKING: 'Buscando grupo', INGAME: 'En partida', OFFLINE: 'Desconectado' };
  const stCls = { ONLINE: 'online', LOOKING: 'looking', INGAME: 'ingame', OFFLINE: 'offline' };
  const stText = stLabels[state.myPresenceStatus] || 'En línea';

  if (topDot) {
    topDot.className = `presence-dot-mini ${stCls[state.myPresenceStatus] || 'online'}`;
  }
  if (state.currentUser) {
    if (navName) navName.innerText = state.currentUser.username;
    if (navAvatar) navAvatar.src = state.currentUser.avatar || defaultAvatarUrl(state.currentUser.username);
    if (navRole) navRole.innerText = `${state.currentUser.role || 'GAMER'} · ${stText}`;
    if (igHandle) igHandle.innerText = `@${state.currentUser.username}`;
  }
}

function initRealtimeEvents() {
  if (!state.currentUser || typeof EventSource === 'undefined') return;
  try {
    if (state.eventSource) {
      state.eventSource.close();
    }
    const es = new EventSource(`/api/events?username=${encodeURIComponent(state.currentUser.username)}`);
    state.eventSource = es;

    es.addEventListener('connected', (e) => {
      try {
        const d = JSON.parse(e.data || '{}');
        if (d.presence && typeof d.presence === 'object') {
          Object.keys(d.presence).forEach(k => {
            state.presenceMap[k.toLowerCase()] = d.presence[k];
          });
          refreshVisiblePresenceDots();
        }
      } catch (err) {}
    });

    es.addEventListener('presence', (e) => {
      try {
        const p = JSON.parse(e.data || '{}');
        if (p.username) {
          state.presenceMap[p.username.toLowerCase()] = p;
          refreshVisiblePresenceDots();
        }
      } catch (err) {}
    });

    es.addEventListener('dm', (e) => {
      try {
        const d = JSON.parse(e.data || '{}');
        loadFriendsInbox();
        if (d.sender && state.currentUser && d.sender.toLowerCase() !== state.currentUser.username.toLowerCase() &&
            (document.hidden || state.currentTab !== 'chat' || !state.activeDmFriend || state.activeDmFriend.toLowerCase() !== d.sender.toLowerCase())) {
          showBrowserPushNotification(`Mensaje directo de @${d.sender}`, d.dm?.content || 'Nuevo mensaje en PlayMatch');
        }
        if (state.activeDmFriend && d.sender &&
            (d.sender.toLowerCase() === state.activeDmFriend.toLowerCase() ||
             (d.receiver && d.receiver.toLowerCase() === state.activeDmFriend.toLowerCase()))) {
          fetchActiveDmMessages(true);
        }
      } catch (err) {}
    });

    es.addEventListener('dm_read', (e) => {
      try {
        const d = JSON.parse(e.data || '{}');
        if (state.activeDmFriend && d.reader && d.reader.toLowerCase() === state.activeDmFriend.toLowerCase()) {
          fetchActiveDmMessages(false);
        }
      } catch (err) {}
    });

    es.addEventListener('typing', (e) => {
      try {
        const d = JSON.parse(e.data || '{}');
        if (d.sender && state.activeDmFriend && d.sender.toLowerCase() === state.activeDmFriend.toLowerCase()) {
          showDmTypingIndicator(d.sender, Boolean(d.typing));
        }
      } catch (err) {}
    });

    es.addEventListener('chat', (e) => {
      try {
        const d = JSON.parse(e.data || '{}');
        if (state.currentTab === 'chat' && state.chatMode === 'channels' &&
            (!d.channel || d.channel === state.activeChannel)) {
          fetchAndRenderChat();
        }
      } catch (err) {}
    });

    es.addEventListener('notification', (e) => {
      try {
        const d = JSON.parse(e.data || '{}');
        loadFriendsInbox();
        loadInvites();
        if (d.type === 'friend_request' && d.from) {
          showBrowserPushNotification('Nueva solicitud de amistad', `@${d.from} te envió una solicitud en PlayMatch`);
        } else if (d.type === 'match_invite' && d.from) {
          showBrowserPushNotification('Nueva invitación LFG', `@${d.from} te invitó a jugar ${d.game || 'PlayMatch'}`);
        }
        if (d.type === 'moderation_update') {
          loadLfgPlayers();
          loadRecommendations();
          loadBlockedUsers();
        }
      } catch (err) {}
    });

    es.addEventListener('lobbies_update', () => {
      loadLobbies();
    });

    es.addEventListener('tournament_update', () => {
      loadTournaments();
    });
  } catch (err) {
    console.warn('SSE no disponible:', err);
  }
}

let deferredPwaPrompt = null;
let swRegistration = null;

function setupPwaAndPush() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js')
      .then((reg) => {
        swRegistration = reg;
        loadIntegrationsStatus();
      })
      .catch(() => {});
  }

  const updateInstallVisibility = (show) => {
    const topBtn = document.getElementById('btn-install-pwa');
    const profBtn = document.getElementById('btn-profile-install-pwa');
    if (topBtn) topBtn.style.display = show ? 'inline-flex' : 'none';
    if (profBtn) profBtn.style.display = show ? 'inline-flex' : 'none';
  };

  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPwaPrompt = e;
    updateInstallVisibility(true);
  });

  window.addEventListener('appinstalled', () => {
    deferredPwaPrompt = null;
    updateInstallVisibility(false);
    showToast('¡PlayMatch instalado en tu dispositivo!');
  });

  const triggerInstall = async () => {
    if (!deferredPwaPrompt) return;
    deferredPwaPrompt.prompt();
    try {
      await deferredPwaPrompt.userChoice;
    } catch (err) {}
    deferredPwaPrompt = null;
    updateInstallVisibility(false);
  };

  document.getElementById('btn-install-pwa')?.addEventListener('click', triggerInstall);
  document.getElementById('btn-profile-install-pwa')?.addEventListener('click', triggerInstall);

  document.getElementById('btn-enable-browser-push')?.addEventListener('click', async () => {
    if (typeof Notification === 'undefined') {
      showToast('Este navegador no soporta notificaciones nativas.', true);
      return;
    }
    const perm = await Notification.requestPermission();
    loadIntegrationsStatus();
    if (perm === 'granted') {
      showToast('Notificaciones Push del navegador activadas.');
      showBrowserPushNotification('PlayMatch Notificaciones Activas', 'Recibirás alertas instantáneas de DMs e invitaciones.');
    } else {
      showToast('Permiso de notificaciones no concedido en el navegador.', true);
    }
  });
}

function showBrowserPushNotification(title, body) {
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
  try {
    const opts = {
      body: body || '',
      icon: '/assets/icons/icon-192.png',
      badge: '/assets/icons/icon-192.png',
      tag: 'playmatch-alert',
      data: { url: '/index.html' }
    };
    if (swRegistration && swRegistration.showNotification) {
      swRegistration.showNotification(title, opts);
    } else {
      new Notification(title, opts);
    }
  } catch (err) {}
}

function getPresenceInfo(username) {
  if (!username) return { status: 'OFFLINE', label: 'Desconectado', cls: 'offline' };
  const p = state.presenceMap[String(username).toLowerCase()];
  const st = (p && p.status) ? String(p.status).toUpperCase() : 'OFFLINE';
  if (st === 'LOOKING') return { status: 'LOOKING', label: 'Buscando grupo', cls: 'looking' };
  if (st === 'INGAME') return { status: 'INGAME', label: 'En partida', cls: 'ingame' };
  if (st === 'ONLINE') return { status: 'ONLINE', label: 'En línea', cls: 'online' };
  return { status: 'OFFLINE', label: 'Desconectado', cls: 'offline' };
}

function refreshVisiblePresenceDots() {
  document.querySelectorAll('[data-presence-user]').forEach(el => {
    const u = el.dataset.presenceUser;
    const info = getPresenceInfo(u);
    el.classList.remove('online', 'looking', 'ingame', 'offline');
    el.classList.add(info.cls);
    el.title = info.label;
  });
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
    loadLobbies();
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

  document.getElementById('btn-quick-invites')?.addEventListener('click', (e) => {
    e.stopPropagation();
    const pop = document.getElementById('notifications-popover');
    if (!pop) {
      switchTab('chat');
      return;
    }
    const isOpen = pop.style.display === 'block';
    pop.style.display = isOpen ? 'none' : 'block';
    if (!isOpen) renderNotificationsPopover();
  });
  document.getElementById('btn-notif-close')?.addEventListener('click', () => {
    const pop = document.getElementById('notifications-popover');
    if (pop) pop.style.display = 'none';
  });
  document.getElementById('btn-notif-open-chat')?.addEventListener('click', () => {
    const pop = document.getElementById('notifications-popover');
    if (pop) pop.style.display = 'none';
    switchTab('chat');
  });
  document.addEventListener('click', (e) => {
    const pop = document.getElementById('notifications-popover');
    const anchor = document.querySelector('.notif-anchor');
    if (pop && pop.style.display === 'block' && anchor && !anchor.contains(e.target)) {
      pop.style.display = 'none';
    }
  });
  document.getElementById('btn-quick-add-game')?.addEventListener('click', openAddGameModal);
  document.getElementById('btn-open-add-game')?.addEventListener('click', openAddGameModal);
  document.getElementById('btn-open-add-tournament')?.addEventListener('click', openAddTournamentModal);
  document.getElementById('btn-open-create-lobby')?.addEventListener('click', openCreateLobbyModal);
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

  if (tabId === 'lfg') {
    loadLobbies();
    loadLfgPlayers();
  } else if (tabId === 'chat') {
    loadFriendsInbox();
    loadLobbies();
    if (state.chatMode === 'dm' && state.activeDmFriend) {
      fetchActiveDmMessages(true);
    } else if (state.chatMode === 'channels') {
      fetchAndRenderChat();
    }
    loadInvites();
  } else if (tabId === 'tournaments') {
    loadTournaments();
    loadLeaderboard();
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
    setText('badge-players-count', stats.profiles ?? stats.users);
    setText('badge-games-count', stats.games);
    setText('badge-tournaments-count', stats.tournaments);
    setText('hero-stat-players', stats.profiles ?? stats.users);
    setText('hero-stat-lobbies', stats.lobbies ?? state.lobbiesCache?.length ?? 0);
    setText('hero-stat-tournaments', stats.tournaments);

    const liveLabel = document.getElementById('db-live-label');
    if (liveLabel) {
      liveLabel.innerText = stats.isPostgres ? 'PostgreSQL · Activa' : 'SQLite Local · Activa';
    }
    const monEl = document.getElementById('db-monitor-engine-name');
    if (monEl) {
      monEl.innerText = stats.dbEngine || 'SQL';
    }
  } catch (err) {
    console.error('Error cargando estadísticas:', err);
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

  ['prof-game', 'new-t-game', 'match-target-game', 'lobby-game', 'clip-game-select'].forEach(selId => {
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
  if (!grid.children.length) renderSkeletonCards(grid, 3);

  try {
    const data = await API.getRecommendations(state.currentUser.username);
    if (!data || data.error) return;
    renderRecommendations(data);
    if (showFeedback) {
      showToast('Recomendaciones actualizadas según tus gustos y valoración.');
    }
  } catch (err) {
    console.error('Error cargando recomendaciones:', err);
  }
}

function extractClipMeta(url) {
  const clean = String(url || '').trim();
  let ytId = '';
  const m1 = clean.match(/(?:youtube\.com\/watch\?v=|youtu\.be\/|youtube\.com\/shorts\/)([A-Za-z0-9_-]{6,15})/i);
  if (m1) ytId = m1[1];
  let platform = 'Video';
  if (ytId || /youtube\.com|youtu\.be/i.test(clean)) platform = 'YouTube';
  else if (/twitch\.tv/i.test(clean)) platform = 'Twitch';
  else if (/medal\.tv/i.test(clean)) platform = 'Medal';
  else if (/streamable\.com/i.test(clean)) platform = 'Streamable';
  return {
    platform,
    ytId,
    thumbUrl: ytId ? `https://i.ytimg.com/vi/${ytId}/hqdefault.jpg` : ''
  };
}

function renderRecommendations(data) {
  state.lastRecommendationsData = data;
  const summaryEl = document.getElementById('ai-rec-summary');
  const warnEl = document.getElementById('ai-low-rating-banner');
  const gamesWrap = document.getElementById('ai-suggested-games');
  const grid = document.getElementById('ai-recommendations-grid');
  if (!grid) return;

  if (summaryEl) {
    summaryEl.innerText = data.summary || 'Afinidad calculada según juego principal, horario y valoraciones.';
  }
  if (warnEl) {
    if (data.currentUserLowVisibility) {
      warnEl.style.display = 'block';
      warnEl.innerText = `Aviso: Con ${Number(data.currentUserRating || 0).toFixed(1)}★ tu perfil recibe menor visibilidad hasta superar 2.0★.`;
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
    grid.innerHTML = '<div style="color:var(--text-secondary);font-size:0.84rem;">No hay otros jugadores disponibles todavía.</div>';
    return;
  }

  grid.innerHTML = '';
  recs.slice(0, 6).forEach(rec => {
    const isLow = Boolean(rec.lowRatedPenalty || Number(rec.starRating) < 2.0 || rec.visibilityTier === 'REDUCIDA');
    const frameInfo = getFrameInfo(rec.cardFrame);
    const card = document.createElement('div');
    card.className = `ai-rec-card ${frameInfo.cls} ${isLow ? 'low-rated' : ''}`;

    const gNameLower = String(rec.gameName || rec.game || '').trim().toLowerCase();
    const seenTags = new Set([gNameLower]);
    const rawTags = Array.isArray(rec.sharedTags) ? rec.sharedTags : [];
    const cleanTags = [];
    for (const rawT of rawTags) {
      const k = String(rawT || '').trim().toLowerCase();
      if (!k || seenTags.has(k)) continue;
      seenTags.add(k);
      const formatted = String(rawT).trim().replace(/\b\w/g, c => c.toUpperCase());
      cleanTags.push(formatted);
      if (cleanTags.length >= 2) break;
    }

    const scoreNum = Math.max(0, Math.min(100, Number(rec.matchScore || 0)));

    card.innerHTML = `
      <div class="ai-rec-card-top">
        <div class="ai-rec-user-info">
          <img src="${escapeHtml(rec.avatarUrl || defaultAvatarUrl(rec.username))}" alt="${escapeHtml(rec.username)}" class="clickable-player-handle" data-profile-user="${escapeHtml(rec.username)}">
          <div style="min-width:0;">
            <strong class="clickable-player-handle" data-profile-user="${escapeHtml(rec.username)}" style="color:#fff;font-size:0.9rem;display:flex;align-items:center;gap:0.35rem;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">
              <span class="presence-dot-mini ${getPresenceInfo(rec.username).cls}" data-presence-user="${escapeHtml(rec.username)}"></span>
              @${escapeHtml(rec.username)}
            </strong>
            <div style="font-size:0.72rem;color:var(--text-secondary);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">
              ${escapeHtml(rec.gameName || rec.game)} · <span class="${getRankTierClass(rec.rank)}">${escapeHtml(rec.rank || 'Competitivo')}</span> · ${Number(rec.starRating || 0).toFixed(1)}★
              ${isLow ? '<span style="color:#fca5a5;"> · Reducida</span>' : ''}
            </div>
          </div>
        </div>
        <span class="rec-match-badge ${isLow ? 'low' : ''}">${scoreNum}%</span>
      </div>

      <p class="ai-rec-reason">${escapeHtml(rec.equippedTitle ? `${rec.equippedTitle} · ${rec.reason}` : rec.reason)}</p>

      <div class="player-chips-row" style="margin-bottom:0.15rem;">
        ${frameInfo.pillCls ? `<span class="frame-tier-pill ${frameInfo.pillCls}">${escapeHtml(frameInfo.label)}</span>` : ''}
        ${cleanTags.map(t => `<span class="chip-plat">${escapeHtml(t)}</span>`).join('')}
      </div>

      <div class="ai-rec-actions">
        <button type="button" class="btn btn-primary btn-sm btn-ai-invite" data-user="${escapeHtml(rec.username)}" data-game="${escapeHtml(rec.gameName || rec.game)}" style="flex:1;">Invitar</button>
        ${buildFriendActionButtonHtml(rec.username)}
        <button type="button" class="btn btn-secondary btn-sm btn-ai-rate" data-user="${escapeHtml(rec.username)}">★</button>
      </div>
    `;

    card.querySelectorAll('[data-profile-user]').forEach(el => {
      el.addEventListener('click', () => openPublicProfile(el.dataset.profileUser));
    });
    card.querySelector('.btn-ai-invite')?.addEventListener('click', (e) => {
      const b = e.currentTarget;
      openMatchModal(b.dataset.user, b.dataset.game);
    });
    card.querySelector('.btn-ai-rate')?.addEventListener('click', (e) => {
      openRateModal(e.currentTarget.dataset.user);
    });
    bindFriendActionButtons(card);
    grid.appendChild(card);
  });
}

async function loadLobbies() {
  const grid = document.getElementById('lobbies-grid');
  if (grid && !grid.children.length) renderSkeletonCards(grid, 3);
  try {
    const viewer = state.currentUser?.username || '';
    const lobbies = await API.getLobbies(state.selectedGameFilter || 'all', viewer);
    state.lobbiesCache = Array.isArray(lobbies) ? lobbies : [];
    renderLobbyChannelsSidebar(state.lobbiesCache);
    if (!grid) return;

    if (state.lobbiesCache.length === 0) {
      grid.innerHTML = '<div style="color:var(--text-muted);font-size:0.84rem;grid-column:1/-1;">No hay lobbies abiertos en este filtro. ¡Crea el primero con "+ Crear Lobby"!</div>';
      return;
    }
    grid.innerHTML = '';
    state.lobbiesCache.forEach(lob => {
      const isFull = Number(lob.currentCount) >= Number(lob.maxSlots);
      const card = document.createElement('div');
      card.className = 'lobby-card';
      const memHtml = (lob.members || []).slice(0, 6).map(m =>
        `<img src="${escapeHtml(m.avatarUrl)}" alt="${escapeHtml(m.username)}" title="@${escapeHtml(m.username)} (${escapeHtml(m.roleNote || 'Miembro')})" class="lobby-member-avatar clickable-player-handle" data-profile-user="${escapeHtml(m.username)}">`
      ).join('');

      card.innerHTML = `
        <div class="lobby-card-top">
          <span class="badge-game ${escapeHtml(lob.game)}">${escapeHtml(lob.gameName)}</span>
          <span class="lobby-slots-pill ${isFull ? 'full' : ''}">${Number(lob.currentCount)}/${Number(lob.maxSlots)} cupos</span>
        </div>
        <div>
          <strong style="color:#fff;font-size:0.95rem;display:block;">${escapeHtml(lob.title)}</strong>
          <span style="font-size:0.75rem;color:var(--text-secondary);">
            Host: <span class="clickable-player-handle" data-profile-user="${escapeHtml(lob.hostUsername)}" style="color:var(--color-cyan);">@${escapeHtml(lob.hostUsername)}</span> · ${escapeHtml(lob.mode)} · ${escapeHtml(lob.scheduleLabel)}
          </span>
        </div>
        ${lob.rolesNeeded ? `<div style="font-size:0.74rem;color:var(--text-muted);">Busca: <span style="color:#e2e8f0;">${escapeHtml(lob.rolesNeeded)}</span></div>` : ''}
        <div class="lobby-members-strip">${memHtml}</div>
        <div style="display:flex;gap:0.45rem;margin-top:auto;">
          ${lob.joined ? `
            <button type="button" class="btn btn-primary btn-sm btn-open-lobby-chat" data-id="${escapeHtml(lob.id)}" data-title="${escapeHtml(lob.title)}" style="flex:1;">Abrir Chat Grupal</button>
            <button type="button" class="btn btn-secondary btn-sm btn-leave-lobby" data-id="${escapeHtml(lob.id)}">Salir</button>
          ` : `
            <button type="button" class="btn btn-primary btn-sm btn-join-lobby" data-id="${escapeHtml(lob.id)}" ${isFull ? 'disabled' : ''} style="flex:1;">
              ${isFull ? 'Sala Llena' : 'Unirse al Lobby'}
            </button>
          `}
        </div>
      `;
      card.querySelectorAll('[data-profile-user]').forEach(el => {
        el.addEventListener('click', () => openPublicProfile(el.dataset.profileUser));
      });
      card.querySelector('.btn-join-lobby')?.addEventListener('click', async () => {
        if (!state.currentUser) return;
        const { ok, data } = await API.joinLobby(lob.id, state.currentUser.username);
        if (!ok) {
          showToast((data && data.error) || 'No se pudo unir al lobby.', true);
          return;
        }
        showToast(`Te uniste a "${lob.title}". Chat grupal habilitado.`);
        await loadLobbies();
      });
      card.querySelector('.btn-leave-lobby')?.addEventListener('click', async () => {
        if (!state.currentUser) return;
        const { ok, data } = await API.leaveLobby(lob.id, state.currentUser.username);
        if (!ok) {
          showToast((data && data.error) || 'No se pudo salir del lobby.', true);
          return;
        }
        showToast(`Saliste del lobby "${lob.title}".`);
        await loadLobbies();
      });
      card.querySelector('.btn-open-lobby-chat')?.addEventListener('click', () => {
        openLobbyGroupChat(lob.id, lob.title);
      });
      grid.appendChild(card);
    });
  } catch (err) {
    console.error('Error cargando lobbies:', err);
  }
}

function renderLobbyChannelsSidebar(lobbies) {
  const wrap = document.getElementById('lobby-channels-list');
  if (!wrap) return;
  const mine = (lobbies || []).filter(l => l.joined);
  if (mine.length === 0) {
    wrap.innerHTML = '<div style="font-size:0.74rem;color:var(--text-muted);padding:0.35rem 0.6rem;">Únete o crea un lobby en LFG para abrir su chat grupal.</div>';
    return;
  }
  wrap.innerHTML = '';
  mine.forEach(lob => {
    const ch = `lobby-${lob.id}`;
    const btn = document.createElement('button');
    btn.className = `channel-list-item ${state.activeChannel === ch ? 'active' : ''}`;
    btn.dataset.channel = ch;
    btn.dataset.label = `# ${lob.title}`;
    btn.dataset.desc = `Chat grupal del lobby (${lob.gameName} · ${lob.currentCount}/${lob.maxSlots} cupos).`;
    btn.innerHTML = `<span># ${escapeHtml(lob.title)}</span><span class="channel-tag squad">${Number(lob.currentCount)}/${Number(lob.maxSlots)}</span>`;
    btn.addEventListener('click', () => selectChatChannel(ch, btn.dataset.label, btn.dataset.desc, btn));
    wrap.appendChild(btn);
  });
}

function openLobbyGroupChat(lobbyId, title) {
  switchTab('chat');
  setChatMode('channels');
  const ch = `lobby-${lobbyId}`;
  const label = `# ${title || 'Lobby de Escuadra'}`;
  const desc = 'Chat grupal privado para coordinar la partida del lobby.';
  const existingBtn = document.querySelector(`.channel-list-item[data-channel="${CSS.escape(ch)}"]`);
  selectChatChannel(ch, label, desc, existingBtn || null);
}

async function openPublicProfile(username) {
  if (!username) return;
  try {
    const viewer = state.currentUser?.username || '';
    const { ok, data } = await API.getPlayerDossier(username, viewer);
    if (!ok || !data) {
      showToast('No se pudo cargar el dossier del jugador.', true);
      return;
    }
    state.dossierUsername = data.username;
    state.dossierIsBlocked = Boolean(data.isBlocked);
    if (data.presenceStatus) {
      state.presenceMap[data.username.toLowerCase()] = { status: data.presenceStatus, activity: data.presenceActivity || '' };
    }

    const pres = getPresenceInfo(data.username);
    const frameInfo = getFrameInfo(data.cardFrame);
    const dossierCard = document.getElementById('public-profile-card');
    if (dossierCard) {
      Object.values(FRAME_META).forEach(m => dossierCard.classList.remove(m.cls));
      dossierCard.classList.add(frameInfo.cls);
    }
    const dossierCover = document.getElementById('dossier-cover');
    if (dossierCover) {
      dossierCover.style.backgroundImage = `linear-gradient(180deg, rgba(10, 15, 27, 0.28), rgba(10, 15, 27, 0.94)), url("${getGameCoverArtwork(data.game)}")`;
    }

    document.getElementById('dossier-avatar').src = data.avatarUrl || defaultAvatarUrl(data.username);
    const dot = document.getElementById('dossier-presence-dot');
    dot.className = `presence-dot-lg ${pres.cls}`;
    document.getElementById('dossier-username').innerText = `@${data.username}`;
    const dossierTitleEl = document.getElementById('dossier-title-pill');
    if (dossierTitleEl) {
      if (data.equippedTitle) {
        dossierTitleEl.innerText = data.equippedTitle;
        dossierTitleEl.style.display = 'inline-flex';
      } else {
        dossierTitleEl.style.display = 'none';
      }
    }
    document.getElementById('dossier-presence-label').innerText = pres.label;
    const verPill = document.getElementById('dossier-verified-pill');
    if (verPill) {
      verPill.style.display = data.phoneVerified ? 'inline-flex' : 'none';
      verPill.className = 'status-pill verified';
      verPill.innerText = 'Cuenta Verificada';
    }
    document.getElementById('dossier-submeta').innerText =
      `${data.gameName || data.game || 'PlayMatch'} · ${data.rank || 'Competitivo'} · ${data.role || 'Flexible'} · ${data.region || 'LATAM'}`;

    const badgesRow = document.getElementById('dossier-badges-row');
    badgesRow.innerHTML = `
      <span class="badge-game ${escapeHtml(data.game || '')}">${escapeHtml(data.gameName || 'Multi-Juego')}</span>
      ${frameInfo.pillCls ? `<span class="frame-tier-pill ${frameInfo.pillCls}">Marco ${escapeHtml(frameInfo.label)}</span>` : ''}
      <span class="chip-plat ${getRankTierClass(data.rank)}">${escapeHtml(data.rank || 'Competitivo')}</span>
      <span class="chip-plat">Estilo: ${escapeHtml(data.playstyle || 'COMPETITIVE')}</span>
      <span class="chip-plat">Horario: ${escapeHtml(data.preferredSchedule || 'Noches')}</span>
      <span class="chip-plat">Reputación: ${Number(data.reputationScore || 100)} pts</span>
    `;

    document.getElementById('dossier-star-score').innerText = `${Number(data.starRating || 4.8).toFixed(1)} ★`;
    document.getElementById('dossier-rating-count').innerText = `${Number(data.ratingCount || 0)} reseñas`;
    document.getElementById('dossier-bio').innerText = data.bio || 'Jugador activo en la arena competitiva PlayMatch.';

    const trophiesRow = document.getElementById('dossier-trophies-row');
    if (trophiesRow) {
      const trophies = Array.isArray(data.trophies) ? data.trophies : [];
      if (trophies.length > 0) {
        trophiesRow.style.display = 'flex';
        trophiesRow.innerHTML = trophies.map(tr =>
          `<span class="trophy-badge" title="${escapeHtml(tr.gameName)} · Equipo ${escapeHtml(tr.teamName)}"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9H4.5a2.5 2.5 0 0 1 0-5H6"/><path d="M18 9h1.5a2.5 2.5 0 0 0 0-5H18"/><path d="M4 22h16"/><path d="M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22"/><path d="M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22"/><path d="M18 2H6v7a6 6 0 0 0 12 0V2Z"/></svg> Campeón · ${escapeHtml(tr.title)}</span>`
        ).join('');
      } else {
        trophiesRow.style.display = 'none';
        trophiesRow.innerHTML = '';
      }
    }

    const tastesRow = document.getElementById('dossier-tastes-row');
    const seenTastes = new Set();
    const tasteTags = [];
    [data.favoriteGames, data.favoriteGenres, data.playPreferences].forEach(csv => {
      String(csv || '').split(',').filter(Boolean).slice(0, 4).forEach(raw => {
        const clean = raw.trim();
        const key = clean.toLowerCase();
        if (clean && !seenTastes.has(key)) {
          seenTastes.add(key);
          const pretty = clean.replace(/(^|\s|-)\S/g, ch => ch.toUpperCase()).replace(/-/g, ' ');
          tasteTags.push(pretty);
        }
      });
    });
    tastesRow.innerHTML = tasteTags.slice(0, 8).map(t => `<span class="chip-plat">${escapeHtml(t)}</span>`).join('');

    const idsRow = document.getElementById('dossier-ids-row');
    idsRow.innerHTML = `
      ${(data.discordConnected || data.discordTag) ? `<button type="button" class="chip-discord" id="dossier-copy-dc">Discord: @${escapeHtml(data.discordTag)}</button>` : ''}
      ${data.riotId ? `<span class="chip-plat">Riot ID: ${escapeHtml(data.riotId)}</span>` : ''}
      ${data.steamId ? `<span class="chip-plat">Steam: ${escapeHtml(data.steamId)}</span>` : ''}
    `;
    document.getElementById('dossier-copy-dc')?.addEventListener('click', () => {
      if (data.discordTag && navigator.clipboard?.writeText) {
        navigator.clipboard.writeText(data.discordTag).then(() => showToast(`Discord @${data.discordTag} copiado.`));
      }
    });

    const isSelf = state.currentUser && data.username.toLowerCase() === state.currentUser.username.toLowerCase();
    const actBar = document.querySelector('.dossier-actions-bar');
    if (actBar) actBar.style.display = isSelf ? 'none' : 'flex';

    const chatBtn = document.getElementById('dossier-btn-chat');
    if (chatBtn) {
      if (data.friendshipStatus === 'ACCEPTED') chatBtn.innerText = 'Abrir Chat Directo';
      else if (data.friendshipStatus === 'PENDING_OUTGOING') chatBtn.innerText = 'Solicitud Enviada';
      else if (data.friendshipStatus === 'PENDING_INCOMING') chatBtn.innerText = 'Aceptar Amigo';
      else chatBtn.innerText = '+ Agregar Amigo';
    }
    const blkBtn = document.getElementById('dossier-btn-block');
    if (blkBtn) blkBtn.innerText = data.isBlocked ? 'Desbloquear' : 'Bloquear';

    const clipsSec = document.getElementById('dossier-clips-section');
    const clipsList = document.getElementById('dossier-clips-list');
    if (clipsSec && clipsList) {
      const clips = Array.isArray(data.clips) ? data.clips : [];
      if (clips.length === 0) {
        clipsSec.style.display = 'none';
        clipsList.innerHTML = '';
      } else {
        clipsSec.style.display = 'block';
        clipsList.innerHTML = clips.map(cl => {
          const meta = extractClipMeta(cl.clipUrl);
          return `
            <div class="dossier-clip-card">
              ${meta.thumbUrl ? `
                <div class="clip-thumb-wrap">
                  <img src="${escapeHtml(meta.thumbUrl)}" alt="${escapeHtml(cl.title)}" onerror="this.style.display='none'">
                </div>
              ` : ''}
              <div style="display:flex;justify-content:space-between;align-items:center;gap:0.4rem;">
                <span class="clip-play-tag">${escapeHtml(meta.platform)}</span>
                <span style="font-size:0.7rem;color:var(--text-muted);">${escapeHtml(cl.gameName || cl.gameCode)}</span>
              </div>
              <strong style="font-size:0.82rem;color:#fff;display:block;">${escapeHtml(cl.title)}</strong>
              <a href="${escapeHtml(cl.clipUrl)}" target="_blank" rel="noopener noreferrer" class="btn btn-secondary btn-sm" style="width:100%;justify-content:center;">Ver Highlight</a>
            </div>
          `;
        }).join('');
      }
    }

    const revWrap = document.getElementById('dossier-reviews-list');
    const reviews = Array.isArray(data.reviews) ? data.reviews : [];
    if (reviews.length === 0) {
      revWrap.innerHTML = '<div style="font-size:0.8rem;color:var(--text-muted);">Aún no hay reseñas escritas para este jugador.</div>';
    } else {
      revWrap.innerHTML = reviews.map(rv => `
        <div class="dossier-review-card">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:0.2rem;">
            <strong style="font-size:0.8rem;color:#fff;">@${escapeHtml(rv.rater)}</strong>
            <span style="color:#fbbf24;font-weight:700;font-size:0.8rem;">${'★'.repeat(Number(rv.stars || 5))} (${Number(rv.stars)})</span>
          </div>
          <div style="font-size:0.78rem;color:var(--text-secondary);">${escapeHtml(rv.comment || 'Sin comentario adicional.')}</div>
        </div>
      `).join('');
    }

    document.getElementById('public-profile-modal').style.display = 'flex';
  } catch (err) {
    showToast('Error abriendo perfil público.', true);
  }
}

async function loadLfgPlayers() {
  const container = document.getElementById('players-grid');
  if (!container) return;

  renderSkeletonCards(container, 6);

  try {
    const q = (state.currentTab === 'lfg') ? state.globalSearch : '';
    const viewer = state.currentUser?.username || '';
    const players = await API.getPlayers(state.selectedGameFilter, state.selectedToxicFilter, q, viewer);
    if (Array.isArray(players)) {
      players.forEach(p => {
        if (p.presenceStatus) {
          state.presenceMap[p.username.toLowerCase()] = { status: p.presenceStatus, activity: p.presenceActivity || '' };
        }
      });
    }
    if (state.selectedGameFilter === 'all' && !state.selectedToxicFilter && !q) {
      state.allPlayersCache = Array.isArray(players) ? players : [];
    }
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
  const frameInfo = getFrameInfo(player.cardFrame);
  div.className = `player-card ${frameInfo.cls}`;
  const isCurrentUser = Boolean(state.currentUser && player.username.toLowerCase() === state.currentUser.username.toLowerCase());
  const stars = Number(player.starRating !== undefined ? player.starRating : 4.8);
  const isLowRating = stars < 2.0;
  const coverUrl = getGameCoverArtwork(player.game);
  const rankTierCls = getRankTierClass(player.rank);

  div.innerHTML = `
    <div class="player-card-cover" data-game="${escapeHtml(player.game)}" style="background-image: linear-gradient(180deg, rgba(16, 11, 18, 0.42), rgba(16, 11, 18, 0.94)), url('${escapeHtml(coverUrl)}');">
      <span class="badge-game ${escapeHtml(player.game)}">${escapeHtml(player.gameName || player.game)}</span>
      <div style="display:flex;align-items:center;gap:0.35rem;flex-wrap:wrap;justify-content:flex-end;">
        ${frameInfo.pillCls ? `<span class="frame-tier-pill ${frameInfo.pillCls}">${escapeHtml(frameInfo.label)}</span>` : ''}
        <span class="star-rating-pill ${isLowRating ? 'low' : ''}">
          ${stars.toFixed(1)} ★ (${Number(player.ratingCount) || 0})
        </span>
      </div>
    </div>

    <div class="player-card-body">
      <div class="player-card-header">
        <div class="player-avatar-wrap clickable-player-handle" data-profile-user="${escapeHtml(player.username)}">
          <img src="${escapeHtml(player.avatarUrl || defaultAvatarUrl(player.username))}" class="player-avatar" alt="${escapeHtml(player.username)}">
          <span class="player-avatar-dot ${getPresenceInfo(player.username).cls}" data-presence-user="${escapeHtml(player.username)}" title="${getPresenceInfo(player.username).label}"></span>
        </div>
        <div style="min-width:0;">
          <h3 class="clickable-player-handle" data-profile-user="${escapeHtml(player.username)}">${escapeHtml(player.username)}</h3>
          ${player.equippedTitle ? `<span class="title-pill-inline">${escapeHtml(player.equippedTitle)}</span>` : ''}
          <p class="player-meta-text">${escapeHtml(player.region || 'Chile')} · ${getPresenceInfo(player.username).label}</p>
        </div>
      </div>

      <div class="player-specs-minimal">
        <div class="player-spec-item">
          <span>Rango</span>
          <strong class="${rankTierCls}">${escapeHtml(player.rank)}</strong>
        </div>
        <div class="player-spec-item">
          <span>Rol</span>
          <strong>${escapeHtml(player.role)}</strong>
        </div>
        <div class="player-spec-item">
          <span>Horario</span>
          <strong>${escapeHtml(player.preferredSchedule || 'Noches')}</strong>
        </div>
      </div>

      <p class="player-bio-snippet">${escapeHtml(player.bio || 'Disponible para coordinar partida.')}</p>

      <div class="player-chips-row">
        ${player.riotId ? `<span class="chip-plat">ID: ${escapeHtml(player.riotId)}</span>` : ''}
        ${(player.discordConnected || player.discordTag) ? `<button type="button" class="chip-discord" data-copy-discord="${escapeHtml(player.discordTag)}">Discord: @${escapeHtml(player.discordTag)}</button>` : ''}
      </div>

      ${!isCurrentUser ? `
        <div class="player-actions-row">
          <button class="btn btn-primary btn-sm btn-invite-player" data-user="${escapeHtml(player.username)}" data-game="${escapeHtml(player.gameName || player.game)}">
            Invitar
          </button>
          ${buildFriendActionButtonHtml(player.username)}
          <button type="button" class="btn btn-secondary btn-sm btn-rate-player" data-user="${escapeHtml(player.username)}">
            ★
          </button>
        </div>
      ` : `
        <button class="btn btn-secondary btn-sm btn-edit-own-profile" style="margin-top:auto;">
          Editar mi Perfil y Gustos
        </button>
      `}
    </div>
  `;

  div.querySelectorAll('[data-profile-user]').forEach(el => {
    el.addEventListener('click', () => openPublicProfile(el.dataset.profileUser));
  });
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
  bindFriendActionButtons(div);
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
          <span style="font-size:0.74rem;color:${t.status === 'FINISHED' ? '#fbbf24' : (t.status === 'IN_PROGRESS' ? 'var(--color-cyan)' : 'var(--color-success)')};font-weight:600;">
            ${t.status === 'FINISHED' ? 'Finalizado' : (t.status === 'IN_PROGRESS' ? 'En Curso' : 'Inscripción Abierta')}
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

    const canManage = Boolean(state.currentUser && (
      String(state.currentUser.role || '').toUpperCase() === 'ADMIN' ||
      (t.organizer && t.organizer.toLowerCase() === state.currentUser.username.toLowerCase())
    ));

    const genBtn = document.getElementById('btn-generate-bracket');
    if (genBtn) {
      const canGen = canManage && (!t.matches || t.matches.length === 0) && Number(t.registeredTeamsCount) >= 2;
      genBtn.style.display = canGen ? 'inline-flex' : 'none';
      genBtn.onclick = async () => {
        const { ok, data } = await API.generateTournamentBracket(t.id, state.currentUser.username);
        if (!ok) {
          showToast((data && data.error) || 'No se pudieron generar las llaves.', true);
          return;
        }
        showToast('Llaves de eliminación generadas correctamente.');
        await loadTournaments();
      };
    }

    if (!t.matches || t.matches.length === 0) {
      const regs = Array.isArray(t.registrations) && t.registrations.length > 0
        ? t.registrations
        : (t.registeredTeams || []).map(nm => ({ teamName: nm, captain: '', checkedIn: false }));
      const checkedCount = regs.filter(r => r.checkedIn).length;
      const regsHtml = regs.length > 0
        ? `<div class="tournament-checkin-list">${regs.map(reg => {
            const canCheckIn = !reg.checkedIn && state.currentUser && (
              canManage || (reg.captain && reg.captain.toLowerCase() === state.currentUser.username.toLowerCase())
            );
            return `
              <div class="checkin-team-chip ${reg.checkedIn ? 'confirmed' : ''}">
                <span>${escapeHtml(reg.teamName)}</span>
                ${reg.checkedIn
                  ? '<strong style="font-size:0.7rem;">✓ Listo</strong>'
                  : (canCheckIn
                    ? `<button type="button" class="btn btn-primary btn-sm btn-team-checkin" data-team="${escapeHtml(reg.teamName)}" style="padding:0.15rem 0.45rem;font-size:0.68rem;">Check-in</button>`
                    : '<span style="color:var(--text-muted);font-size:0.68rem;">Pendiente</span>')}
              </div>
            `;
          }).join('')}</div>`
        : '';

      bracketContainer.innerHTML = `
        <div style="padding:1.5rem;text-align:center;color:var(--text-muted);font-size:0.85rem;">
          Llaves pendientes de sorteo (${Number(t.registeredTeamsCount)} de ${Number(t.maxTeams)} equipos inscritos · Check-in: <strong style="color:#fff;">${checkedCount}/${regs.length}</strong>).
          ${regsHtml}
        </div>
      `;
      bracketContainer.querySelectorAll('.btn-team-checkin').forEach(btn => {
        btn.addEventListener('click', async () => {
          const res = await API.checkInTournament(t.id, btn.dataset.team, state.currentUser.username);
          if (!res.ok) {
            showToast((res.data && res.data.error) || 'No se pudo confirmar el check-in.', true);
            return;
          }
          showToast(`Check-in confirmado para "${btn.dataset.team}".`);
          await loadTournaments();
        });
      });
      return;
    }

    const roundsMap = new Map();
    t.matches.forEach(m => {
      let rIdx = Number(m.roundIndex || 0);
      if (rIdx <= 0) {
        if (m.roundName.includes('Octavo')) rIdx = 1;
        else if (m.roundName.includes('Cuarto')) rIdx = 2;
        else if (m.roundName.includes('Semi')) rIdx = 3;
        else rIdx = 4;
      }
      if (!roundsMap.has(rIdx)) roundsMap.set(rIdx, []);
      roundsMap.get(rIdx).push(m);
    });
    const sortedRoundKeys = [...roundsMap.keys()].sort((a, b) => a - b);
    const totalR = sortedRoundKeys.length;
    const finalMatch = roundsMap.get(sortedRoundKeys[totalR - 1])?.[0];

    const roundLabelFor = (matches, posIdx) => {
      const sample = matches[0]?.roundName || '';
      if (sample.includes('Octavo')) return 'Octavos de Final (Bo1)';
      if (sample.includes('Cuarto')) return 'Cuartos de Final (Bo1)';
      if (sample.includes('Semi')) return 'Semifinales (Bo1)';
      if (sample.includes('Gran Final') || posIdx === totalR - 1) return 'Gran Final (Bo3)';
      return `Ronda ${posIdx + 1}`;
    };

    const roundsHtml = sortedRoundKeys.map((rk, posIdx) => {
      const colMatches = roundsMap.get(rk);
      let connectorHtml = '';
      if (posIdx < totalR - 1) {
        const pairCount = Math.max(1, Math.floor(colMatches.length / 2));
        connectorHtml = `<div class="bracket-connectors-col">${'<div class="bracket-connector-pair"></div>'.repeat(pairCount)}</div>`;
      } else {
        connectorHtml = '<div class="bracket-connectors-col"><div class="bracket-connector-single"></div></div>';
      }
      return `
        <div class="bracket-round-column">
          <div class="round-name-tag">${escapeHtml(roundLabelFor(colMatches, posIdx))}</div>
          ${colMatches.map(m => createMatchBoxHtml(m, canManage)).join('')}
        </div>
        ${connectorHtml}
      `;
    }).join('');

    bracketContainer.innerHTML = `
      <div class="bracket-container ${totalR >= 3 ? 'wide-bracket' : ''}">
        ${roundsHtml}
        <div class="bracket-round-column" style="max-width:200px;text-align:center;">
          <div class="round-name-tag">Campeón</div>
          <div class="match-box-item champion-trophy-card">
            <div style="display:flex;justify-content:center;margin-bottom:0.45rem;color:#fbbf24;">
              <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9H4.5a2.5 2.5 0 0 1 0-5H6"/><path d="M18 9h1.5a2.5 2.5 0 0 0 0-5H18"/><path d="M4 22h16"/><path d="M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22"/><path d="M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22"/><path d="M18 2H6v7a6 6 0 0 0 12 0V2Z"/></svg>
            </div>
            <strong style="color:#fffbeb;display:block;font-size:0.95rem;">
              ${escapeHtml(finalMatch?.winner || 'Por Definir')}
            </strong>
            <span style="display:block;font-size:0.68rem;color:#fde68a;opacity:0.85;margin-top:0.2rem;text-transform:uppercase;letter-spacing:0.06em;">
              ${finalMatch?.winner ? 'Campeón Oficial' : 'Esperando Final'}
            </span>
          </div>
        </div>
      </div>
    `;

    bracketContainer.querySelectorAll('.btn-record-match').forEach(btn => {
      btn.addEventListener('click', () => {
        openMatchResultModal(t.id, btn.dataset.mid, btn.dataset.label, btn.dataset.t1, btn.dataset.t2);
      });
    });
  } catch (err) {
    console.error('Error renderizando bracket:', err);
  }
}

function createMatchBoxHtml(m, canManage = false) {
  const isFinished = m.status === 'FINISHED';
  const hasTeams = Boolean(m.team1 && m.team2 && m.team1 !== 'Por definir' && m.team2 !== 'Por definir' && m.team1 !== 'BYE' && m.team2 !== 'BYE');
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
      ${(canManage && !isFinished && hasTeams) ? `
        <div class="bracket-match-actions">
          <button type="button" class="btn btn-secondary btn-sm btn-record-match"
            data-mid="${escapeHtml(m.id)}"
            data-label="${escapeHtml(m.roundName)}"
            data-t1="${escapeHtml(m.team1)}"
            data-t2="${escapeHtml(m.team2)}"
            style="padding:0.2rem 0.55rem;font-size:0.7rem;">
            Registrar Resultado
          </button>
        </div>
      ` : ''}
    </div>
  `;
}

async function loadLeaderboard() {
  const listEl = document.getElementById('leaderboard-list');
  if (!listEl) return;
  try {
    const viewer = state.currentUser?.username || '';
    const board = await API.getLeaderboard(viewer);
    if (!board || board.length === 0) {
      listEl.innerHTML = '<div style="color:var(--text-muted);font-size:0.84rem;">No hay jugadores ranqueados todavía.</div>';
      return;
    }
    listEl.innerHTML = board.map((row, idx) => {
      const pos = Number(row.position || idx + 1);
      const topCls = pos === 1 ? 'top1' : (pos === 2 ? 'top2' : (pos === 3 ? 'top3' : ''));
      if (row.presenceStatus) {
        state.presenceMap[row.username.toLowerCase()] = { status: row.presenceStatus, activity: row.presenceActivity || '' };
      }
      const pInfo = getPresenceInfo(row.username);
      const frameInfo = getFrameInfo(row.cardFrame);
      const rankCls = getRankTierClass(row.rank);
      return `
        <div class="leaderboard-row ${frameInfo.cls}">
          <span class="leaderboard-pos ${topCls}">#${pos}</span>
          <div style="display:flex;align-items:center;gap:0.6rem;min-width:0;">
            <img src="${escapeHtml(row.avatarUrl || defaultAvatarUrl(row.username))}" alt="${escapeHtml(row.username)}" style="width:34px;height:34px;border-radius:50%;object-fit:cover;">
            <div style="min-width:0;">
              <strong class="clickable-player-handle" data-profile-user="${escapeHtml(row.username)}" style="color:#fff;font-size:0.88rem;display:flex;align-items:center;gap:0.35rem;flex-wrap:wrap;">
                <span class="presence-dot-mini ${pInfo.cls}" data-presence-user="${escapeHtml(row.username)}"></span>
                @${escapeHtml(row.username)}
                ${frameInfo.pillCls ? `<span class="frame-tier-pill ${frameInfo.pillCls}">${escapeHtml(frameInfo.label)}</span>` : ''}
                ${row.equippedTitle ? `<span class="title-pill">${escapeHtml(row.equippedTitle)}</span>` : ''}
              </strong>
              <span class="${rankCls}" style="font-size:0.74rem;color:var(--text-muted);">${escapeHtml(row.rank)}</span>
            </div>
          </div>
          <span class="badge-game ${escapeHtml(row.game || '')}" style="justify-self:start;">${escapeHtml(row.gameName)}</span>
          <span style="color:#fbbf24;font-weight:700;font-size:0.86rem;">${Number(row.starRating).toFixed(1)} ★ <small style="color:var(--text-muted);">(${Number(row.ratingCount)})</small></span>
          <span style="color:#10b981;font-weight:700;font-size:0.82rem;text-align:right;">${Number(row.reputationScore)} Karma</span>
        </div>
      `;
    }).join('');
    listEl.querySelectorAll('[data-profile-user]').forEach(el => {
      el.addEventListener('click', () => openPublicProfile(el.dataset.profileUser));
    });
  } catch (err) {
    console.error('Error cargando leaderboard:', err);
  }
}

// =========================================================================
// 5. SISTEMA DE AMISTADES + CHAT DIRECTO ESTILO IG + SALAS GENERALES
// =========================================================================
function getFriendStatus(username) {
  if (!username) return 'NONE';
  return state.friendshipStatusesLower[String(username).toLowerCase()] || 'NONE';
}

function buildFriendActionButtonHtml(username) {
  const safe = escapeHtml(username);
  const st = getFriendStatus(username);
  if (st === 'ACCEPTED') {
    return `<button type="button" class="btn btn-secondary btn-sm btn-dm-friend" data-user="${safe}" style="display:inline-flex;align-items:center;gap:0.3rem;"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>Chatear</button>`;
  }
  if (st === 'PENDING_INCOMING') {
    return `<button type="button" class="btn btn-success btn-sm btn-accept-friend-inline" data-user="${safe}">Aceptar Amigo</button>`;
  }
  if (st === 'PENDING_OUTGOING') {
    return `<button type="button" class="btn btn-secondary btn-sm" disabled style="opacity:0.7;">Solicitud enviada</button>`;
  }
  return `<button type="button" class="btn btn-secondary btn-sm btn-add-friend" data-user="${safe}">+ Amigo</button>`;
}

function bindFriendActionButtons(container) {
  if (!container) return;
  container.querySelectorAll('.btn-dm-friend').forEach(btn => {
    btn.addEventListener('click', (e) => {
      openDirectChatWith(e.currentTarget.dataset.user);
    });
  });
  container.querySelectorAll('.btn-add-friend').forEach(btn => {
    btn.addEventListener('click', (e) => {
      handleAddFriend(e.currentTarget.dataset.user);
    });
  });
  container.querySelectorAll('.btn-accept-friend-inline').forEach(btn => {
    btn.addEventListener('click', (e) => {
      handleAcceptFriendByUsername(e.currentTarget.dataset.user);
    });
  });
}

function setChatMode(mode) {
  state.chatMode = mode === 'channels' ? 'channels' : 'dm';
  const dmView = document.getElementById('chat-dm-view');
  const chView = document.getElementById('chat-channels-view');
  if (dmView) dmView.style.display = state.chatMode === 'dm' ? 'grid' : 'none';
  if (chView) chView.style.display = state.chatMode === 'channels' ? 'grid' : 'none';

  document.getElementById('btn-chat-mode-dm')?.classList.toggle('active', state.chatMode === 'dm');
  document.getElementById('btn-chat-mode-channels')?.classList.toggle('active', state.chatMode === 'channels');

  if (state.chatMode === 'channels') {
    fetchAndRenderChat();
    loadInvites();
  } else {
    loadFriendsInbox();
    if (state.activeDmFriend) {
      fetchActiveDmMessages(true);
    }
  }
}

function updateChatNavBadge() {
  const unread = Number(state.friendsInbox?.totalUnread || 0);
  const reqs = Number(state.friendsInbox?.incomingRequests?.length ?? state.friendsInbox?.pendingIncomingCount ?? 0);
  const invites = Number(state.pendingInvitesCount || 0);
  const totalBadge = unread + reqs + invites;

  const badgeEl = document.getElementById('badge-invites-count');
  if (badgeEl) badgeEl.innerText = totalBadge;

  const topBtn = document.getElementById('topbar-invites-text');
  if (topBtn) topBtn.innerText = totalBadge > 0 ? `Notificaciones (${totalBadge})` : 'Notificaciones';

  const pop = document.getElementById('notifications-popover');
  if (pop && pop.style.display === 'block') renderNotificationsPopover();

  const dmPill = document.getElementById('dm-unread-pill');
  if (dmPill) {
    if (unread > 0) {
      dmPill.style.display = 'inline-block';
      dmPill.innerText = unread;
    } else {
      dmPill.style.display = 'none';
    }
  }

  const reqBadge = document.getElementById('ig-requests-badge');
  if (reqBadge) {
    if (reqs > 0) {
      reqBadge.style.display = 'inline-block';
      reqBadge.innerText = reqs;
    } else {
      reqBadge.style.display = 'none';
    }
  }
}

function setupInstagramDm() {
  document.getElementById('btn-chat-mode-dm')?.addEventListener('click', () => setChatMode('dm'));
  document.getElementById('btn-chat-mode-channels')?.addEventListener('click', () => setChatMode('channels'));

  document.querySelectorAll('.ig-tab-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      state.igInboxTab = btn.dataset.igTab === 'requests' ? 'requests' : 'chats';
      document.querySelectorAll('.ig-tab-btn').forEach(b => b.classList.toggle('active', b.dataset.igTab === state.igInboxTab));
      renderIgSidebarList();
    });
  });

  const openRequestsTab = () => {
    state.igInboxTab = 'requests';
    document.querySelectorAll('.ig-tab-btn').forEach(b => b.classList.toggle('active', b.dataset.igTab === 'requests'));
    renderIgSidebarList();
    document.getElementById('ig-dm-search')?.focus();
  };
  document.getElementById('btn-ig-new-chat')?.addEventListener('click', openRequestsTab);
  document.getElementById('btn-ig-empty-find')?.addEventListener('click', openRequestsTab);

  document.getElementById('ig-dm-search')?.addEventListener('input', (e) => {
    state.igSearchTerm = e.target.value.trim().toLowerCase();
    renderIgSidebarList();
  });

  const dmInput = document.getElementById('ig-dm-input');
  document.getElementById('ig-dm-send-btn')?.addEventListener('click', handleSendDirectMessage);
  dmInput?.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') handleSendDirectMessage();
  });
  dmInput?.addEventListener('input', () => {
    if (!state.currentUser || !state.activeDmFriend) return;
    if (!state.dmTypingDebounce) {
      API.sendDmTyping(state.currentUser.username, state.activeDmFriend, true).catch(() => {});
    }
    clearTimeout(state.dmTypingDebounce);
    state.dmTypingDebounce = setTimeout(() => {
      state.dmTypingDebounce = null;
      if (state.currentUser && state.activeDmFriend) {
        API.sendDmTyping(state.currentUser.username, state.activeDmFriend, false).catch(() => {});
      }
    }, 2200);
  });

  document.getElementById('ig-chat-username')?.addEventListener('click', () => {
    if (state.activeDmFriend) openPublicProfile(state.activeDmFriend);
  });
  document.getElementById('btn-dm-profile')?.addEventListener('click', () => {
    if (state.activeDmFriend) openPublicProfile(state.activeDmFriend);
  });

  document.getElementById('btn-dm-attach-image')?.addEventListener('click', () => {
    if (!state.activeDmFriend) return;
    document.getElementById('dm-image-modal').style.display = 'flex';
  });
  document.getElementById('btn-dm-pick-file')?.addEventListener('click', () => {
    document.getElementById('dm-image-file')?.click();
  });
  document.getElementById('dm-image-file')?.addEventListener('change', async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const dataUrl = await resizeImageFileToDataUrl(file);
      closeModalById('dm-image-modal');
      await sendRichDirectMessage('Imagen compartida', 'IMAGE', dataUrl, '');
    } catch (err) {
      showToast(err.message || 'No se pudo procesar la imagen.', true);
    } finally {
      e.target.value = '';
    }
  });
  document.getElementById('dm-image-url-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const url = document.getElementById('dm-image-url-input').value.trim();
    if (!url) return;
    closeModalById('dm-image-modal');
    e.target.reset();
    await sendRichDirectMessage('Imagen compartida', 'IMAGE', url, '');
  });

  document.getElementById('btn-dm-share-lobby')?.addEventListener('click', async () => {
    if (!state.activeDmFriend) return;
    if (!state.lobbiesCache || state.lobbiesCache.length === 0) {
      await loadLobbies();
    }
    const sel = document.getElementById('share-lobby-select');
    if (!sel) return;
    if (!state.lobbiesCache || state.lobbiesCache.length === 0) {
      showToast('No hay lobbies abiertos todavía. Crea uno en la sección LFG.', true);
      return;
    }
    sel.innerHTML = state.lobbiesCache.map(l =>
      `<option value="${escapeHtml(l.id)}">${escapeHtml(l.title)} · ${escapeHtml(l.gameName)} (${Number(l.currentCount)}/${Number(l.maxSlots)})</option>`
    ).join('');
    document.getElementById('share-lobby-modal').style.display = 'flex';
  });

  document.getElementById('share-lobby-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const lobbyId = document.getElementById('share-lobby-select').value;
    const note = document.getElementById('share-lobby-note').value.trim();
    if (!lobbyId) return;
    closeModalById('share-lobby-modal');
    await sendRichDirectMessage(note || '¡Únete a mi lobby!', 'LOBBY_INVITE', '', lobbyId);
  });

  document.querySelectorAll('.ig-quick-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      const txt = chip.dataset.quickText;
      if (txt) sendDirectMessageText(txt);
    });
  });

  document.getElementById('btn-dm-invite')?.addEventListener('click', () => {
    if (!state.activeDmFriend) return;
    const fObj = (state.friendsInbox?.friends || []).find(f => f.username.toLowerCase() === state.activeDmFriend.toLowerCase());
    openMatchModal(state.activeDmFriend, (fObj && fObj.gameName) || 'Valorant');
  });

  document.getElementById('btn-dm-rate')?.addEventListener('click', () => {
    if (state.activeDmFriend) openRateModal(state.activeDmFriend);
  });

  document.getElementById('btn-dm-unfriend')?.addEventListener('click', () => {
    if (state.activeDmFriend) handleUnfriend(state.activeDmFriend);
  });
}

async function loadFriendsInbox() {
  if (!state.currentUser) return;
  try {
    const inbox = await API.getFriendsInbox(state.currentUser.username);
    if (!inbox) return;
    const prevKey = JSON.stringify(state.friendshipStatusesLower);
    state.friendsInbox = inbox;
    const statusMap = {};
    const rawStatuses = inbox.friendshipStatuses || {};
    Object.keys(rawStatuses).forEach(u => {
      statusMap[u.toLowerCase()] = rawStatuses[u];
    });
    state.friendshipStatusesLower = statusMap;

    if (prevKey !== JSON.stringify(statusMap)) {
      if (state.lastRecommendationsData) renderRecommendations(state.lastRecommendationsData);
      if (document.querySelector('#players-grid .player-card')) loadLfgPlayers();
    }

    if (state.allPlayersCache.length === 0) {
      API.getPlayers('all', '', '').then(list => {
        if (Array.isArray(list) && list.length > 0) {
          state.allPlayersCache = list;
          if (state.igInboxTab === 'requests') renderIgSidebarList();
        }
      }).catch(() => {});
    }

    updateChatNavBadge();
    renderIgSidebarList();
  } catch (err) {
    console.error('Error cargando bandeja de amigos:', err);
  }
}

function renderIgSidebarList() {
  const container = document.getElementById('ig-dm-threads-list');
  if (!container || !state.friendsInbox) return;

  const q = state.igSearchTerm || '';
  container.innerHTML = '';

  if (state.igInboxTab === 'chats') {
    const friends = (state.friendsInbox.friends || []).filter(f => {
      if (!q) return true;
      return f.username.toLowerCase().includes(q) || (f.gameName || '').toLowerCase().includes(q);
    });

    if (friends.length === 0) {
      container.innerHTML = `
        <div style="text-align:center;color:var(--text-muted);padding:2rem 0.75rem;font-size:0.8rem;">
          ${q ? 'No se encontraron amigos con ese filtro.' : 'Aún no tienes amigos agregados.<br>Ve a <strong>Solicitudes</strong> o haz clic en <strong>+ Agregar</strong> para buscar jugadores.'}
        </div>
      `;
      return;
    }

    friends.forEach(f => {
      const isActive = Boolean(state.activeDmFriend && state.activeDmFriend.toLowerCase() === f.username.toLowerCase());
      const unread = Number(f.unreadCount || 0);
      const item = document.createElement('button');
      item.type = 'button';
      item.className = `ig-thread-item ${isActive ? 'active' : ''} ${unread > 0 ? 'unread' : ''}`;
      item.dataset.friend = f.username;

      let previewText = 'Toca para saludar a tu amigo';
      if (f.lastMessage) {
        const fromMe = state.currentUser && f.lastSender && f.lastSender.toLowerCase() === state.currentUser.username.toLowerCase();
        previewText = `${fromMe ? 'Tú: ' : ''}${f.lastMessage}`;
      }

      item.innerHTML = `
        <div class="ig-avatar-ring ${unread > 0 ? 'has-unread' : ''}">
          <img src="${escapeHtml(f.avatarUrl)}" alt="${escapeHtml(f.username)}">
          <span class="ig-online-dot"></span>
        </div>
        <div class="ig-thread-meta">
          <div class="ig-thread-top">
            <span class="ig-thread-name">@${escapeHtml(f.username)}</span>
            <span class="ig-thread-time">${escapeHtml(f.lastTime || f.gameName || '')}</span>
          </div>
          <div class="ig-thread-bottom">
            <span class="ig-thread-preview">${escapeHtml(previewText)}</span>
            ${unread > 0 ? `<span class="ig-unread-badge">${unread}</span>` : ''}
          </div>
        </div>
      `;
      item.addEventListener('click', () => openDirectChatWith(f.username));
      container.appendChild(item);
    });
    return;
  }

  // Sub-pestaña "Solicitudes" y "Agregar Amigos"
  const incoming = state.friendsInbox.incomingRequests || [];
  const outgoing = state.friendsInbox.outgoingRequests || [];

  const secIn = document.createElement('div');
  secIn.innerHTML = `<div class="ig-req-group-title">Solicitudes Recibidas (${incoming.length})</div>`;
  if (incoming.length === 0) {
    secIn.innerHTML += `<div style="font-size:0.76rem;color:var(--text-muted);padding:0.35rem 0.6rem;">No tienes solicitudes pendientes.</div>`;
  } else {
    incoming.forEach(req => {
      const card = document.createElement('div');
      card.className = 'ig-req-card';
      card.innerHTML = `
        <div class="ig-req-user">
          <img src="${escapeHtml(req.avatarUrl)}" alt="${escapeHtml(req.username)}">
          <div style="min-width:0;">
            <strong style="display:block;font-size:0.82rem;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">@${escapeHtml(req.username)}</strong>
            <span style="font-size:0.7rem;color:var(--text-muted);">${escapeHtml(req.gameName)} · ${Number(req.starRating || 0).toFixed(1)}★</span>
          </div>
        </div>
        <div class="ig-req-actions">
          <button type="button" class="btn btn-primary btn-sm btn-accept-req" data-id="${escapeHtml(req.friendshipId)}" data-user="${escapeHtml(req.username)}">Aceptar</button>
          <button type="button" class="btn btn-secondary btn-sm btn-reject-req" data-id="${escapeHtml(req.friendshipId)}" data-user="${escapeHtml(req.username)}">✕</button>
        </div>
      `;
      card.querySelector('.btn-accept-req')?.addEventListener('click', () => handleRespondFriendRequest(req.friendshipId, 'ACCEPTED', req.username));
      card.querySelector('.btn-reject-req')?.addEventListener('click', () => handleRespondFriendRequest(req.friendshipId, 'REJECTED', req.username));
      secIn.appendChild(card);
    });
  }
  container.appendChild(secIn);

  if (outgoing.length > 0) {
    const secOut = document.createElement('div');
    secOut.innerHTML = `<div class="ig-req-group-title">Enviadas (${outgoing.length})</div>`;
    outgoing.forEach(req => {
      const card = document.createElement('div');
      card.className = 'ig-req-card';
      card.innerHTML = `
        <div class="ig-req-user">
          <img src="${escapeHtml(req.avatarUrl)}" alt="${escapeHtml(req.username)}">
          <div style="min-width:0;">
            <strong style="display:block;font-size:0.82rem;color:#fff;">@${escapeHtml(req.username)}</strong>
            <span style="font-size:0.7rem;color:var(--text-muted);">Pendiente de respuesta</span>
          </div>
        </div>
        <button type="button" class="btn btn-secondary btn-sm btn-cancel-req" data-user="${escapeHtml(req.username)}">Cancelar</button>
      `;
      card.querySelector('.btn-cancel-req')?.addEventListener('click', () => handleUnfriend(req.username));
      secOut.appendChild(card);
    });
    container.appendChild(secOut);
  }

  // Jugadores disponibles para agregar
  const discover = (state.allPlayersCache || []).filter(p => {
    if (!state.currentUser || p.username.toLowerCase() === state.currentUser.username.toLowerCase()) return false;
    if (getFriendStatus(p.username) !== 'NONE') return false;
    if (!q) return true;
    return p.username.toLowerCase().includes(q) || (p.gameName || '').toLowerCase().includes(q);
  });

  const secDisc = document.createElement('div');
  secDisc.innerHTML = `<div class="ig-req-group-title">Agregar Amigos de PlayMatch (${discover.length})</div>`;
  if (discover.length === 0) {
    secDisc.innerHTML += `<div style="font-size:0.76rem;color:var(--text-muted);padding:0.35rem 0.6rem;">No hay más jugadores disponibles con ese filtro.</div>`;
  } else {
    discover.slice(0, 10).forEach(p => {
      const card = document.createElement('div');
      card.className = 'ig-req-card';
      card.innerHTML = `
        <div class="ig-req-user">
          <img src="${escapeHtml(p.avatarUrl)}" alt="${escapeHtml(p.username)}">
          <div style="min-width:0;">
            <strong style="display:block;font-size:0.82rem;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">@${escapeHtml(p.username)}</strong>
            <span style="font-size:0.7rem;color:var(--text-muted);">${escapeHtml(p.gameName || p.game)} · ${Number(p.starRating || 0).toFixed(1)}★</span>
          </div>
        </div>
        <button type="button" class="btn btn-primary btn-sm btn-disc-add" data-user="${escapeHtml(p.username)}">+ Agregar</button>
      `;
      card.querySelector('.btn-disc-add')?.addEventListener('click', () => handleAddFriend(p.username));
      secDisc.appendChild(card);
    });
  }
  container.appendChild(secDisc);
}

async function openDirectChatWith(friendUsername) {
  if (!friendUsername || !state.currentUser) return;
  if (state.currentTab !== 'chat') {
    switchTab('chat');
  }
  if (state.chatMode !== 'dm') {
    setChatMode('dm');
  }
  state.igInboxTab = 'chats';
  document.querySelectorAll('.ig-tab-btn').forEach(b => b.classList.toggle('active', b.dataset.igTab === 'chats'));
  state.activeDmFriend = friendUsername;
  state.activeDmCount = -1;

  document.getElementById('ig-dm-empty-placeholder').style.display = 'none';
  document.getElementById('ig-dm-active-thread').style.display = 'flex';
  renderIgSidebarList();
  await fetchActiveDmMessages(true);
  await loadFriendsInbox();
  document.getElementById('ig-dm-input')?.focus();
}

async function fetchActiveDmMessages(forceScroll = true) {
  if (!state.currentUser || !state.activeDmFriend) return;
  try {
    const { ok, status, data } = await API.getDirectMessages(state.currentUser.username, state.activeDmFriend);
    if (!ok || !data) {
      if (status === 403) {
        state.activeDmFriend = null;
        document.getElementById('ig-dm-active-thread').style.display = 'none';
        document.getElementById('ig-dm-empty-placeholder').style.display = 'block';
      }
      return;
    }

    const friend = data.friend || {};
    const messages = Array.isArray(data.messages) ? data.messages : [];

    const avEl = document.getElementById('ig-chat-avatar');
    if (avEl && friend.avatarUrl) avEl.src = friend.avatarUrl;
    setTextIfExists('ig-chat-username', `@${friend.username || state.activeDmFriend}`);
    setTextIfExists('ig-chat-meta', `${friend.gameName || 'PlayMatch'} · ${friend.rank || 'Activo'} · ${Number(friend.starRating || 4.8).toFixed(1)}★`);

    const dcEl = document.getElementById('ig-chat-discord');
    if (dcEl) {
      if (friend.discordConnected || friend.discordTag) {
        dcEl.style.display = 'inline-block';
        dcEl.innerText = `Discord: @${friend.discordTag}`;
      } else {
        dcEl.style.display = 'none';
      }
    }

    renderActiveDmMessages(friend, messages, forceScroll || messages.length !== state.activeDmCount);
    state.activeDmCount = messages.length;
  } catch (err) {
    console.error('Error cargando mensajes directos:', err);
  }
}

function setTextIfExists(id, text) {
  const el = document.getElementById(id);
  if (el) el.innerText = text;
}

function renderActiveDmMessages(friend, messages, shouldScroll) {
  const container = document.getElementById('ig-dm-messages');
  if (!container) return;

  container.innerHTML = `
    <div class="ig-profile-intro">
      <img src="${escapeHtml(friend.avatarUrl || defaultAvatarUrl(friend.username))}" alt="${escapeHtml(friend.username || '')}">
      <h4>@${escapeHtml(friend.username || '')}</h4>
      <p>Amigos en PlayMatch · ${escapeHtml(friend.gameName || 'Multi-Juego')} ${friend.rank ? `(${escapeHtml(friend.rank)})` : ''}</p>
    </div>
  `;

  if (!messages || messages.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'chat-empty-state';
    empty.innerHTML = 'Todavía no hay mensajes entre ustedes.<br>Envía el primer mensaje directo para coordinar partida.';
    container.appendChild(empty);
    return;
  }

  let lastOwnIdx = -1;
  messages.forEach((m, idx) => {
    if (state.currentUser && m.sender.toLowerCase() === state.currentUser.username.toLowerCase()) {
      lastOwnIdx = idx;
    }
  });

  messages.forEach((m, idx) => {
    const isMe = Boolean(state.currentUser && m.sender.toLowerCase() === state.currentUser.username.toLowerCase());
    const row = document.createElement('div');
    row.className = `ig-msg-row ${isMe ? 'own' : 'incoming'}`;
    const receipt = (isMe && idx === lastOwnIdx) ? (m.isRead ? ' · ✓✓ Visto' : ' · ✓ Enviado') : '';

    let innerHtml = escapeHtml(m.content);
    if (m.msgType === 'IMAGE' && m.attachmentUrl) {
      innerHtml = `
        ${m.content && m.content !== 'Imagen compartida' ? `<div style="margin-bottom:0.3rem;">${escapeHtml(m.content)}</div>` : ''}
        <img src="${escapeHtml(m.attachmentUrl)}" alt="Imagen" class="ig-msg-image">
      `;
    } else if (m.msgType === 'LOBBY_INVITE' && m.metaJson) {
      let meta = {};
      try { meta = JSON.parse(m.metaJson); } catch (err) {}
      innerHtml = `
        <div>${escapeHtml(m.content || 'Invitación a Lobby')}</div>
        <div class="ig-lobby-card">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:0.4rem;">
            <strong style="color:#fff;font-size:0.83rem;">${escapeHtml(meta.title || 'Lobby de Escuadra')}</strong>
            <span class="lobby-slots-pill">${Number(meta.currentCount || 1)}/${Number(meta.maxSlots || 5)}</span>
          </div>
          <div style="font-size:0.73rem;color:var(--color-cyan);">${escapeHtml(meta.gameName || 'PlayMatch')} · ${escapeHtml(meta.mode || 'Escuadra')}</div>
          <button type="button" class="btn btn-primary btn-sm btn-dm-join-lobby" data-lobby-id="${escapeHtml(meta.lobbyId || '')}" data-lobby-title="${escapeHtml(meta.title || 'Lobby')}" style="margin-top:0.25rem;">
            Unirse al Lobby
          </button>
        </div>
      `;
    }

    row.innerHTML = `
      ${!isMe ? `<img src="${escapeHtml(m.avatar)}" alt="${escapeHtml(m.sender)}" class="ig-msg-mini-avatar">` : ''}
      <div class="ig-msg-bubble-wrap">
        <div class="ig-msg-bubble">${innerHtml}</div>
        <span class="ig-msg-time">${escapeHtml(m.timestamp)}${receipt}</span>
      </div>
    `;
    row.querySelector('.btn-dm-join-lobby')?.addEventListener('click', async (e) => {
      const b = e.currentTarget;
      const lId = b.dataset.lobbyId;
      if (!lId || !state.currentUser) return;
      const { ok, data } = await API.joinLobby(lId, state.currentUser.username);
      if (!ok) {
        showToast((data && data.error) || 'No se pudo unir al lobby.', true);
        return;
      }
      showToast('Te uniste al lobby desde el chat.');
      await loadLobbies();
      openLobbyGroupChat(lId, b.dataset.lobbyTitle);
    });
    container.appendChild(row);
  });

  if (shouldScroll) {
    container.scrollTop = container.scrollHeight;
  }
}

function showDmTypingIndicator(senderUsername, isTyping) {
  const el = document.getElementById('ig-typing-indicator');
  if (!el) return;
  clearTimeout(state.typingTimers[senderUsername]);
  if (!isTyping) {
    el.style.display = 'none';
    return;
  }
  el.innerText = `@${senderUsername} está escribiendo...`;
  el.style.display = 'block';
  state.typingTimers[senderUsername] = setTimeout(() => {
    el.style.display = 'none';
  }, 3500);
}

async function handleSendDirectMessage() {
  const input = document.getElementById('ig-dm-input');
  const content = input?.value?.trim();
  if (!content) return;
  const ok = await sendDirectMessageText(content);
  if (ok && input) input.value = '';
}

async function sendDirectMessageText(content) {
  return sendRichDirectMessage(content, 'TEXT', '', '');
}

async function sendRichDirectMessage(content, msgType = 'TEXT', attachmentUrl = '', lobbyId = '') {
  if (!state.currentUser || !state.activeDmFriend) return false;
  try {
    const { ok, data } = await API.sendDirectMessage(state.currentUser.username, state.activeDmFriend, content, {
      msgType, attachmentUrl, lobbyId
    });
    if (!ok) {
      showToast((data && data.error) || 'No se pudo enviar el mensaje directo.', true);
      return false;
    }
    if (state.dmTypingDebounce) {
      clearTimeout(state.dmTypingDebounce);
      state.dmTypingDebounce = null;
      API.sendDmTyping(state.currentUser.username, state.activeDmFriend, false).catch(() => {});
    }
    await fetchActiveDmMessages(true);
    await loadFriendsInbox();
    return true;
  } catch (err) {
    showToast('Error al enviar mensaje directo.', true);
    return false;
  }
}

async function refreshFriendRelatedViews() {
  await loadFriendsInbox();
  if (state.lastRecommendationsData) renderRecommendations(state.lastRecommendationsData);
  if (state.currentTab === 'lfg') await loadLfgPlayers();
}

async function handleAddFriend(targetUsername) {
  if (!state.currentUser || !targetUsername) return;
  try {
    const { ok, data } = await API.sendFriendRequest(state.currentUser.username, targetUsername);
    if (!ok) {
      showToast((data && data.error) || 'No se pudo enviar la solicitud.', true);
      return;
    }
    showToast((data && data.message) || `Solicitud enviada a @${targetUsername}.`);
    await refreshFriendRelatedViews();
  } catch (err) {
    showToast('Error enviando solicitud de amistad.', true);
  }
}

async function handleRespondFriendRequest(requestId, status, friendUsername) {
  if (!state.currentUser || !requestId) return;
  try {
    const { ok, data } = await API.respondFriendRequest(requestId, state.currentUser.username, status);
    if (!ok) {
      showToast((data && data.error) || 'No se pudo responder la solicitud.', true);
      return;
    }
    showToast((data && data.message) || 'Solicitud actualizada.');
    await refreshFriendRelatedViews();
    if (status === 'ACCEPTED' && friendUsername) {
      openDirectChatWith(friendUsername);
    }
  } catch (err) {
    showToast('Error al procesar la solicitud.', true);
  }
}

async function handleAcceptFriendByUsername(username) {
  const req = (state.friendsInbox?.incomingRequests || []).find(
    r => r.username.toLowerCase() === String(username).toLowerCase()
  );
  if (req && req.friendshipId) {
    await handleRespondFriendRequest(req.friendshipId, 'ACCEPTED', req.username);
  } else {
    await handleAddFriend(username);
  }
}

async function handleUnfriend(friendUsername) {
  if (!state.currentUser || !friendUsername) return;
  try {
    const { ok, data } = await API.removeFriend(state.currentUser.username, friendUsername);
    if (!ok) {
      showToast((data && data.error) || 'No se pudo quitar la amistad.', true);
      return;
    }
    if (state.activeDmFriend && state.activeDmFriend.toLowerCase() === friendUsername.toLowerCase()) {
      state.activeDmFriend = null;
      document.getElementById('ig-dm-active-thread').style.display = 'none';
      document.getElementById('ig-dm-empty-placeholder').style.display = 'block';
    }
    showToast(`@${friendUsername} eliminado de tu lista.`);
    await refreshFriendRelatedViews();
  } catch (err) {
    showToast('Error quitando amistad.', true);
  }
}

function selectChatChannel(channel, label, desc, activeBtn = null) {
  const input = document.getElementById('chat-message-input');
  if (state.chatMode !== 'channels') {
    state.chatMode = 'channels';
    const dmView = document.getElementById('chat-dm-view');
    const chView = document.getElementById('chat-channels-view');
    if (dmView) dmView.style.display = 'none';
    if (chView) chView.style.display = 'grid';
    document.getElementById('btn-chat-mode-dm')?.classList.remove('active');
    document.getElementById('btn-chat-mode-channels')?.classList.add('active');
  }
  document.querySelectorAll('.channel-list-item').forEach(b => b.classList.remove('active'));
  if (activeBtn) {
    activeBtn.classList.add('active');
  } else {
    document.querySelector(`.channel-list-item[data-channel="${CSS.escape(channel)}"]`)?.classList.add('active');
  }
  state.activeChannel = channel || 'global';
  const cleanLabel = label || `# ${state.activeChannel}`;
  const cleanDesc = desc || 'Conversación persistida entre jugadores.';
  const header = document.getElementById('active-channel-title');
  if (header) header.innerText = `Canal Activo: ${cleanLabel}`;
  const descEl = document.getElementById('active-channel-desc');
  if (descEl) descEl.innerText = cleanDesc;
  if (input) input.placeholder = `Escribe en ${cleanLabel} para charlar o buscar gente...`;
  const mc = document.getElementById('chat-messages-container');
  if (mc) mc.innerHTML = '<div class="chat-empty-state">Cargando sala...</div>';
  fetchAndRenderChat();
}

function setupChat() {
  const input = document.getElementById('chat-message-input');
  const btn = document.getElementById('chat-send-btn');

  btn?.addEventListener('click', handleSendMessage);
  input?.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') handleSendMessage();
  });

  document.querySelectorAll('.channel-list-item').forEach(chBtn => {
    chBtn.addEventListener('click', () => {
      selectChatChannel(
        chBtn.dataset.channel || 'global',
        chBtn.dataset.label || chBtn.innerText.trim(),
        chBtn.dataset.desc || 'Conversación persistida entre jugadores.',
        chBtn
      );
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
      const isSys = m.sender === 'PlayMatch Sistema';
      const item = document.createElement('div');
      item.className = `message-card ${isMe ? 'own' : ''}`;
      item.innerHTML = `
        <img src="${escapeHtml(m.avatar)}" class="msg-avatar-img ${!isSys ? 'clickable-player-handle' : ''}" ${!isSys ? `data-profile-user="${escapeHtml(m.sender)}"` : ''} alt="${escapeHtml(m.sender)}">
        <div class="msg-body-wrapper">
          <div class="msg-meta-line">
            <strong class="${!isSys ? 'clickable-player-handle' : ''}" ${!isSys ? `data-profile-user="${escapeHtml(m.sender)}"` : ''}>${escapeHtml(m.sender)}</strong>
            <span>${escapeHtml(m.timestamp)}</span>
            ${(!isMe && !isSys) ? `<button type="button" class="msg-invite-btn">Invitar a jugar</button>` : ''}
          </div>
          <div class="msg-text-content">${escapeHtml(m.content)}</div>
        </div>
      `;
      item.querySelectorAll('[data-profile-user]').forEach(el => {
        el.addEventListener('click', () => openPublicProfile(el.dataset.profileUser));
      });
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
    state.matchesInvitesCache = Array.isArray(invites) ? invites : [];
    const pendingForMe = state.matchesInvitesCache.filter(i => i.receiver.toLowerCase() === state.currentUser.username.toLowerCase() && i.status === 'PENDING');
    state.pendingInvitesCount = pendingForMe.length;
    updateChatNavBadge();
    const unreadDmCount = Number(state.friendsInbox?.totalUnread || 0) + Number(state.friendsInbox?.pendingIncomingCount || 0);
    const totalAlertCount = pendingForMe.length + unreadDmCount;
    const topBtn = document.getElementById('topbar-invites-text');
    if (topBtn) topBtn.innerText = totalAlertCount > 0 ? `Notificaciones (${totalAlertCount})` : 'Notificaciones';
    const pop = document.getElementById('notifications-popover');
    if (pop && pop.style.display === 'block') renderNotificationsPopover();

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

function renderNotificationsPopover() {
  const listEl = document.getElementById('notif-popover-list');
  if (!listEl || !state.currentUser) return;
  const items = [];

  const unreadFriends = (state.friendsInbox?.friends || []).filter(f => Number(f.unreadCount || 0) > 0);
  unreadFriends.forEach(f => {
    items.push(`
      <div class="notif-popover-item">
        <div class="notif-popover-item-title" style="display:flex;align-items:center;gap:0.4rem;">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="var(--color-cyan)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
          <span>${Number(f.unreadCount)} mensaje(s) sin leer de @${escapeHtml(f.username)}</span>
        </div>
        <div class="notif-popover-item-sub">${escapeHtml(f.lastMessage || 'Nuevo mensaje directo')}</div>
        <div class="notif-popover-actions">
          <button type="button" class="btn btn-primary btn-sm btn-notif-open-dm" data-user="${escapeHtml(f.username)}">Abrir chat</button>
        </div>
      </div>
    `);
  });

  const pendingReq = state.friendsInbox?.incomingRequests || state.friendsInbox?.pendingIncoming || [];
  pendingReq.forEach(r => {
    const reqId = r.friendshipId || r.requestId || '';
    items.push(`
      <div class="notif-popover-item">
        <div class="notif-popover-item-title" style="display:flex;align-items:center;gap:0.4rem;">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="var(--color-success)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="8.5" cy="7" r="4"/><line x1="20" y1="8" x2="20" y2="14"/><line x1="23" y1="11" x2="17" y2="11"/></svg>
          <span>Solicitud de amistad de @${escapeHtml(r.username)}</span>
        </div>
        <div class="notif-popover-item-sub">${escapeHtml(r.gameName || 'PlayMatch')} · ${Number(r.starRating || 4.8).toFixed(1)}★</div>
        <div class="notif-popover-actions">
          <button type="button" class="btn btn-success btn-sm btn-notif-accept-fr" data-id="${escapeHtml(reqId)}" data-user="${escapeHtml(r.username)}">Aceptar</button>
          <button type="button" class="btn btn-danger btn-sm btn-notif-reject-fr" data-id="${escapeHtml(reqId)}" data-user="${escapeHtml(r.username)}">Rechazar</button>
        </div>
      </div>
    `);
  });

  const pendingInv = (state.matchesInvitesCache || []).filter(
    i => i.receiver.toLowerCase() === state.currentUser.username.toLowerCase() && i.status === 'PENDING'
  );
  pendingInv.forEach(inv => {
    items.push(`
      <div class="notif-popover-item">
        <div class="notif-popover-item-title" style="display:flex;align-items:center;gap:0.4rem;">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fbbf24" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="6" y1="12" x2="10" y2="12"/><line x1="8" y1="10" x2="8" y2="14"/><line x1="15" y1="13" x2="15.01" y2="13"/><line x1="18" y1="11" x2="18.01" y2="11"/><rect x="2" y="6" width="20" height="12" rx="2"/></svg>
          <span>Invitación LFG de @${escapeHtml(inv.sender)}</span>
        </div>
        <div class="notif-popover-item-sub">${escapeHtml(inv.game)} · "${escapeHtml(inv.message || 'Sin mensaje')}"</div>
        <div class="notif-popover-actions">
          <button type="button" class="btn btn-success btn-sm btn-notif-accept-inv" data-id="${escapeHtml(inv.id)}">Aceptar</button>
          <button type="button" class="btn btn-danger btn-sm btn-notif-reject-inv" data-id="${escapeHtml(inv.id)}">Rechazar</button>
        </div>
      </div>
    `);
  });

  if (items.length === 0) {
    listEl.innerHTML = '<div style="padding:1.25rem;text-align:center;font-size:0.8rem;color:var(--text-muted);">Estás al día · No tienes alertas pendientes.</div>';
    return;
  }
  listEl.innerHTML = items.join('');

  const pop = document.getElementById('notifications-popover');
  listEl.querySelectorAll('.btn-notif-open-dm').forEach(b => {
    b.addEventListener('click', () => {
      if (pop) pop.style.display = 'none';
      openDirectChatWith(b.dataset.user);
    });
  });
  listEl.querySelectorAll('.btn-notif-accept-fr').forEach(b => {
    b.addEventListener('click', async () => {
      await handleRespondFriendRequest(b.dataset.id, 'ACCEPTED', b.dataset.user);
      renderNotificationsPopover();
    });
  });
  listEl.querySelectorAll('.btn-notif-reject-fr').forEach(b => {
    b.addEventListener('click', async () => {
      await handleRespondFriendRequest(b.dataset.id, 'REJECTED', b.dataset.user);
      renderNotificationsPopover();
    });
  });
  listEl.querySelectorAll('.btn-notif-accept-inv, .btn-notif-reject-inv').forEach(b => {
    b.addEventListener('click', async () => {
      const status = b.classList.contains('btn-notif-accept-inv') ? 'ACCEPTED' : 'REJECTED';
      const res = await API.respondMatchRequest(b.dataset.id, status);
      if (res.ok) {
        showToast(status === 'ACCEPTED' ? 'Invitación aceptada.' : 'Invitación rechazada.');
        await loadInvites();
        renderNotificationsPopover();
      }
    });
  });
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

function syncProfileCardFrameUi(rawFrame) {
  const key = FRAME_META[String(rawFrame || 'NONE').trim().toUpperCase()]
    ? String(rawFrame).trim().toUpperCase()
    : 'NONE';
  const info = getFrameInfo(key);

  const hiddenInp = document.getElementById('prof-card-frame-value');
  if (hiddenInp) hiddenInp.value = key;
  const labelEl = document.getElementById('prof-card-frame-active-label');
  if (labelEl) labelEl.innerText = `Marco activo: ${info.label}`;

  document.querySelectorAll('#prof-card-frame-picker .frame-swatch').forEach(btn => {
    btn.classList.toggle('active', String(btn.dataset.frame || '').toUpperCase() === key);
  });

  ['profile-hero-pass', 'profile-avatar-frame'].forEach(id => {
    const el = document.getElementById(id);
    if (!el) return;
    Object.values(FRAME_META).forEach(m => el.classList.remove(m.cls));
    el.classList.add(info.cls);
  });
}

function renderProfileIdentityBadges(profile = null) {
  const user = state.currentUser;
  if (!user) return;
  const defaultAvatar = defaultAvatarUrl(user.username);
  const avatarSrc = user.avatar || defaultAvatar;

  const passCover = document.getElementById('profile-pass-cover');
  if (passCover && (profile?.game || !passCover.style.backgroundImage)) {
    passCover.style.backgroundImage = `linear-gradient(180deg, rgba(10, 15, 27, 0.25), rgba(10, 15, 27, 0.92)), url("${getGameCoverArtwork(profile?.game || 'valorant')}")`;
  }

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
      rateBadge.innerText = `${stars.toFixed(1)} ★ (${votes} votos) · ${lowVis ? 'Visibilidad reducida' : 'Prioridad Alta'}`;
    }
    const visNote = document.getElementById('prof-rating-visibility-note');
    if (visNote) {
      visNote.innerText = lowVis
        ? 'Tu valoración está bajo 2.0★: aparecerás menos recomendado hasta subir tu puntuación.'
        : 'Con 2.0★ o más tu perfil aparece con prioridad normal en las recomendaciones.';
    }

    const passGame = document.getElementById('prof-pass-game-pill');
    if (passGame) {
      passGame.className = `badge-game ${profile.game || 'valorant'}`;
      passGame.innerText = profile.gameName || profile.game || 'PlayMatch';
    }
    const passRank = document.getElementById('prof-pass-rank-pill');
    if (passRank) {
      passRank.className = `chip-plat ${getRankTierClass(profile.rank)}`;
      passRank.innerText = `${profile.rank || 'Sin rango'} · ${profile.role || 'Competitivo'}`;
    }
    const passSub = document.getElementById('prof-pass-sub');
    if (passSub) {
      passSub.innerText = `${profile.region || 'Chile'} · ${profile.preferredSchedule || 'Horario flexible'}`;
    }
    syncProfileCardFrameUi(profile.cardFrame || 'NONE');
    syncProfileTitleUi(profile.equippedTitle || '');
  }
}

function syncProfileTitleUi(rawTitle) {
  const clean = String(rawTitle || '').trim();
  const valEl = document.getElementById('prof-equipped-title-value');
  if (valEl) valEl.value = clean;
  const lblEl = document.getElementById('prof-equipped-title-label');
  if (lblEl) lblEl.innerText = `Título activo: ${clean || 'Ninguno'}`;
  const passPill = document.getElementById('prof-pass-title-pill');
  if (passPill) {
    if (clean) {
      passPill.innerText = clean;
      passPill.style.display = 'inline-flex';
    } else {
      passPill.style.display = 'none';
    }
  }
  document.querySelectorAll('#prof-title-picker .achievement-card').forEach(card => {
    card.classList.toggle('active', Boolean(clean) && String(card.dataset.title || '') === clean);
  });
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

    await Promise.all([loadBlockedUsers(), loadIntegrationsStatus(), loadProfileAchievementsAndAnalytics()]);
  } catch (err) {
    console.error('Error cargando perfil:', err);
  }
}

async function loadProfileAchievementsAndAnalytics() {
  if (!state.currentUser) return;
  try {
    const [achRes, statsRes] = await Promise.all([
      API.getPlayerAchievements(state.currentUser.username).catch(() => ({ ok: false })),
      API.getPlayerAnalytics(state.currentUser.username).catch(() => ({ ok: false }))
    ]);

    if (achRes && achRes.ok && achRes.data) {
      renderProfileAchievements(achRes.data);
    }
    if (statsRes && statsRes.ok && statsRes.data) {
      renderProfileAnalyticsAndClips(statsRes.data);
    }
  } catch (err) {
    console.error('Error cargando logros y analíticas:', err);
  }
}

function renderProfileAchievements(data) {
  const grid = document.getElementById('prof-title-picker');
  if (!grid) return;
  const list = Array.isArray(data.achievements) ? data.achievements : [];
  const activeTitle = String(data.equippedTitle || '').trim();
  syncProfileTitleUi(activeTitle);

  grid.innerHTML = list.map(ach => {
    const unlocked = Boolean(ach.unlocked);
    const isActive = unlocked && activeTitle === ach.title;
    return `
      <button type="button" class="achievement-card ${unlocked ? '' : 'locked'} ${isActive ? 'active' : ''}" data-title="${escapeHtml(ach.title)}" data-unlocked="${unlocked ? '1' : '0'}">
        <div class="achievement-card-top">
          <span class="achievement-title">${escapeHtml(ach.title)}</span>
          <span class="achievement-state ${unlocked ? 'unlocked' : 'locked'}">${unlocked ? (isActive ? 'Equipado' : 'Desbloqueado') : escapeHtml(ach.progress || 'Bloqueado')}</span>
        </div>
        <div class="achievement-desc">${escapeHtml(ach.description)}</div>
      </button>
    `;
  }).join('');

  grid.querySelectorAll('.achievement-card').forEach(btn => {
    btn.addEventListener('click', async () => {
      if (btn.dataset.unlocked !== '1') {
        showToast('Aún no cumples el requisito para este título.', true);
        return;
      }
      const clickedTitle = String(btn.dataset.title || '');
      const currentTitle = document.getElementById('prof-equipped-title-value')?.value || '';
      const nextTitle = (currentTitle === clickedTitle) ? '' : clickedTitle;
      try {
        const { ok, data: res } = await API.updateProfile({
          username: state.currentUser.username,
          equippedTitle: nextTitle
        });
        if (!ok) {
          showToast((res && res.error) || 'No se pudo equipar el título.', true);
          return;
        }
        renderProfileAchievements({ equippedTitle: nextTitle, achievements: list });
        showToast(nextTitle ? `Título "${nextTitle}" equipado en tu perfil.` : 'Título removido de tu perfil.');
        loadLfgPlayers();
        loadRecommendations();
        loadLeaderboard();
      } catch (err) {
        showToast('Error al actualizar título.', true);
      }
    });
  });
}

function renderProfileAnalyticsAndClips(stats) {
  const setText = (id, val) => {
    const el = document.getElementById(id);
    if (el) el.innerText = val;
  };
  setText('stat-kpi-tourneys', String(Number(stats.tournamentsJoined || 0)));
  setText('stat-kpi-wins', String(Number(stats.matchesWon || 0)));
  setText('stat-kpi-friends', String(Number(stats.friendsCount || 0)));
  setText('stat-kpi-clips', String(Number(stats.clipsCount || 0)));
  setText('analytics-winrate-pill', `${Number(stats.winRate || 0)}% Win Rate`);

  const bdWrap = document.getElementById('prof-star-breakdown');
  if (bdWrap && stats.starBreakdown) {
    const breakdown = stats.starBreakdown;
    const total = [5, 4, 3, 2, 1].reduce((acc, s) => acc + Number(breakdown[String(s)] || 0), 0);
    bdWrap.innerHTML = [5, 4, 3, 2, 1].map(star => {
      const cnt = Number(breakdown[String(star)] || 0);
      const pct = total > 0 ? Math.round((cnt * 100) / total) : 0;
      return `
        <div class="star-bar-row">
          <span>${star} ★</span>
          <div class="star-bar-track"><div class="star-bar-fill" style="width:${pct}%"></div></div>
          <span style="text-align:right;color:var(--text-muted);">${cnt}</span>
        </div>
      `;
    }).join('');
  }

  const matesWrap = document.getElementById('prof-recent-teammates');
  if (matesWrap) {
    const mates = Array.isArray(stats.recentTeammates) ? stats.recentTeammates : [];
    if (mates.length === 0) {
      matesWrap.innerHTML = '<span style="font-size:0.75rem;color:var(--text-muted);">Aún no agregas compañeros en tu escuadra.</span>';
    } else {
      matesWrap.innerHTML = mates.map(m => `
        <span class="mate-chip clickable-player-handle" data-profile-user="${escapeHtml(m.username)}">
          <img src="${escapeHtml(m.avatarUrl || defaultAvatarUrl(m.username))}" alt="${escapeHtml(m.username)}">
          <span>@${escapeHtml(m.username)}</span>
        </span>
      `).join('');
      matesWrap.querySelectorAll('[data-profile-user]').forEach(el => {
        el.addEventListener('click', () => openPublicProfile(el.dataset.profileUser));
      });
    }
  }

  const clipsWrap = document.getElementById('prof-clips-list');
  if (clipsWrap) {
    const clips = Array.isArray(stats.clips) ? stats.clips : [];
    if (clips.length === 0) {
      clipsWrap.innerHTML = '<div style="font-size:0.78rem;color:var(--text-muted);">No has subido clips o highlights todavía.</div>';
    } else {
      clipsWrap.innerHTML = clips.map(cl => {
        const meta = extractClipMeta(cl.clipUrl);
        return `
          <div class="clip-item-card">
            <div style="min-width:0;flex:1;">
              <div style="display:flex;align-items:center;gap:0.4rem;margin-bottom:0.15rem;">
                <span class="clip-play-tag">${escapeHtml(meta.platform)}</span>
                <span style="font-size:0.7rem;color:var(--text-muted);">${escapeHtml(cl.gameName || cl.gameCode)}</span>
              </div>
              <strong style="font-size:0.82rem;color:#fff;display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${escapeHtml(cl.title)}</strong>
            </div>
            <div style="display:flex;gap:0.35rem;flex-shrink:0;">
              <a href="${escapeHtml(cl.clipUrl)}" target="_blank" rel="noopener noreferrer" class="btn btn-secondary btn-sm">Ver</a>
              <button type="button" class="btn btn-danger btn-sm btn-del-clip" data-cid="${escapeHtml(cl.id)}">&times;</button>
            </div>
          </div>
        `;
      }).join('');
      clipsWrap.querySelectorAll('.btn-del-clip').forEach(btn => {
        btn.addEventListener('click', async () => {
          const res = await API.deletePlayerClip(state.currentUser.username, btn.dataset.cid);
          if (res.ok) {
            showToast('Clip eliminado.');
            loadProfileAchievementsAndAnalytics();
          }
        });
      });
    }
  }
}

async function loadBlockedUsers() {
  const listEl = document.getElementById('blocked-users-list');
  if (!listEl || !state.currentUser) return;
  try {
    const blocked = await API.getBlockedUsers(state.currentUser.username);
    if (!blocked || blocked.length === 0) {
      listEl.innerHTML = '<div style="font-size:0.8rem;color:var(--text-muted);">No has bloqueado a ningún jugador.</div>';
      return;
    }
    listEl.innerHTML = blocked.map(b => `
      <div class="blocked-user-row">
        <div style="display:flex;align-items:center;gap:0.55rem;">
          <img src="${escapeHtml(b.avatarUrl)}" alt="${escapeHtml(b.username)}" style="width:30px;height:30px;border-radius:50%;object-fit:cover;">
          <strong style="color:#fff;font-size:0.84rem;">@${escapeHtml(b.username)}</strong>
        </div>
        <button type="button" class="btn btn-secondary btn-sm btn-unblock-row" data-user="${escapeHtml(b.username)}">Desbloquear</button>
      </div>
    `).join('');
    listEl.querySelectorAll('.btn-unblock-row').forEach(btn => {
      btn.addEventListener('click', async () => {
        const res = await API.unblockUser(state.currentUser.username, btn.dataset.user);
        if (res.ok) {
          showToast(`Has desbloqueado a @${btn.dataset.user}.`);
          await Promise.all([loadBlockedUsers(), loadLfgPlayers(), loadRecommendations(), loadLobbies(), loadLeaderboard()]);
        }
      });
    });
  } catch (err) {
    console.error('Error cargando bloqueados:', err);
  }
}

async function loadIntegrationsStatus() {
  const grid = document.getElementById('integrations-status-grid');
  const pill = document.getElementById('integrations-engine-pill');
  if (!grid) return;
  try {
    const st = await API.getIntegrationsStatus();
    if (!st) return;
    if (pill) pill.innerText = st.dbEngine || 'SQL';
    const pushPerm = (typeof Notification !== 'undefined') ? Notification.permission : 'unsupported';
    const swActive = Boolean(swRegistration || (navigator.serviceWorker && navigator.serviceWorker.controller));
    const items = [
      { label: 'Base de Datos', ok: true, detail: st.isPostgres ? `PostgreSQL (${st.safePostgresTarget || 'conectado'})` : 'SQLite local persistente (.env DATABASE_URL)' },
      { label: 'Motor Gemini API', ok: Boolean(st.geminiConfigured), detail: st.geminiConfigured ? `Activo (${st.geminiModel})` : 'Modo afinidad local (.env sin GEMINI_API_KEY)' },
      { label: 'Discord OAuth2', ok: Boolean(st.discordOAuthConfigured), detail: st.discordOAuthConfigured ? 'OAuth2 configurado' : 'Vinculación manual activa (.env sin client_id)' },
      { label: 'Verificación SMS', ok: Boolean(st.twilioConfigured), detail: st.twilioConfigured ? 'Twilio SMS real activo' : 'Modo Demo en pantalla (.env sin Twilio)' },
      { label: 'App PWA & Service Worker', ok: swActive, detail: swActive ? 'Manifest + Service Worker listo para instalar' : 'Registrando Service Worker...' },
      { label: 'Notificaciones Push', ok: pushPerm === 'granted', detail: pushPerm === 'granted' ? 'Permiso concedido en navegador' : (pushPerm === 'denied' ? 'Bloqueadas en navegador' : 'Pendiente de activar') }
    ];
    grid.innerHTML = items.map(it => `
      <div class="integration-status-card">
        <div>
          <strong style="display:block;font-size:0.82rem;color:#fff;">${escapeHtml(it.label)}</strong>
          <span style="font-size:0.74rem;color:var(--text-secondary);">${escapeHtml(it.detail)}</span>
        </div>
        <span class="status-pill ${it.ok ? 'verified' : 'pending'}">${it.ok ? 'Activo' : 'Local'}</span>
      </div>
    `).join('');
  } catch (err) {
    console.error('Error cargando estado integraciones:', err);
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
    const defaultUrl = defaultAvatarUrl(state.currentUser.username);
    await persistAvatarChange(defaultUrl);
  });

  // 1b. Selector interactivo de Marcos LoL para perfil y tarjetas
  document.querySelectorAll('#prof-card-frame-picker .frame-swatch').forEach(btn => {
    btn.addEventListener('click', async () => {
      if (!state.currentUser) return;
      const frame = String(btn.dataset.frame || 'NONE').toUpperCase();
      const info = getFrameInfo(frame);
      syncProfileCardFrameUi(frame);
      try {
        const { ok, data } = await API.updateProfile({
          username: state.currentUser.username,
          cardFrame: frame
        });
        if (!ok) {
          showToast((data && data.error) || 'No se pudo equipar el marco.', true);
          return;
        }
        if (data && data.profile) syncProfileCardFrameUi(data.profile.cardFrame || frame);
        showToast(`Marco "${info.label}" equipado en tu perfil y tarjetas.`);
        loadLfgPlayers();
        loadRecommendations();
        loadLeaderboard();
      } catch (err) {
        showToast('Error al guardar el marco personalizado.', true);
      }
    });
  });

  // Vista previa instantanea de portada al cambiar de juego principal
  document.getElementById('prof-game')?.addEventListener('change', (e) => {
    const passCover = document.getElementById('profile-pass-cover');
    if (passCover) {
      passCover.style.backgroundImage = `linear-gradient(180deg, rgba(10, 15, 27, 0.25), rgba(10, 15, 27, 0.92)), url("${getGameCoverArtwork(e.target.value)}")`;
    }
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

  document.getElementById('btn-discord-oauth')?.addEventListener('click', async () => {
    if (!state.currentUser) return;
    try {
      const { ok, data } = await API.getDiscordOAuthUrl(state.currentUser.username);
      if (ok && data && data.configured && data.url) {
        window.location.href = data.url;
        return;
      }
      showToast((data && data.message) || 'Usa el campo de usuario de Discord arriba para vincular manualmente.', false);
      document.getElementById('discord-username-input')?.focus();
    } catch (err) {
      showToast('No se pudo consultar Discord OAuth2.', true);
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
      cardFrame: document.getElementById('prof-card-frame-value')?.value || 'NONE',
      equippedTitle: document.getElementById('prof-equipped-title-value')?.value || '',
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

      showToast('Perfil, gustos y preferencias guardados.');
      loadLfgPlayers();
      loadRecommendations();
      loadGames();
      loadProfileAchievementsAndAnalytics();
    } catch (err) {
      showToast('Error al conectar con el servidor Java.', true);
    }
  });

  // 6. Subir Highlight / Clip al Dossier Público
  document.getElementById('btn-add-clip')?.addEventListener('click', async () => {
    if (!state.currentUser) return;
    const titleInp = document.getElementById('clip-title-input');
    const urlInp = document.getElementById('clip-url-input');
    const gameSel = document.getElementById('clip-game-select');
    const title = titleInp?.value?.trim() || '';
    const clipUrl = urlInp?.value?.trim() || '';
    const game = gameSel?.value || 'valorant';
    if (!title || !clipUrl) {
      showToast('Ingresa el título y el enlace del clip antes de agregar.', true);
      return;
    }
    try {
      const { ok, data } = await API.addPlayerClip(state.currentUser.username, title, clipUrl, game);
      if (!ok) {
        showToast((data && data.error) || 'No se pudo guardar el highlight.', true);
        return;
      }
      if (titleInp) titleInp.value = '';
      if (urlInp) urlInp.value = '';
      showToast('Highlight agregado a tu dossier público.');
      loadProfileAchievementsAndAnalytics();
    } catch (err) {
      showToast('Error al agregar clip.', true);
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
  document.getElementById('public-profile-close-btn')?.addEventListener('click', () => closeModalById('public-profile-modal'));
  document.getElementById('create-lobby-close-btn')?.addEventListener('click', () => closeModalById('create-lobby-modal'));
  document.getElementById('share-lobby-close-btn')?.addEventListener('click', () => closeModalById('share-lobby-modal'));
  document.getElementById('dm-image-close-btn')?.addEventListener('click', () => closeModalById('dm-image-modal'));
  document.getElementById('report-close-btn')?.addEventListener('click', () => closeModalById('report-modal'));
  document.getElementById('mr-close-btn')?.addEventListener('click', () => closeModalById('match-result-modal'));

  document.getElementById('dossier-btn-chat')?.addEventListener('click', async () => {
    const target = state.dossierUsername;
    if (!target) return;
    const st = getFriendStatus(target);
    if (st === 'ACCEPTED') {
      closeModalById('public-profile-modal');
      openDirectChatWith(target);
    } else if (st === 'PENDING_INCOMING') {
      await handleAcceptFriendByUsername(target);
      openPublicProfile(target);
    } else if (st !== 'PENDING_OUTGOING') {
      await handleAddFriend(target);
      openPublicProfile(target);
    }
  });
  document.getElementById('dossier-btn-invite')?.addEventListener('click', () => {
    const target = state.dossierUsername;
    if (!target) return;
    closeModalById('public-profile-modal');
    openMatchModal(target, 'Valorant');
  });
  document.getElementById('dossier-btn-rate')?.addEventListener('click', () => {
    const target = state.dossierUsername;
    if (target) openRateModal(target);
  });
  document.getElementById('dossier-btn-report')?.addEventListener('click', () => {
    const target = state.dossierUsername;
    if (target) openReportModal(target);
  });
  document.getElementById('dossier-btn-block')?.addEventListener('click', async () => {
    const target = state.dossierUsername;
    if (!target || !state.currentUser) return;
    if (state.dossierIsBlocked) {
      const res = await API.unblockUser(state.currentUser.username, target);
      if (res.ok) {
        showToast(`Has desbloqueado a @${target}.`);
        openPublicProfile(target);
        await Promise.all([loadBlockedUsers(), loadLfgPlayers(), loadRecommendations(), loadLobbies(), loadLeaderboard()]);
      }
    } else {
      const res = await API.blockUser(state.currentUser.username, target);
      if (res.ok) {
        showToast(`Has bloqueado a @${target}.`);
        closeModalById('public-profile-modal');
        if (state.activeDmFriend && state.activeDmFriend.toLowerCase() === target.toLowerCase()) {
          state.activeDmFriend = null;
          document.getElementById('ig-dm-active-thread').style.display = 'none';
          document.getElementById('ig-dm-empty-placeholder').style.display = 'block';
        }
        await Promise.all([loadFriendsInbox(), loadBlockedUsers(), loadLfgPlayers(), loadRecommendations(), loadLobbies(), loadLeaderboard()]);
      }
    }
  });

  document.getElementById('create-lobby-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!state.currentUser) return;
    const payload = {
      hostUsername: state.currentUser.username,
      title: document.getElementById('lobby-title').value.trim(),
      game: document.getElementById('lobby-game').value,
      maxSlots: document.getElementById('lobby-slots').value,
      mode: document.getElementById('lobby-mode').value.trim(),
      scheduleLabel: document.getElementById('lobby-schedule').value.trim(),
      rolesNeeded: document.getElementById('lobby-roles').value.trim()
    };
    const { ok, data } = await API.createLobby(payload);
    if (!ok) {
      showToast((data && data.error) || 'No se pudo crear el lobby.', true);
      return;
    }
    closeModalById('create-lobby-modal');
    e.target.reset();
    showToast('Lobby creado con chat grupal activo.');
    await loadLobbies();
    await loadStats();
  });

  document.getElementById('report-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!state.currentUser) return;
    const reported = document.getElementById('report-target-user').value;
    const reason = document.getElementById('report-reason').value;
    const details = document.getElementById('report-details').value.trim();
    const { ok, data } = await API.reportPlayer(state.currentUser.username, reported, reason, details);
    if (!ok) {
      showToast((data && data.error) || 'No se pudo enviar el reporte.', true);
      return;
    }
    closeModalById('report-modal');
    e.target.reset();
    showToast(`Reporte contra @${reported} registrado.`);
  });

  document.getElementById('match-result-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!state.currentUser) return;
    const tId = document.getElementById('mr-tournament-id').value;
    const mId = document.getElementById('mr-match-id').value;
    const s1 = Number(document.getElementById('mr-score1').value || 0);
    const s2 = Number(document.getElementById('mr-score2').value || 0);
    const { ok, data } = await API.submitTournamentMatchResult(tId, mId, state.currentUser.username, s1, s2);
    if (!ok) {
      showToast((data && data.error) || 'No se pudo guardar el resultado.', true);
      return;
    }
    closeModalById('match-result-modal');
    showToast('Resultado registrado y llaves actualizadas.');
    await loadTournaments();
  });

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
      await Promise.all([loadLfgPlayers(), loadRecommendations(), loadLeaderboard()]);
      if (document.getElementById('public-profile-modal')?.style.display === 'flex' &&
          state.dossierUsername && state.dossierUsername.toLowerCase() === ratedUsername.toLowerCase()) {
        openPublicProfile(ratedUsername);
      }
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

function openCreateLobbyModal() {
  document.getElementById('create-lobby-modal').style.display = 'flex';
}

function openReportModal(username) {
  document.getElementById('report-target-user').value = username;
  document.getElementById('report-modal-title').innerText = `Reportar a @${username}`;
  document.getElementById('report-modal').style.display = 'flex';
}

function openMatchResultModal(tournamentId, matchId, label, t1, t2) {
  document.getElementById('mr-tournament-id').value = tournamentId;
  document.getElementById('mr-match-id').value = matchId;
  document.getElementById('mr-match-label').innerText = `${label} · ${t1} vs ${t2}`;
  document.getElementById('mr-team1-label').innerText = t1;
  document.getElementById('mr-team2-label').innerText = t2;
  document.getElementById('mr-score1').value = 2;
  document.getElementById('mr-score2').value = 0;
  document.getElementById('match-result-modal').style.display = 'flex';
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
