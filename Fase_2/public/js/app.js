/**
 * PlayMatch Frontend Application Logic
 * Interfaz limpia y funcional orientada a proyecto de Ingeniería en Informática.
 */

// Comprobación de acceso previo: Si no hay usuario autenticado, redirigir a login independiente
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

const state = {
  currentUser: storedUser,
  currentTab: 'lfg',
  selectedGameFilter: 'all',
  selectedToxicFilter: '',
  selectedTournamentId: 't-3',
  chatInterval: null
};

document.addEventListener('DOMContentLoaded', () => {
  updateAuthUI();
  setupNavigation();
  loadLfgPlayers();
  loadTournaments();
  setupChat();
  setupModals();
  setupProfileForm();
  loadProfileData();

  // Consulta periódica del chat cada 3 segundos
  state.chatInterval = setInterval(fetchAndRenderChat, 3000);
});

function updateAuthUI() {
  const navName = document.getElementById('nav-user-name');
  const navAvatar = document.getElementById('nav-user-avatar');

  if (state.currentUser) {
    if (navName) navName.innerText = state.currentUser.username;
    if (navAvatar) navAvatar.src = state.currentUser.avatar || `https://api.dicebear.com/7.x/bottts/svg?seed=${state.currentUser.username}`;
  }
}

// 1. NAVEGACIÓN
function setupNavigation() {
  const tabBtns = document.querySelectorAll('.nav-tab-btn');
  tabBtns.forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      const targetTab = btn.dataset.tab;
      switchTab(targetTab);
    });
  });

  document.getElementById('game-filter')?.addEventListener('change', (e) => {
    state.selectedGameFilter = e.target.value;
    loadLfgPlayers();
  });

  document.getElementById('toxic-filter')?.addEventListener('change', (e) => {
    state.selectedToxicFilter = e.target.value;
    loadLfgPlayers();
  });
}

function switchTab(tabId) {
  state.currentTab = tabId;
  document.querySelectorAll('.nav-tab-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.tab === tabId);
  });

  document.querySelectorAll('.app-section').forEach(sec => {
    sec.style.display = (sec.id === `section-${tabId}`) ? 'block' : 'none';
  });

  if (tabId === 'chat') {
    fetchAndRenderChat();
  }
}

// 2. BUSCADOR DE JUGADORES (LFG)
async function loadLfgPlayers() {
  const container = document.getElementById('players-grid');
  if (!container) return;

  container.innerHTML = '<div style="color:var(--text-secondary);grid-column:1/-1;text-align:center;padding:2rem;">Cargando lista de jugadores...</div>';

  try {
    const players = await API.getPlayers(state.selectedGameFilter, state.selectedToxicFilter);
    container.innerHTML = '';

    if (!players || players.length === 0) {
      container.innerHTML = '<div style="color:var(--text-secondary);grid-column:1/-1;text-align:center;padding:2rem;">No se encontraron jugadores con los filtros seleccionados.</div>';
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
      <img src="${player.avatarUrl}" class="player-avatar" alt="${player.username}">
      <div>
        <h3>${player.username}</h3>
        <p class="player-meta-text">Ubicación: ${player.region || 'Chile'}</p>
        <span class="badge-game ${player.game}">${player.gameName || player.game}</span>
      </div>
    </div>

    <div class="player-data-table">
      <div class="player-data-cell">
        <span>Rango</span>
        <strong>${player.rank}</strong>
      </div>
      <div class="player-data-cell">
        <span>Rol</span>
        <strong>${player.role}</strong>
      </div>
      <div class="player-data-cell">
        <span>Horario</span>
        <strong>${player.preferredSchedule || 'Noches'}</strong>
      </div>
      <div class="player-data-cell">
        <span>Conducta</span>
        <strong style="color:var(--color-success);">${player.toxicLevel === 'ZERO_TOXIC' ? 'Zero-Toxic (100)' : 'Amigable'}</strong>
      </div>
    </div>

    <p class="player-bio-snippet">"${player.bio || 'Jugador activo buscando formar equipo.'}"</p>

    <div class="player-chips-row">
      ${player.riotId ? `<span class="chip-plat">Riot: ${player.riotId}</span>` : ''}
      ${player.discordTag ? `<span class="chip-plat">Discord: ${player.discordTag}</span>` : ''}
      ${player.steamId ? `<span class="chip-plat">Steam</span>` : ''}
    </div>

    ${!isCurrentUser ? `
      <button class="btn btn-primary btn-sm" onclick="openMatchModal('${player.username}', '${player.gameName || player.game}')">
        Invitar a Partida
      </button>
    ` : `
      <button class="btn btn-secondary btn-sm" onclick="switchTab('profile')">
        Editar mi Perfil
      </button>
    `}
  `;
  return div;
}

// 3. TORNEOS Y BRACKET
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
          <span class="badge-game ${t.game}">${t.gameName}</span>
          <span style="font-size:0.75rem;color:var(--text-secondary);">${t.status === 'IN_PROGRESS' ? 'En Curso' : 'Inscripción Abierta'}</span>
        </div>
        <h3>${t.title}</h3>
        <div class="tournament-table-info">
          <div>Premio: <strong>${t.prizePool}</strong></div>
          <div>Fecha: <strong>${t.startDate}</strong></div>
          <div>Equipos inscritos: <strong>${t.registeredTeamsCount} / ${t.maxTeams}</strong></div>
          <div>Organizador: <strong>${t.organizer}</strong></div>
        </div>
        <div style="display:flex;gap:0.5rem;margin-top:auto;">
          <button class="btn btn-primary btn-sm" style="flex:1;" onclick="openJoinTournamentModal('${t.id}', '${t.title}')">
            Inscribir Equipo
          </button>
          <button class="btn btn-secondary btn-sm" onclick="renderTournamentBracket('${t.id}')">
            Ver Llaves
          </button>
        </div>
      `;
      container.appendChild(card);
    });

    renderTournamentBracket(state.selectedTournamentId);

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
    if (!t) return;

    document.getElementById('bracket-tournament-title').innerText = `Llaves de Eliminación: ${t.title}`;

    if (!t.matches || t.matches.length === 0) {
      bracketContainer.innerHTML = `
        <div style="padding:1.5rem;text-align:center;color:var(--text-muted);font-size:0.85rem;">
          Las llaves de este torneo se generarán al completar los cupos (${t.registeredTeamsCount} de ${t.maxTeams} registrados).
        </div>
      `;
      return;
    }

    const semis = t.matches.filter(m => m.roundName.includes('Semifinal'));
    const finals = t.matches.filter(m => m.roundName.includes('Final') && !m.roundName.includes('Semi'));

    let html = `
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

        <div class="bracket-round-column" style="max-width:180px;text-align:center;">
          <div class="round-name-tag">Campeón</div>
          <div class="match-box-item" style="padding:1rem;background-color:rgba(16,185,129,0.1);border-color:var(--color-success);">
            <strong style="color:var(--text-primary);display:block;">
              ${finals[0]?.winner || 'Por Definir'}
            </strong>
          </div>
        </div>
      </div>
    `;

    bracketContainer.innerHTML = html;

  } catch (err) {
    console.error('Error renderizando bracket:', err);
  }
}

function createMatchBoxHtml(m) {
  const isFinished = m.status === 'FINISHED';
  return `
    <div class="match-box-item">
      <div class="match-row-team ${isFinished && m.winner === m.team1 ? 'winner' : ''}">
        <span>${m.team1 || 'Por definir'}</span>
        <span class="score">${m.scoreTeam1}</span>
      </div>
      <div class="match-row-team ${isFinished && m.winner === m.team2 ? 'winner' : ''}">
        <span>${m.team2 || 'Por definir'}</span>
        <span class="score">${m.scoreTeam2}</span>
      </div>
    </div>
  `;
}

// 4. SALA DE COORDINACIÓN (CHAT)
function setupChat() {
  const input = document.getElementById('chat-message-input');
  const btn = document.getElementById('chat-send-btn');

  btn?.addEventListener('click', handleSendMessage);
  input?.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') handleSendMessage();
  });
}

async function handleSendMessage() {
  const input = document.getElementById('chat-message-input');
  const content = input?.value?.trim();
  if (!content) return;

  if (!state.currentUser) {
    openLoginModal();
    showToast('Debes iniciar sesión para enviar mensajes al chat.', true);
    return;
  }

  try {
    await API.sendChatMessage(state.currentUser.username, content, 'global');
    input.value = '';
    fetchAndRenderChat();
  } catch (err) {
    console.error('Error enviando mensaje:', err);
    showToast('Error al enviar el mensaje al servidor.', true);
  }
}

async function fetchAndRenderChat() {
  const msgContainer = document.getElementById('chat-messages-container');
  if (!msgContainer) return;

  try {
    const messages = await API.getChatMessages();
    msgContainer.innerHTML = '';

    if (!messages || messages.length === 0) {
      msgContainer.innerHTML = `
        <div class="chat-empty-state">
          No hay mensajes en este canal todavía.<br>
          Escribe a continuación para iniciar la coordinación.
        </div>
      `;
      return;
    }

    messages.forEach(m => {
      const isMe = Boolean(state.currentUser && m.sender.toLowerCase() === state.currentUser.username.toLowerCase());
      const item = document.createElement('div');
      item.className = `message-card ${isMe ? 'own' : ''}`;

      item.innerHTML = `
        <img src="${m.avatar}" class="msg-avatar-img" alt="${m.sender}">
        <div class="msg-body-wrapper">
          <div class="msg-meta-line">
            <strong>${m.sender}</strong>
            <span>${m.timestamp}</span>
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

// 5. PERFIL DE USUARIO
function populateProfileFields(profile) {
  if (!profile) return;
  const setVal = (id, val) => {
    const el = document.getElementById(id);
    if (el && val !== undefined && val !== null) el.value = val;
  };
  setVal('prof-bio', profile.bio || '');
  setVal('prof-region', profile.region || '');
  setVal('prof-game', profile.game || 'valorant');
  setVal('prof-rank', profile.rank || '');
  setVal('prof-role', profile.role || '');
  setVal('prof-riot', profile.riotId || '');
  setVal('prof-discord', profile.discordTag || '');
  setVal('prof-schedule', profile.preferredSchedule || '');
}

function clearProfileFields() {
  const setVal = (id, val) => {
    const el = document.getElementById(id);
    if (el) el.value = val;
  };
  setVal('prof-bio', '');
  setVal('prof-region', '');
  setVal('prof-game', 'valorant');
  setVal('prof-rank', '');
  setVal('prof-role', '');
  setVal('prof-riot', '');
  setVal('prof-discord', '');
  setVal('prof-schedule', '');
}

async function loadProfileData() {
  if (!state.currentUser) {
    clearProfileFields();
    return;
  }
  try {
    const players = await API.getPlayers('all');
    if (Array.isArray(players)) {
      const myProfile = players.find(p => p.username.toLowerCase() === state.currentUser.username.toLowerCase());
      if (myProfile) {
        populateProfileFields(myProfile);
      }
    }
  } catch (err) {
    console.error('Error cargando perfil:', err);
  }
}

function setupProfileForm() {
  document.getElementById('profile-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!state.currentUser) {
      openLoginModal();
      showToast('Debes iniciar sesión para guardar cambios en tu perfil.', true);
      return;
    }
    const data = {
      username: state.currentUser.username,
      bio: document.getElementById('prof-bio').value,
      region: document.getElementById('prof-region').value,
      game: document.getElementById('prof-game').value,
      rank: document.getElementById('prof-rank').value,
      role: document.getElementById('prof-role').value,
      riotId: document.getElementById('prof-riot').value,
      discordTag: document.getElementById('prof-discord').value,
      preferredSchedule: document.getElementById('prof-schedule').value
    };

    try {
      await API.updateProfile(data);
      showToast('Perfil actualizado correctamente.');
      loadLfgPlayers();
    } catch (err) {
      showToast('Error al actualizar el perfil.', true);
    }
  });
}

// 6. MODALES Y NAVEGACIÓN
function setupModals() {
  // Cierres de modales
  document.getElementById('modal-close-btn')?.addEventListener('click', closeModal);
  document.getElementById('tournament-modal-close-btn')?.addEventListener('click', closeTournamentModal);

  // Cerrar sesión y volver a la ventana independiente de login
  document.getElementById('btn-logout')?.addEventListener('click', () => {
    localStorage.removeItem('playmatch_user');
    sessionStorage.removeItem('playmatch_user');
    window.location.href = 'login.html';
  });

  // Cerrar al hacer clic en el backdrop oscuro
  window.addEventListener('click', (e) => {
    if (e.target.classList.contains('modal-overlay')) {
      e.target.style.display = 'none';
    }
  });

  // Modal: Invitar a jugar
  document.getElementById('match-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!state.currentUser) {
      window.location.href = 'login.html';
      return;
    }
    const receiver = document.getElementById('match-target-user').value;
    const game = document.getElementById('match-target-game').value;
    const msg = document.getElementById('match-message-input').value;

    try {
      await API.sendMatchRequest(state.currentUser.username, receiver, game, msg);
      closeModal();
      showToast(`Invitación enviada a @${receiver}.`);
      fetchAndRenderChat();
    } catch (err) {
      showToast('Error al enviar la invitación.', true);
    }
  });

  // Modal: Registrar equipo en torneo
  document.getElementById('join-tournament-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!state.currentUser) {
      window.location.href = 'login.html';
      return;
    }
    const tId = document.getElementById('join-t-id').value;
    const teamName = document.getElementById('join-team-name').value;

    try {
      const res = await API.joinTournament(tId, teamName);
      closeTournamentModal();
      showToast(res.message || 'Equipo inscrito con éxito.');
      loadTournaments();
    } catch (err) {
      showToast('Error al procesar la inscripción.', true);
    }
  });
}

function openMatchModal(username, game) {
  if (!state.currentUser) {
    window.location.href = 'login.html';
    return;
  }
  document.getElementById('match-target-user').value = username;
  document.getElementById('match-target-game').value = game;
  document.getElementById('modal-title').innerText = `Invitar a @${username} (${game})`;
  document.getElementById('match-modal').style.display = 'flex';
}

function closeModal() {
  document.getElementById('match-modal').style.display = 'none';
}

function openJoinTournamentModal(tournamentId, tournamentTitle) {
  if (!state.currentUser) {
    window.location.href = 'login.html';
    return;
  }
  document.getElementById('join-t-id').value = tournamentId;
  document.getElementById('join-modal-title').innerText = `Inscripción: ${tournamentTitle}`;
  document.getElementById('join-tournament-modal').style.display = 'flex';
}

function closeTournamentModal() {
  document.getElementById('join-tournament-modal').style.display = 'none';
}

let toastTimeout = null;
function showToast(message, isError = false) {
  const toast = document.getElementById('app-toast');
  if (!toast) return;
  toast.innerText = message;
  toast.style.display = 'block';

  if (isError) {
    toast.style.borderColor = 'rgba(239, 68, 68, 0.6)';
    toast.style.color = '#fca5a5';
    toast.style.backgroundColor = '#181124';
  } else {
    toast.style.borderColor = 'var(--color-primary)';
    toast.style.color = 'var(--text-primary)';
    toast.style.backgroundColor = 'var(--bg-card)';
  }

  if (toastTimeout) clearTimeout(toastTimeout);
  toastTimeout = setTimeout(() => { toast.style.display = 'none'; }, 4000);
}

function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
