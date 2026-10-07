/**
 * PlayMatch Frontend Application Logic
 */

// Estado global de la aplicación
const state = {
  currentUser: {
    username: 'Naxoo_Viper',
    email: 'jesus.villasanti@playmatch.cl',
    avatar: 'https://api.dicebear.com/7.x/bottts/svg?seed=Naxoo_Viper',
    reputation: 100,
    toxicLevel: 'ZERO_TOXIC'
  },
  currentTab: 'lfg',
  selectedGameFilter: 'all',
  selectedToxicFilter: '',
  selectedTournamentId: 't-3',
  chatInterval: null
};

// Inicialización al cargar la ventana
document.addEventListener('DOMContentLoaded', () => {
  setupNavigation();
  loadLfgPlayers();
  loadTournaments();
  setupChat();
  setupModals();
  loadProfileData();

  // Polling para mensajes de chat cada 3.5 segundos
  state.chatInterval = setInterval(fetchAndRenderChat, 3500);
});

// 1. NAVEGACIÓN ENTRE SECCIONES
function setupNavigation() {
  const navLinks = document.querySelectorAll('.nav-link');
  navLinks.forEach(link => {
    link.addEventListener('click', (e) => {
      e.preventDefault();
      const targetTab = link.dataset.tab;
      switchTab(targetTab);
    });
  });

  // Filtros de juegos en LFG
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
  document.querySelectorAll('.nav-link').forEach(l => {
    l.classList.toggle('active', l.dataset.tab === tabId);
  });

  document.querySelectorAll('.app-section').forEach(sec => {
    sec.style.display = (sec.id === `section-${tabId}`) ? 'block' : 'none';
  });

  if (tabId === 'chat') {
    fetchAndRenderChat();
  }
}

// 2. MÓDULO DE EMPAREJAMIENTO SOCIAL (LFG)
async function loadLfgPlayers() {
  const container = document.getElementById('players-grid');
  if (!container) return;

  container.innerHTML = '<div style="color:var(--text-muted);grid-column:1/-1;text-align:center;padding:2rem;">Cargando jugadores compatibles...</div>';

  try {
    const players = await API.getPlayers(state.selectedGameFilter, state.selectedToxicFilter);
    container.innerHTML = '';

    if (!players || players.length === 0) {
      container.innerHTML = '<div style="color:var(--text-muted);grid-column:1/-1;text-align:center;padding:2rem;">No se encontraron jugadores con los filtros seleccionados.</div>';
      return;
    }

    players.forEach(player => {
      const card = createPlayerCard(player);
      container.appendChild(card);
    });
  } catch (err) {
    console.error('Error cargando jugadores:', err);
    container.innerHTML = '<div style="color:var(--accent-rose);grid-column:1/-1;text-align:center;padding:2rem;">Error al conectar con el servidor Java.</div>';
  }
}

function createPlayerCard(player) {
  const div = document.createElement('div');
  div.className = 'player-card';

  const isCurrentUser = player.username.toLowerCase() === state.currentUser.username.toLowerCase();

  div.innerHTML = `
    <div class="player-card-header">
      <img src="${player.avatarUrl}" class="player-avatar" alt="${player.username}">
      <div class="player-identity">
        <h3>${player.username}</h3>
        <p class="player-region">📍 ${player.region || 'Chile'}</p>
        <span class="game-badge ${player.game}">${player.gameName || player.game}</span>
      </div>
    </div>

    <div class="player-stats-row">
      <div>
        <span>Rango Oficial</span>
        <strong>${player.rank}</strong>
      </div>
      <div>
        <span>Rol Favorito</span>
        <strong>${player.role}</strong>
      </div>
      <div>
        <span>Horario</span>
        <strong>${player.preferredSchedule || 'Noches'}</strong>
      </div>
      <div>
        <span>Reputación</span>
        <strong style="color:var(--accent-green);">🛡️ ${player.toxicLevel === 'ZERO_TOXIC' ? 'Zero Toxic (100%)' : 'Friendly'}</strong>
      </div>
    </div>

    <p class="player-bio">"${player.bio || 'Jugador competitivo buscando coordinar equipo.'}"</p>

    <div class="platform-tags">
      ${player.riotId ? `<span class="tag-plat">🎯 Riot: ${player.riotId}</span>` : ''}
      ${player.discordTag ? `<span class="tag-plat">💬 Discord: ${player.discordTag}</span>` : ''}
      ${player.steamId ? `<span class="tag-plat">🎮 Steam</span>` : ''}
    </div>

    ${!isCurrentUser ? `
      <button class="btn-primary" onclick="openMatchModal('${player.username}', '${player.gameName || player.game}')">
        ⚔️ Invitar a Dúo / Escuadra
      </button>
    ` : `
      <button class="btn-secondary" onclick="switchTab('profile')">
        ✏️ Editar mi Perfil
      </button>
    `}
  `;
  return div;
}

// 3. MÓDULO DE GESTIÓN DE TORNEOS Y BRACKET
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
        <div class="tournament-banner" style="background-image:url('${t.bannerUrl}')">
          <span class="game-badge ${t.game}">${t.gameName}</span>
        </div>
        <div class="tournament-info">
          <h3 class="tournament-title">${t.title}</h3>
          <div class="tournament-meta">
            <span>🏆 Premio: <strong class="prize-badge">${t.prizePool}</strong></span>
            <span>📅 Fecha: <strong>${t.startDate}</strong></span>
            <span>👥 Equipos: <strong>${t.registeredTeamsCount} / ${t.maxTeams} inscritos</strong></span>
            <span>🏢 Organiza: <strong>${t.organizer}</strong></span>
          </div>
          <div style="display:flex;gap:0.5rem;">
            <button class="btn-primary" style="flex:1;" onclick="openJoinTournamentModal('${t.id}', '${t.title}')">
              📝 Inscribir Equipo
            </button>
            <button class="btn-secondary" onclick="renderTournamentBracket('${t.id}')">
              📊 Ver Llaves
            </button>
          </div>
        </div>
      `;
      container.appendChild(card);
    });

    // Renderizar las llaves del torneo CS2 por defecto
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

    document.getElementById('bracket-tournament-title').innerText = `Llaves de Eliminación Directa: ${t.title}`;

    if (!t.matches || t.matches.length === 0) {
      bracketContainer.innerHTML = `
        <div style="padding:2rem;text-align:center;color:var(--text-muted);">
          Las llaves de este torneo se generarán automáticamente una vez completadas las inscripciones (${t.registeredTeamsCount}/${t.maxTeams} equipos inscritos).
        </div>
      `;
      return;
    }

    // Dividir matches por rondas
    const semis = t.matches.filter(m => m.roundName.includes('Semifinal'));
    const finals = t.matches.filter(m => m.roundName.includes('Final') && !m.roundName.includes('Semi'));

    let html = `
      <div class="bracket-tree">
        <div class="bracket-round">
          <div class="round-header">SEMIFINALES (Bo1)</div>
          ${semis.map(m => createMatchBoxHtml(m)).join('')}
        </div>

        <div style="font-size:2rem;color:var(--neon-purple);font-weight:bold;">➔</div>

        <div class="bracket-round">
          <div class="round-header">GRAN FINAL (Bo3)</div>
          ${finals.map(m => createMatchBoxHtml(m)).join('')}
        </div>

        <div style="font-size:2rem;color:var(--neon-cyan);font-weight:bold;">🏆</div>

        <div class="bracket-round" style="max-width:180px;text-align:center;">
          <div class="round-header">CAMPEÓN</div>
          <div class="match-box" style="padding:1rem;background:linear-gradient(135deg,rgba(16,185,129,0.2),rgba(6,182,212,0.2));border-color:var(--accent-green);">
            <div style="font-size:1.5rem;">👑</div>
            <strong style="color:#fff;display:block;margin-top:0.4rem;">${finals[0]?.winner || 'Por Definir'}</strong>
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
    <div class="match-box">
      <div class="team-entry ${isFinished && m.winner === m.team1 ? 'winner' : ''}">
        <span>${m.team1 || 'TBD'}</span>
        <span class="team-score">${m.scoreTeam1}</span>
      </div>
      <div class="team-entry ${isFinished && m.winner === m.team2 ? 'winner' : ''}">
        <span>${m.team2 || 'TBD'}</span>
        <span class="team-score">${m.scoreTeam2}</span>
      </div>
    </div>
  `;
}

// 4. MÓDULO DE CHAT INTERNO EN TIEMPO REAL
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

  try {
    await API.sendChatMessage(state.currentUser.username, content, 'global');
    input.value = '';
    fetchAndRenderChat();
  } catch (err) {
    console.error('Error enviando mensaje:', err);
  }
}

async function fetchAndRenderChat() {
  const msgContainer = document.getElementById('chat-messages-container');
  if (!msgContainer) return;

  try {
    const messages = await API.getChatMessages();
    msgContainer.innerHTML = '';

    messages.forEach(m => {
      const isMe = m.sender === state.currentUser.username;
      const bubble = document.createElement('div');
      bubble.className = 'message-bubble';
      if (isMe) {
        bubble.style.alignSelf = 'flex-end';
        bubble.style.flexDirection = 'row-reverse';
      }

      bubble.innerHTML = `
        <img src="${m.avatar}" class="msg-avatar" alt="${m.sender}">
        <div class="msg-content-wrapper" style="${isMe ? 'background:rgba(139,92,246,0.25);border-color:var(--neon-purple);' : ''}">
          <div class="msg-author" style="${isMe ? 'justify-content:flex-end;' : ''}">
            <span>${m.sender}</span>
            <span class="msg-time">${m.timestamp}</span>
          </div>
          <div class="msg-text">${escapeHtml(m.content)}</div>
        </div>
      `;
      msgContainer.appendChild(bubble);
    });

    // Auto scroll al fondo
    msgContainer.scrollTop = msgContainer.scrollHeight;
  } catch (err) {
    console.error('Error actualizando chat:', err);
  }
}

// 5. PERFIL DE USUARIO
async function loadProfileData() {
  try {
    const profile = await API.getPlayers('all');
    const myProfile = profile.find(p => p.username.toLowerCase() === state.currentUser.username.toLowerCase());
    if (myProfile) {
      document.getElementById('prof-bio').value = myProfile.bio || '';
      document.getElementById('prof-region').value = myProfile.region || '';
      document.getElementById('prof-game').value = myProfile.game || 'valorant';
      document.getElementById('prof-rank').value = myProfile.rank || '';
      document.getElementById('prof-role').value = myProfile.role || '';
      document.getElementById('prof-riot').value = myProfile.riotId || '';
      document.getElementById('prof-discord').value = myProfile.discordTag || '';
      document.getElementById('prof-schedule').value = myProfile.preferredSchedule || '';
    }
  } catch (err) {
    console.error('Error cargando perfil:', err);
  }

  document.getElementById('profile-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
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
      showToast('✅ ¡Perfil de jugador guardado exitosamente!');
      loadLfgPlayers();
    } catch (err) {
      showToast('❌ Error al actualizar el perfil');
    }
  });
}

// 6. MODALES Y SOLICITUDES DE MATCH
function setupModals() {
  document.getElementById('modal-close-btn')?.addEventListener('click', closeModal);
  document.getElementById('tournament-modal-close-btn')?.addEventListener('click', closeTournamentModal);

  document.getElementById('match-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const receiver = document.getElementById('match-target-user').value;
    const game = document.getElementById('match-target-game').value;
    const msg = document.getElementById('match-message-input').value;

    try {
      await API.sendMatchRequest(state.currentUser.username, receiver, game, msg);
      closeModal();
      showToast(`⚔️ Solicitud de match enviada a @${receiver}`);
      fetchAndRenderChat();
    } catch (err) {
      showToast('❌ Error al enviar solicitud');
    }
  });

  document.getElementById('join-tournament-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const tId = document.getElementById('join-t-id').value;
    const teamName = document.getElementById('join-team-name').value;

    try {
      const res = await API.joinTournament(tId, teamName);
      closeTournamentModal();
      showToast(`🏆 ${res.message || 'Equipo inscrito con éxito'}`);
      loadTournaments();
    } catch (err) {
      showToast('❌ Error al inscribir equipo');
    }
  });
}

function openMatchModal(username, game) {
  document.getElementById('match-target-user').value = username;
  document.getElementById('match-target-game').value = game;
  document.getElementById('modal-title').innerText = `Invitar a @${username} (${game})`;
  document.getElementById('match-modal').style.display = 'flex';
}

function closeModal() {
  document.getElementById('match-modal').style.display = 'none';
}

function openJoinTournamentModal(tournamentId, tournamentTitle) {
  document.getElementById('join-t-id').value = tournamentId;
  document.getElementById('join-modal-title').innerText = `Inscripción: ${tournamentTitle}`;
  document.getElementById('join-tournament-modal').style.display = 'flex';
}

function closeTournamentModal() {
  document.getElementById('join-tournament-modal').style.display = 'none';
}

function showToast(message) {
  const toast = document.getElementById('app-toast');
  if (!toast) return;
  toast.innerText = message;
  toast.style.display = 'block';
  setTimeout(() => { toast.style.display = 'none'; }, 3500);
}

function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
