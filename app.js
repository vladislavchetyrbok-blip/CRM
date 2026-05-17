const COLUMNS = [
  { id: 'new', title: 'Новые лиды' },
  { id: 'meeting', title: 'Встреча / Показ' },
  { id: 'deposit', title: 'Задаток / Оформление' },
  { id: 'closed', title: 'Сделка закрыта' },
];

const PROPERTY_TYPES = ['Квартира', 'Дом', 'Земля', 'Готовый бизнес'];
const BADGE_CLASSES = {
  Квартира: 'badge--apartment',
  Дом: 'badge--house',
  Земля: 'badge--land',
  'Готовый бизнес': 'badge--business',
};
const STORAGE_KEY = 'realtor-crm-clients';
const AUTH_KEY = 'senate-crm-authenticated';
const CRM_PASSWORD = '0000';

let crmBootstrapped = false;

let clients = [];
let draggedCardId = null;
let dragJustEnded = false;
let lastFocusedElement = null;
let modalMode = 'create';
let editingClientId = null;
let filterQuery = '';
let filterType = '';

const modal = document.getElementById('clientModal');
const form = document.getElementById('clientForm');
const modalTitle = document.getElementById('modalTitle');
const modalDesc = document.getElementById('modalDesc');
const modalSubmitBtn = document.getElementById('modalSubmitBtn');
const nameInput = document.getElementById('clientName');
const phoneInput = document.getElementById('clientPhone');
const budgetInput = document.getElementById('clientBudget');
const typeInput = document.getElementById('clientType');
const commentInput = document.getElementById('clientComment');
const contactDateInput = document.getElementById('clientContactDate');
const nameError = document.getElementById('nameError');

const VALID_STATUSES = new Set(COLUMNS.map((c) => c.id));

function persist() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(clients));
  } catch (err) {
    console.error('Не удалось сохранить данные в localStorage:', err);
  }
}

function normalizeClient(raw) {
  const status = VALID_STATUSES.has(raw.status) ? raw.status : 'new';
  return {
    id: raw.id || crypto.randomUUID(),
    name: String(raw.name || 'Без имени'),
    phone: String(raw.phone || '—'),
    propertyType: PROPERTY_TYPES.includes(raw.propertyType) ? raw.propertyType : PROPERTY_TYPES[0],
    budget: String(raw.budget || '—'),
    comments: String(raw.comments || ''),
    nextContactDate: normalizeContactDate(raw.nextContactDate),
    status,
    createdAt: typeof raw.createdAt === 'number' ? raw.createdAt : Date.now(),
  };
}

function normalizeContactDate(value) {
  if (!value) return '';
  const str = String(value).trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(str)) return str;
  const parsed = new Date(str);
  if (Number.isNaN(parsed.getTime())) return '';
  return toDateOnlyString(parsed);
}

function toDateOnlyString(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function getTodayString() {
  return toDateOnlyString(new Date());
}

function dateStringFromToday(offsetDays) {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return toDateOnlyString(date);
}

function getContactDateStatus(dateStr) {
  if (!dateStr) return null;
  const today = getTodayString();
  if (dateStr < today) return 'overdue';
  if (dateStr === today) return 'today';
  return 'future';
}

function formatContactDateDisplay(dateStr) {
  const [y, m, d] = dateStr.split('-').map(Number);
  const date = new Date(y, m - 1, d);
  return date.toLocaleDateString('ru-RU', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

function renderContactDateHtml(client) {
  if (!client.nextContactDate) return '';

  const status = getContactDateStatus(client.nextContactDate);
  const label = formatContactDateDisplay(client.nextContactDate);

  return `
    <div class="card__date card__date--${status}">
      <svg class="card__date-icon" width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
        <rect x="2" y="2.5" width="10" height="9.5" rx="1.2" stroke="currentColor" stroke-width="1.1"/>
        <path d="M2 5.5h10M4.5 1.5v2M9.5 1.5v2" stroke="currentColor" stroke-width="1.1" stroke-linecap="round"/>
      </svg>
      <span>${escapeHtml(label)}</span>
    </div>
  `;
}

function loadFromStorage() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === null) return null;

    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return null;

    return parsed.map(normalizeClient);
  } catch {
    return null;
  }
}

function getSeedClients() {
  return [
    {
      id: crypto.randomUUID(),
      name: 'Иванов Алексей',
      phone: '+7 (916) 123-45-67',
      propertyType: 'Квартира',
      budget: '$350,000',
      comments: '',
      nextContactDate: dateStringFromToday(-2),
      status: 'new',
      createdAt: Date.now() - 86400000 * 2,
    },
    {
      id: crypto.randomUUID(),
      name: 'Петрова Мария',
      phone: '+7 (903) 987-65-43',
      propertyType: 'Дом',
      budget: '$780,000',
      comments: 'Интересует коттеджный посёлок за городом',
      nextContactDate: dateStringFromToday(0),
      status: 'meeting',
      createdAt: Date.now() - 86400000,
    },
    {
      id: crypto.randomUUID(),
      name: 'Сидоров Дмитрий',
      phone: '+7 (925) 555-12-34',
      propertyType: 'Готовый бизнес',
      budget: '$1,200,000',
      comments: '',
      nextContactDate: dateStringFromToday(5),
      status: 'deposit',
      createdAt: Date.now() - 3600000 * 5,
    },
  ];
}

function formatBudget(value) {
  const cleaned = String(value).replace(/[^\d.,]/g, '').replace(',', '.');
  if (!cleaned) return '—';
  const num = parseFloat(cleaned);
  if (Number.isNaN(num)) return '—';
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 0,
  }).format(num);
}

function budgetToInputValue(budget) {
  if (!budget || budget === '—') return '';
  const num = parseFloat(String(budget).replace(/[^\d.,]/g, '').replace(',', '.'));
  if (Number.isNaN(num)) return '';
  return num.toLocaleString('en-US', { maximumFractionDigits: 0 });
}

function phoneToInputValue(phone) {
  return phone && phone !== '—' ? phone : '';
}

function hasActiveFilters() {
  return filterQuery.trim() !== '' || filterType !== '';
}

function clientMatchesFilters(client) {
  if (filterType && client.propertyType !== filterType) return false;

  const query = filterQuery.trim().toLowerCase();
  if (!query) return true;

  const name = client.name.toLowerCase();
  const phone = (client.phone || '').toLowerCase();
  const queryDigits = query.replace(/\D/g, '');
  const phoneDigits = phone.replace(/\D/g, '');

  if (name.includes(query) || phone.includes(query)) return true;
  if (queryDigits.length > 0 && phoneDigits.includes(queryDigits)) return true;

  return false;
}

function getFilteredClients() {
  return clients.filter(clientMatchesFilters);
}

function getClientsByStatus(statusId) {
  return getFilteredClients().filter((c) => c.status === statusId);
}

function getStatusTitle(statusId) {
  return COLUMNS.find((c) => c.id === statusId)?.title ?? statusId;
}

function escapeCsvCell(value) {
  const str = String(value ?? '');
  if (/[",\n\r]/.test(str)) {
    return `"${str.replace(/"/g, '""')}"`;
  }
  return str;
}

function formatDeadlineForExport(client) {
  if (!client.nextContactDate) return '';
  return formatContactDateDisplay(client.nextContactDate);
}

function exportClientsToCsv() {
  if (clients.length === 0) {
    window.alert('Нет клиентов для экспорта.');
    return;
  }

  const headers = [
    'Имя',
    'Телефон',
    'Бюджет',
    'Тип недвижимости',
    'Дедлайн',
    'Статус',
    'Комментарий',
  ];

  const rows = clients.map((client) => [
    client.name,
    client.phone,
    client.budget,
    client.propertyType,
    formatDeadlineForExport(client),
    getStatusTitle(client.status),
    client.comments,
  ]);

  const csv = [headers, ...rows]
    .map((row) => row.map(escapeCsvCell).join(','))
    .join('\r\n');

  const blob = new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'senate_clients.csv';
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

function updateKanbanScrollHint() {
  const wrapper = document.querySelector('[data-kanban-scroll]');
  const board = document.getElementById('kanban');
  if (!wrapper || !board) return;

  const scrollable = board.scrollWidth > board.clientWidth + 2;
  wrapper.setAttribute('data-scrollable', scrollable ? 'true' : 'false');
}

function parseBudgetAmount(budget) {
  if (!budget || budget === '—') return 0;
  const num = parseFloat(String(budget).replace(/[^\d.,]/g, '').replace(',', '.'));
  return Number.isNaN(num) ? 0 : num;
}

function getBadgeClass(propertyType) {
  return BADGE_CLASSES[propertyType] || 'badge--default';
}

function updateDashboard() {
  const totalEl = document.getElementById('metricTotal');
  const potentialEl = document.getElementById('metricPotential');
  const closedEl = document.getElementById('metricClosed');
  const dashboardEl = document.querySelector('.dashboard');
  if (!totalEl || !potentialEl || !closedEl) return;

  const visible = getFilteredClients();
  const total = visible.length;
  const potentialSum = visible.reduce((sum, c) => sum + parseBudgetAmount(c.budget), 0);
  const closed = visible.filter((c) => c.status === 'closed').length;

  totalEl.textContent = total;
  potentialEl.textContent = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 0,
  }).format(potentialSum);
  closedEl.textContent = closed;

  dashboardEl?.classList.toggle('dashboard--filtered', hasActiveFilters());
}

function renderCard(client) {
  const card = document.createElement('article');
  card.className = 'card';
  card.draggable = true;
  card.dataset.id = client.id;
  card.setAttribute('role', 'listitem');
  card.setAttribute('tabindex', '0');
  card.classList.add('card--editable');
  card.setAttribute('aria-label', `Клиент ${client.name}. Нажмите для редактирования`);

  const commentHtml = client.comments?.trim()
    ? `<p class="card__comment">${escapeHtml(client.comments)}</p>`
    : '';

  card.innerHTML = `
    <div class="card__header">
      <h3 class="card__name">${escapeHtml(client.name)}</h3>
      <button
        type="button"
        class="card__delete"
        aria-label="Удалить клиента ${escapeHtml(client.name)}"
        data-delete-id="${client.id}"
      >
        <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
          <path d="M3.5 3.5l7 7M10.5 3.5l-7 7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
        </svg>
      </button>
    </div>
    <div class="card__meta">
      <div class="card__row">
        <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
          <path d="M3.5 1.5h2.2c.4 0 .7.3.8.7l.3 1.5c.1.3 0 .6-.2.8l-.9.9c.6 1.2 1.5 2.1 2.7 2.7l.9-.9c.2-.2.5-.3.8-.2l1.5.3c.4.1.7.4.7.8v2.2c0 .5-.4.9-.9.9C4.8 11.5 2.5 9.2 2.5 6.4c0-.5.4-.9.9-.9z" stroke="currentColor" stroke-width="1.1" stroke-linejoin="round"/>
        </svg>
        <span>${escapeHtml(client.phone || '—')}</span>
      </div>
      ${renderContactDateHtml(client)}
    </div>
    <div class="card__footer">
      <span class="badge ${getBadgeClass(client.propertyType)}">${escapeHtml(client.propertyType)}</span>
      <span class="card__budget">${escapeHtml(client.budget)}</span>
    </div>
    ${commentHtml}
  `;

  const deleteBtn = card.querySelector('.card__delete');
  deleteBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    deleteClient(client.id);
  });
  deleteBtn.addEventListener('mousedown', (e) => e.stopPropagation());

  card.addEventListener('click', (e) => {
    if (e.target.closest('.card__delete')) return;
    if (dragJustEnded) return;
    openEditModal(client.id);
  });

  card.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      openEditModal(client.id);
    }
  });

  card.addEventListener('dragstart', onDragStart);
  card.addEventListener('dragend', onDragEnd);

  return card;
}

function deleteClient(id) {
  const client = clients.find((c) => c.id === id);
  if (!client) return;

  const confirmed = window.confirm('Удалить этого клиента?');
  if (!confirmed) return;

  clients = clients.filter((c) => c.id !== id);
  persist();
  renderKanban();
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

function renderKanban() {
  const board = document.getElementById('kanban');
  board.innerHTML = '';

  COLUMNS.forEach((col) => {
    const columnEl = document.createElement('section');
    columnEl.className = 'column';
    columnEl.dataset.status = col.id;
    columnEl.setAttribute('role', 'group');
    columnEl.setAttribute('aria-label', col.title);

    const items = getClientsByStatus(col.id);

    columnEl.innerHTML = `
      <header class="column__header">
        <h2 class="column__title">${escapeHtml(col.title)}</h2>
        <span class="column__count">${items.length}</span>
      </header>
      <div class="column__body" data-drop-zone="${col.id}" role="list"></div>
    `;

    const body = columnEl.querySelector('.column__body');

    if (items.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'column__empty';
      empty.textContent = hasActiveFilters() ? 'Нет совпадений' : 'Перетащите карточку сюда';
      body.appendChild(empty);
    } else {
      items.forEach((client) => body.appendChild(renderCard(client)));
    }

    body.addEventListener('dragover', onDragOver);
    body.addEventListener('dragleave', onDragLeave);
    body.addEventListener('drop', onDrop);

    board.appendChild(columnEl);
  });

  updateDashboard();
  updateKanbanScrollHint();
}

function highlightCard(clientId) {
  const card = document.querySelector(`.card[data-id="${clientId}"]`);
  if (!card) return;
  card.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  card.animate(
    [
      { boxShadow: '0 0 0 0 rgba(201, 169, 98, 0.6)' },
      { boxShadow: '0 0 0 8px rgba(201, 169, 98, 0)' },
    ],
    { duration: 600 }
  );
}

function onDragStart(e) {
  if (e.target.closest('.card__delete')) {
    e.preventDefault();
    return;
  }

  const card = e.target.closest('.card');
  if (!card) return;

  draggedCardId = card.dataset.id;
  card.classList.add('card--dragging');

  e.dataTransfer.effectAllowed = 'move';
  e.dataTransfer.setData('text/plain', draggedCardId);

  requestAnimationFrame(() => {
    card.classList.add('card--ghost');
  });
}

function onDragEnd(e) {
  const card = e.target.closest('.card');
  if (card) {
    card.classList.remove('card--dragging', 'card--ghost');
  }
  draggedCardId = null;
  dragJustEnded = true;
  setTimeout(() => {
    dragJustEnded = false;
  }, 150);
  document.querySelectorAll('.column--drag-over').forEach((el) => {
    el.classList.remove('column--drag-over');
  });
}

function onDragOver(e) {
  e.preventDefault();
  e.dataTransfer.dropEffect = 'move';

  const column = e.currentTarget.closest('.column');
  if (column) column.classList.add('column--drag-over');

  const empty = e.currentTarget.querySelector('.column__empty');
  if (empty) empty.style.display = 'none';
}

function onDragLeave(e) {
  const column = e.currentTarget.closest('.column');
  if (!column) return;

  const related = e.relatedTarget;
  if (related && column.contains(related)) return;

  column.classList.remove('column--drag-over');
}

function onDrop(e) {
  e.preventDefault();

  const zone = e.currentTarget;
  const newStatus = zone.dataset.dropZone;
  const column = zone.closest('.column');
  column?.classList.remove('column--drag-over');

  const id = e.dataTransfer.getData('text/plain') || draggedCardId;
  if (!id) return;

  const client = clients.find((c) => c.id === id);
  if (!client || client.status === newStatus) {
    renderKanban();
    return;
  }

  client.status = newStatus;
  persist();
  renderKanban();
}

function setModalUi(mode) {
  const isEdit = mode === 'edit';
  modalTitle.textContent = isEdit ? 'Редактирование лида' : 'Новый лид';
  modalDesc.textContent = isEdit
    ? 'Измените данные клиента — карточка обновится на доске'
    : 'Заполните данные клиента для воронки продаж';
  modalSubmitBtn.textContent = isEdit ? 'Сохранить изменения' : 'Сохранить';
}

function fillFormFromClient(client) {
  nameInput.value = client.name;
  phoneInput.value = phoneToInputValue(client.phone);
  budgetInput.value = budgetToInputValue(client.budget);
  typeInput.value = PROPERTY_TYPES.includes(client.propertyType)
    ? client.propertyType
    : PROPERTY_TYPES[0];
  commentInput.value = client.comments || '';
  contactDateInput.value = client.nextContactDate || '';
}

function showModal() {
  modal.hidden = false;
  modal.setAttribute('aria-hidden', 'false');
  modal.classList.add('modal--open');
  document.body.style.overflow = 'hidden';
  requestAnimationFrame(() => nameInput.focus());
}

function openCreateModal() {
  lastFocusedElement = document.activeElement;
  modalMode = 'create';
  editingClientId = null;
  form.reset();
  nameError.textContent = '';
  nameInput.classList.remove('form-input--invalid');
  setModalUi('create');
  showModal();
}

function openEditModal(clientId) {
  const client = clients.find((c) => c.id === clientId);
  if (!client) return;

  lastFocusedElement = document.activeElement;
  modalMode = 'edit';
  editingClientId = clientId;
  form.reset();
  nameError.textContent = '';
  nameInput.classList.remove('form-input--invalid');
  fillFormFromClient(client);
  setModalUi('edit');
  showModal();
}

function closeModal() {
  modal.hidden = true;
  modal.setAttribute('aria-hidden', 'true');
  modal.classList.remove('modal--open');
  document.body.style.overflow = '';
  modalMode = 'create';
  editingClientId = null;

  if (lastFocusedElement?.focus) {
    lastFocusedElement.focus();
  }
}

function validateForm() {
  const name = nameInput.value.trim();
  if (!name) {
    nameError.textContent = 'Укажите имя клиента';
    nameInput.classList.add('form-input--invalid');
    nameInput.focus();
    return false;
  }
  nameError.textContent = '';
  nameInput.classList.remove('form-input--invalid');
  return true;
}

function getClientDataFromForm() {
  const formData = new FormData(form);
  return {
    name: formData.get('name').trim(),
    phone: formData.get('phone').trim() || '—',
    budget: formatBudget(formData.get('budget')),
    propertyType: formData.get('propertyType'),
    comments: formData.get('comments').trim(),
    nextContactDate: normalizeContactDate(formData.get('nextContactDate')),
  };
}

function saveClientFromForm(e) {
  e.preventDefault();
  if (!validateForm()) return;

  const data = getClientDataFromForm();

  if (modalMode === 'edit' && editingClientId) {
    const client = clients.find((c) => c.id === editingClientId);
    if (!client) return;

    Object.assign(client, data);
    persist();
    closeModal();
    renderKanban();
    highlightCard(client.id);
    return;
  }

  const client = {
    id: crypto.randomUUID(),
    ...data,
    status: 'new',
    createdAt: Date.now(),
  };

  clients.unshift(client);
  persist();
  closeModal();
  renderKanban();
  highlightCard(client.id);
}

function applyFilters() {
  renderKanban();
}

function initFilters() {
  const searchInput = document.getElementById('searchInput');
  const typeFilter = document.getElementById('typeFilter');

  searchInput.addEventListener('input', () => {
    filterQuery = searchInput.value;
    applyFilters();
  });

  typeFilter.addEventListener('change', () => {
    filterType = typeFilter.value;
    applyFilters();
  });
}

function initExport() {
  document.getElementById('btnExport').addEventListener('click', exportClientsToCsv);
}

function initModal() {
  document.getElementById('btnAddClient').addEventListener('click', openCreateModal);
  form.addEventListener('submit', saveClientFromForm);

  modal.querySelectorAll('[data-modal-close]').forEach((el) => {
    el.addEventListener('click', closeModal);
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !modal.hidden) closeModal();
  });

  nameInput.addEventListener('input', () => {
    if (nameInput.value.trim()) {
      nameError.textContent = '';
      nameInput.classList.remove('form-input--invalid');
    }
  });
}

function isAuthenticated() {
  return localStorage.getItem(AUTH_KEY) === 'true';
}

function setAuthenticated(value) {
  if (value) {
    localStorage.setItem(AUTH_KEY, 'true');
  } else {
    localStorage.removeItem(AUTH_KEY);
  }
}

function bootstrapCRM() {
  if (crmBootstrapped) return;
  crmBootstrapped = true;

  const stored = loadFromStorage();

  if (stored === null) {
    clients = getSeedClients();
    persist();
  } else {
    clients = stored;
  }

  renderKanban();
  initFilters();
  initExport();
  initModal();

  window.addEventListener('resize', updateKanbanScrollHint);
}

function showAuthScreen() {
  const authScreen = document.getElementById('authScreen');
  const appRoot = document.getElementById('appRoot');
  const authForm = document.getElementById('authForm');
  const authPassword = document.getElementById('authPassword');
  const authError = document.getElementById('authError');

  closeModal();

  appRoot.classList.remove('app-shell--visible');
  appRoot.classList.add('app-shell--hidden');
  appRoot.hidden = true;
  appRoot.setAttribute('aria-hidden', 'true');

  authScreen.hidden = false;
  authScreen.classList.remove('auth--exit');
  authScreen.setAttribute('aria-hidden', 'false');

  authForm.classList.remove('auth__form--shake');
  authPassword.classList.remove('auth__input--error');
  authPassword.value = '';
  authError.hidden = true;

  requestAnimationFrame(() => authPassword.focus());
}

function showAppScreen(animate = true) {
  const authScreen = document.getElementById('authScreen');
  const appRoot = document.getElementById('appRoot');

  bootstrapCRM();

  appRoot.hidden = false;
  appRoot.setAttribute('aria-hidden', 'false');
  appRoot.classList.remove('app-shell--hidden');

  requestAnimationFrame(() => {
    appRoot.classList.add('app-shell--visible');
    updateKanbanScrollHint();
  });

  if (animate) {
    authScreen.classList.add('auth--exit');
    authScreen.setAttribute('aria-hidden', 'true');
    window.setTimeout(() => {
      authScreen.hidden = true;
    }, 400);
  } else {
    authScreen.classList.add('auth--exit');
    authScreen.hidden = true;
    authScreen.setAttribute('aria-hidden', 'true');
  }
}

function showAuthError() {
  const authForm = document.getElementById('authForm');
  const authPassword = document.getElementById('authPassword');
  const authError = document.getElementById('authError');

  authError.hidden = false;
  authPassword.classList.add('auth__input--error');
  authForm.classList.remove('auth__form--shake');
  void authForm.offsetWidth;
  authForm.classList.add('auth__form--shake');
  authPassword.focus();
  authPassword.select();
}

function handleAuthSubmit(e) {
  e.preventDefault();

  const authPassword = document.getElementById('authPassword');
  const authError = document.getElementById('authError');
  const password = authPassword.value.trim();

  if (password === CRM_PASSWORD) {
    authError.hidden = true;
    authPassword.classList.remove('auth__input--error');
    setAuthenticated(true);
    showAppScreen(true);
    return;
  }

  showAuthError();
}

function logout() {
  setAuthenticated(false);
  showAuthScreen();
}

function initAuth() {
  const authForm = document.getElementById('authForm');
  const authPassword = document.getElementById('authPassword');

  authForm.addEventListener('submit', handleAuthSubmit);

  authPassword.addEventListener('input', () => {
    document.getElementById('authError').hidden = true;
    authPassword.classList.remove('auth__input--error');
  });

  document.getElementById('btnLogout').addEventListener('click', logout);

  if (isAuthenticated()) {
    showAppScreen(false);
  } else {
    showAuthScreen();
  }
}

initAuth();
